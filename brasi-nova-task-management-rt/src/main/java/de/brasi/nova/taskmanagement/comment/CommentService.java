package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskCommentRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaktionale Kommando-Operationen fuer Kommentare.
 *
 * <p>Alle Mutationen laufen in einer eigenen {@code @Transactional}-Transaktion.
 * Nach erfolgtem Commit wird via {@link InboxSignalService} ein {@code snapshot-invalidated}-Signal
 * an alle aktiven Teilnehmer gesendet.
 *
 * <p>Benachrichtigungen (REPLY, MENTION) werden nach der Persistierung emittiert.
 * Der Aktor wird niemals selbst benachrichtigt; REPLY hat Vorrang vor MENTION fuer denselben
 * Empfaenger (Deduplizierung).
 *
 * <p>Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}),
 * NIEMALS aus Client-Eingabedaten.
 */
@Service
public class CommentService {

  private static final Logger log = LoggerFactory.getLogger(CommentService.class);

  private final BaseTaskRepository taskRepository;
  private final BaseTaskCommentRepository commentRepository;
  private final TaskUserStateQueryRepository userStateQueryRepository;
  private final NotificationService notificationService;
  private final InboxSignalService inboxSignalService;
  private final InboxGenerationService inboxGenerationService;
  private final UserReadRepository userReadRepository;
  private final UserAccessor userAccessor;
  private final AuditTimeConverter auditTimeConverter;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public CommentService(
      final BaseTaskRepository taskRepository,
      final BaseTaskCommentRepository commentRepository,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final NotificationService notificationService,
      final InboxSignalService inboxSignalService,
      final InboxGenerationService inboxGenerationService,
      final UserReadRepository userReadRepository,
      final UserAccessor userAccessor,
      final AuditTimeConverter auditTimeConverter) {
    this.taskRepository = taskRepository;
    this.commentRepository = commentRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.notificationService = notificationService;
    this.inboxSignalService = inboxSignalService;
    this.inboxGenerationService = inboxGenerationService;
    this.userReadRepository = userReadRepository;
    this.userAccessor = userAccessor;
    this.auditTimeConverter = auditTimeConverter;
  }

  /**
   * Erstellt einen neuen Kommentar fuer eine Aufgabe.
   *
   * <p>Ablauf:
   * <ol>
   *   <li>Actor aus dem Security-Context laden.
   *   <li>Aufgabe tenant-scoped laden (404-Masking bei fremder/fehlender UUID).
   *   <li>Optionalen Eltern-Kommentar aufloesen und validieren (400 bei fremder/fehlender UUID).
   *   <li>Kommentar persistieren (TENANT_ID, TASK_ID, AUTHOR_REF, TEXT, IS_EDITED=false).
   *   <li>COMMENT_COUNT++ + LAST_ACTIVITY_AT aktualisieren.
   *   <li>Mentions aufloesen und REPLY/MENTION-Benachrichtigungen emittieren (Deduplizierung).
   *   <li>Inbox-Generation-Bump + Invalidate fuer alle aktiven Teilnehmer der Aufgabe (der
   *       LAST_ACTIVITY_AT-Bump verschiebt die aktive Keyset-Position jedes Teilnehmers -&gt; alte
   *       aktive Cursor werden beim naechsten Folge-Read mit 410 abgelehnt statt stale 200).
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input          Kommentar-Eingabe
   * @return {@link CommentModel} des neuen Kommentars
   * @throws EntityNotFoundException   wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws EntityValidationException wenn Text leer/null oder Eltern-Kommentar ungueltig
   */
  @Transactional
  public CommentModel createComment(
      final UUID taskExternalId, final CommentCreateInput input) {

    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Eltern-Kommentar aufloesen (falls angegeben)
    TaskComment parentComment = null;
    if (input.parentCommentId() != null) {
      parentComment = resolveParentComment(input.parentCommentId(), taskId, tenantId);
    }

    // Kommentar anlegen
    final TaskComment comment = new TaskComment();
    comment.setTenantId(tenantId);
    comment.setTaskId(taskId);
    comment.setAuthorRef(actorRef);
    comment.setText(input.text());
    comment.setEdited(false);
    if (parentComment != null) {
      comment.setParentCommentId(parentComment.getId());
    }

    final TaskComment saved = commentRepository.save(comment);

    log.info(
        "CommentService.createComment: Kommentar {} fuer Aufgabe {} (id={}) von Akteur {}"
            + " in Mandant {} persistiert.",
        saved.getUuid(),
        taskExternalId,
        taskId,
        actorRef,
        tenantId);

    // Zaehler und Aktivitaet aktualisieren
    task.setCommentCount(task.getCommentCount() + 1);
    task.setLastActivityAt(LocalDateTime.now());
    taskRepository.save(task);

    // Benachrichtigungs-Fan-out (Deduplizierung + kein Selbst-Notify)
    UUID replyRecipient = null;
    if (parentComment != null) {
      final UUID parentAuthor = parentComment.getAuthorRef();
      if (!actorRef.equals(parentAuthor)) {
        replyRecipient = parentAuthor;
        notificationService.emit(
            replyRecipient,
            taskId,
            NotificationTypeCodes.REPLY,
            NotificationSeverityCodes.INFO,
            "Es gibt eine neue Antwort auf deinen Kommentar.",
            task.getTitle(),
            actorRef);
        log.debug(
            "CommentService.createComment: REPLY-Benachrichtigung an {} emittiert.", replyRecipient);
      }
    }

    // Mentions: explizite Ziel-GUIDs des Clients (NICHT aus dem Text geparst). Deduplizieren +
    // mandanten-scoped auf AKTIVE Benutzer validieren (findActiveByUuids -> fremd-tenant-/inaktive
    // GUIDs loesen NICHT auf = kein Cross-Tenant-Leak); nicht aufloesbare GUIDs still verwerfen
    // (best-effort: eine kaputte Mention darf den Kommentar nicht scheitern lassen).
    final List<UUID> mentionRefs;
    if (input.mentions().isEmpty()) {
      mentionRefs = List.of();
    } else {
      final Set<UUID> dedupedMentions = new LinkedHashSet<>(input.mentions());
      mentionRefs =
          userReadRepository.findActiveByUuids(dedupedMentions).stream()
              .map(User::getUuid)
              .toList();
    }
    final UUID finalReplyRecipient = replyRecipient;
    for (final UUID mentionRef : mentionRefs) {
      // Selbst-Notify ausschliessen + Deduplizierung: wer REPLY bekommt, bekommt kein MENTION
      if (actorRef.equals(mentionRef)) {
        continue;
      }
      if (mentionRef.equals(finalReplyRecipient)) {
        continue;
      }
      notificationService.emit(
          mentionRef,
          taskId,
          NotificationTypeCodes.MENTION,
          NotificationSeverityCodes.INFO,
          "Du wurdest in einem Kommentar erwaehnt.",
          task.getTitle(),
          actorRef);
      log.debug(
          "CommentService.createComment: MENTION-Benachrichtigung an {} emittiert.", mentionRef);
    }

    // Finding 2 / Option B + After-Commit: fuer ALLE aktiven Teilnehmer der Aufgabe (= TASK_USER_STATE
    // mit UNSUBSCRIBED_AT IS NULL, innerhalb dieser Tx ermittelt) (a) die Inbox-Generation ATOMAR in
    // dieser Tx bumpen und (b) das snapshot-invalidated Signal registrieren (nach Commit). Begruendung
    // Bump: createComment setzt LAST_ACTIVITY_AT = now() (oben). Da der aktive Keyset-Cursor genau auf
    // (asOf, LAST_ACTIVITY_AT, externalId) ordnet, verschiebt das die Keyset-Position des Tasks im
    // Scroll jedes aktiven Teilnehmers - ein alter aktiver Cursor koennte den Task sonst ueberspringen
    // oder doppelt liefern (stale pagination), ohne 410 zu erhalten. Dieselbe Teilnehmer-Menge fuer
    // Bump UND Invalidate - konsistent zum Muster der uebrigen Aktivitaets-/Membership-Mutationen
    // (TaskCommandService.patch, AssigneeService.updateAssignees/exit/addParticipants).
    final List<TaskUserState> activeStates =
        userStateQueryRepository.findActiveUserRefsByTaskId(taskId);
    final List<UUID> activeUserRefs =
        activeStates.stream()
            .map(TaskUserState::getUserRef)
            .collect(Collectors.toList());
    if (!activeUserRefs.isEmpty()) {
      // Deadlock-Robustheit (Codex #6): Bump NACH dem TASK-save (oben, taskRepository.save) ueber den
      // zentralen Helfer in DETERMINISTISCH SORTIERTER Ref-Reihenfolge - so wird ueberall zuerst die
      // TASK-Zeile und dann die TASK_INBOX_GENERATION-Zeilen gesperrt, und parallele Mutationen mit
      // ueberlappenden Teilnehmern sperren die Generation-Zeilen in gleicher Reihenfolge (keine
      // ueberkreuzte Lock-Order).
      inboxGenerationService.bumpSorted(tenantId, activeUserRefs);
      inboxSignalService.invalidate(tenantId, activeUserRefs);
    }

    // Antwort-DTO zusammenstellen
    final Instant createdAt = auditTimeConverter.toInstant(saved.getCreatedAt());
    return new CommentModel(
        saved.getUuid(),
        taskExternalId,
        actorRef,
        input.parentCommentId(),
        input.text(),
        false,
        createdAt);
  }

  /**
   * Loest den Eltern-Kommentar anhand seiner externen UUID auf und validiert ihn.
   *
   * <p>Gibt eine identische, neutrale {@link EntityValidationException} (HTTP 400) zurueck,
   * wenn:
   * <ul>
   *   <li>der Kommentar nicht gefunden wurde,
   *   <li>er zu einer anderen Aufgabe gehoert,
   *   <li>er einem anderen Mandanten zugehoert.
   * </ul>
   *
   * @param parentUuid externe UUID des Eltern-Kommentars
   * @param taskId     interne ID der aktuellen Aufgabe
   * @param tenantId   aktueller Mandanten-Schluessel
   * @return der aufgeloeste Eltern-Kommentar
   * @throws EntityValidationException wenn der Kommentar ungueltig ist (HTTP 400)
   */
  private TaskComment resolveParentComment(
      final UUID parentUuid, final Long taskId, final long tenantId) {
    final Optional<TaskComment> opt = commentRepository.findByUuid(parentUuid.toString());
    if (opt.isEmpty()) {
      throw new EntityValidationException("Der angegebene Eltern-Kommentar ist ungueltig.");
    }
    final TaskComment parent = opt.get();
    if (!Objects.equals(taskId, parent.getTaskId())) {
      throw new EntityValidationException("Der angegebene Eltern-Kommentar ist ungueltig.");
    }
    if (!Objects.equals(tenantId, parent.getTenantId())) {
      throw new EntityValidationException("Der angegebene Eltern-Kommentar ist ungueltig.");
    }
    if (parent.getEntityState() != io.keel.framework.api.model.EntityState.ACTIVE) {
      throw new EntityValidationException("Der angegebene Eltern-Kommentar ist ungueltig.");
    }
    return parent;
  }

  /**
   * Laedt eine Aufgabe anhand ihrer externen UUID und prueft die Mandantenzugehoerigkeit.
   *
   * <p>Beide Fehlerfaelle (nicht gefunden / fremder Mandant) liefern eine identische, neutrale
   * {@link EntityNotFoundException} (HTTP 404), um keine Existenz-Information preiszugeben
   * (arch-v2 §8 / Cross-Tenant-Disclosure-Schutz).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param tenantId       erwarteter Mandanten-Schluessel
   * @return die gefundene Aufgabe
   * @throws EntityNotFoundException wenn nicht gefunden oder fremdem Tenant zugehoerig (HTTP 404)
   */
  private Task requireTask(final UUID taskExternalId, final long tenantId) {
    final Optional<Task> opt = taskRepository.findByUuid(taskExternalId.toString());
    if (opt.isEmpty()) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    final Task task = opt.get();
    if (!Objects.equals(tenantId, task.getTenantId())) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    // Final-Review P1-1: logisch deaktivierte Aufgaben neutral wie fremder Tenant maskieren (404).
    if (task.getEntityState() != io.keel.framework.api.model.EntityState.ACTIVE) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    return task;
  }

}
