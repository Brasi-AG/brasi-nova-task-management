package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
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
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
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
  private final TaskParticipantQueryRepository participantQueryRepository;
  private final NotificationService notificationService;
  private final InboxSignalService inboxSignalService;
  private final InboxGenerationService inboxGenerationService;
  private final UserReadRepository userReadRepository;
  private final UserAccessor userAccessor;
  private final AuditTimeConverter auditTimeConverter;
  private final TaskMembershipGuard membershipGuard;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public CommentService(
      final BaseTaskRepository taskRepository,
      final BaseTaskCommentRepository commentRepository,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final TaskParticipantQueryRepository participantQueryRepository,
      final NotificationService notificationService,
      final InboxSignalService inboxSignalService,
      final InboxGenerationService inboxGenerationService,
      final UserReadRepository userReadRepository,
      final UserAccessor userAccessor,
      final AuditTimeConverter auditTimeConverter,
      final TaskMembershipGuard membershipGuard) {
    this.taskRepository = taskRepository;
    this.commentRepository = commentRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.participantQueryRepository = participantQueryRepository;
    this.notificationService = notificationService;
    this.inboxSignalService = inboxSignalService;
    this.inboxGenerationService = inboxGenerationService;
    this.userReadRepository = userReadRepository;
    this.userAccessor = userAccessor;
    this.auditTimeConverter = auditTimeConverter;
    this.membershipGuard = membershipGuard;
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

    // Standard-Selfservice (Slice 3): WRITE-Member-Guard NACH Tenant-/ACTIVE-Check und VOR jeder
    // Mutation. Nur aktive, nicht ausgetretene PERSON-Mitglieder duerfen kommentieren; Non-/Ex-Member
    // -> neutrale 404 (kein Kommentar, kein Count/Activity-Bump, kein Notify).
    membershipGuard.requireWriteMember(taskId, actorRef, tenantId);

    // Eltern-Kommentar aufloesen (falls angegeben)
    TaskComment parentComment = null;
    if (input.parentCommentId() != null) {
      parentComment = resolveParentComment(input.parentCommentId(), taskId, tenantId);
    }

    // Reader-Refs vor dem TASK-save materialisieren, damit kein Membership-SELECT unter dessen
    // X-Lock gegen einen Exit mit umgekehrter Lock-Reihenfolge laeuft.
    final Set<UUID> readerRefs = new LinkedHashSet<>();
    userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
        .map(TaskUserState::getUserRef)
        .forEach(readerRefs::add);
    participantQueryRepository.findArchiveReadersByTaskId(taskId).stream()
        .map(TaskParticipant::getParticipantRef)
        .forEach(readerRefs::add);

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

    // Benachrichtigungs-Fan-out (Deduplizierung + kein Selbst-Notify).
    //
    // Standard-Selfservice (Slice 3/Auflage 4): Empfaenger-Filter fuer BEIDE Typen (REPLY UND
    // MENTION) ausschliesslich auf die WRITE-Member-Menge der Aufgabe = aktive PERSON-Teilnehmer mit
    // EXIT_KIND IS NULL. Ein ausgetretener Nutzer (READ-Member, EXIT_KIND gesetzt) bzw. ein
    // Nicht-Teilnehmer ist KEIN Empfaenger und wird still verworfen (best-effort: ein verworfener
    // Mention/Reply laesst den Kommentar NICHT scheitern). Die WRITE-Member-Pruefung ist ein reiner
    // EXISTS-Read (membershipGuard.isWriteMember) pro Empfaenger - konsistent zum Guard-Praedikat.
    UUID replyRecipient = null;
    if (parentComment != null) {
      final UUID parentAuthor = parentComment.getAuthorRef();
      // REPLY-Empfaenger muss WRITE-Member sein (sonst kein Ping an eine verlassene/fremde Task).
      if (!actorRef.equals(parentAuthor)
          && membershipGuard.isWriteMember(taskId, parentAuthor, tenantId)) {
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
      // Standard-Selfservice (Slice 3/Auflage 4): nur WRITE-Member werden erwaehnt. Ein
      // ausgetretener (READ-Member) oder Nicht-Teilnehmer wird still verworfen (kein Ping an eine
      // verlassene Task), obwohl findActiveByUuids ihn als aktiven Mandanten-Nutzer aufgeloest hat.
      if (!membershipGuard.isWriteMember(taskId, mentionRef, tenantId)) {
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

    // TASK-save vor global UUID-sortiertem Bump; Signal fuer exakt dieselbe deduplizierte Menge.
    if (!readerRefs.isEmpty()) {
      inboxGenerationService.bumpSorted(tenantId, readerRefs);
      inboxSignalService.invalidate(tenantId, readerRefs);
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
