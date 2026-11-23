package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.attachment.AttachmentModelMapper;
import de.brasi.nova.taskmanagement.attachment.AttachmentProperties;
import de.brasi.nova.taskmanagement.attachment.AttachmentWriter;
import de.brasi.nova.taskmanagement.attachment.PreparedAttachment;
import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationMuteFilter;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
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
 * <p>Alle Mutationen laufen in einer eigenen {@code @Transactional}-Transaktion. Nach erfolgtem
 * Commit wird via {@link InboxSignalService} ein {@code snapshot-invalidated}-Signal an alle
 * aktiven Teilnehmer gesendet.
 *
 * <p>Benachrichtigungen (REPLY, MENTION) werden nach der Persistierung emittiert. Der Aktor wird
 * niemals selbst benachrichtigt; REPLY hat Vorrang vor MENTION fuer denselben Empfaenger
 * (Deduplizierung). Erwaehnte aktive Mandanten-Nutzer, die noch nicht Mitglied sind, werden als
 * Beteiligte aufgenommen, wenn der Kommentierende Beteiligte hinzufuegen darf (Intake #156).
 *
 * <p>Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}), NIEMALS aus
 * Client-Eingabedaten.
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
  private final UserAccessor userAccessor;
  private final AuditTimeConverter auditTimeConverter;
  private final TaskMembershipGuard membershipGuard;
  private final NotificationMuteFilter muteFilter;
  private final AttachmentWriter attachmentWriter;
  private final AttachmentModelMapper attachmentModelMapper;
  private final AttachmentProperties attachmentProperties;
  private final MentionRecipientService mentionRecipientService;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public CommentService(
      final BaseTaskRepository taskRepository,
      final BaseTaskCommentRepository commentRepository,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final TaskParticipantQueryRepository participantQueryRepository,
      final NotificationService notificationService,
      final InboxSignalService inboxSignalService,
      final InboxGenerationService inboxGenerationService,
      final UserAccessor userAccessor,
      final AuditTimeConverter auditTimeConverter,
      final TaskMembershipGuard membershipGuard,
      final NotificationMuteFilter muteFilter,
      final AttachmentWriter attachmentWriter,
      final AttachmentModelMapper attachmentModelMapper,
      final AttachmentProperties attachmentProperties,
      final MentionRecipientService mentionRecipientService) {
    this.taskRepository = taskRepository;
    this.commentRepository = commentRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.participantQueryRepository = participantQueryRepository;
    this.notificationService = notificationService;
    this.inboxSignalService = inboxSignalService;
    this.inboxGenerationService = inboxGenerationService;
    this.userAccessor = userAccessor;
    this.auditTimeConverter = auditTimeConverter;
    this.membershipGuard = membershipGuard;
    this.muteFilter = muteFilter;
    this.attachmentWriter = attachmentWriter;
    this.attachmentModelMapper = attachmentModelMapper;
    this.attachmentProperties = attachmentProperties;
    this.mentionRecipientService = mentionRecipientService;
  }

  /**
   * Erstellt einen neuen Kommentar ohne Anhaenge (JSON-Weg). Delegiert an {@link
   * #createComment(UUID, CommentCreateInput, List)} mit leerer Anhangliste; die Antwort traegt
   * {@code attachments = []}.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input Kommentar-Eingabe
   * @return {@link CommentModel} des neuen Kommentars
   * @throws EntityNotFoundException wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws EntityValidationException wenn Text leer/null oder Eltern-Kommentar ungueltig
   */
  @Transactional
  public CommentModel createComment(final UUID taskExternalId, final CommentCreateInput input) {
    return createComment(taskExternalId, input, List.of());
  }

  /**
   * Erstellt einen neuen Kommentar fuer eine Aufgabe, optional mit Anhaengen (Intake #152).
   * Kommentar und Anhaenge entstehen in derselben Transaktion: scheitert ein Schritt, rollt alles
   * zurueck, und der {@link AttachmentWriter} entfernt die in dieser Transaktion geschriebenen
   * Dateien.
   *
   * <p>Ablauf:
   *
   * <ol>
   *   <li>Actor aus dem Security-Context laden.
   *   <li>Aufgabe tenant-scoped laden (404-Masking bei fremder/fehlender UUID).
   *   <li>Optionalen Eltern-Kommentar aufloesen und validieren (400 bei fremder/fehlender UUID).
   *   <li>Erwaehnte Abteilungen ueber den {@link DivisionMentionResolver} auf ihre Personen
   *       aufloesen (lokale Projektion, Intake #190; 400 bei unbekannter oder inaktiver Abteilung
   *       oder zu vielen Personen) und mit den Mentions vereinigen.
   *   <li>Mentions aufloesen (aktive Mandanten-Nutzer); Mitglieder sind MENTION-Ziele. Nicht-
   *       Mitglieder werden als Beteiligte aufgenommen, wenn der Kommentierende das Recht von
   *       {@code POST /participants} hat (Intake #156), sonst still verworfen.
   *   <li>COMMENT_COUNT++, ANHANG_ANZAHL um die Zahl der Anhaenge (Intake #194, ohne Anhaenge +0)
   *       und LAST_ACTIVITY_AT aktualisieren (ein versionierter TASK-save, Schreibsperre der
   *       Aufgabenzeile bis zum Commit).
   *   <li>Kommentar persistieren (TENANT_ID, TASK_ID, AUTHOR_REF, TEXT, IS_EDITED=false); erst nach
   *       dem TASK-save, damit ANGELEGT_AM unter der Aufgabensperre entsteht und sich mit {@code
   *       MarkerService#markSeen} serialisiert (Review #150).
   *   <li>Nur bei Anhaengen (Intake #152): Hoechstzahl je Kommentar sichern (400 bei mehr als
   *       {@link AttachmentProperties#maxPerTarget()}), dann Anhangzeilen und Dateien am neuen
   *       Kommentar speichern ({@link AttachmentWriter#store}, laeuft in dieser Transaktion). Keine
   *       Lesung von {@code TASK_ANHANG}: ein neuer Kommentar hat noch keine Anhaenge.
   *   <li>REPLY/MENTION/COMMENT-Benachrichtigungen emittieren (Deduplizierung). Neu Aufgenommene
   *       bekommen nur die MENTION, keine PARTICIPANT-Benachrichtigung.
   *   <li>Inbox-Generation-Bump + Invalidate fuer alle aktiven Teilnehmer der Aufgabe (der
   *       LAST_ACTIVITY_AT-Bump verschiebt die aktive Keyset-Position jedes Teilnehmers -&gt; alte
   *       aktive Cursor werden beim naechsten Folge-Read mit 410 abgelehnt statt stale 200).
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input Kommentar-Eingabe
   * @param attachments gepruefte und vorbereitete Dateien in Upload-Reihenfolge; leer fuer einen
   *     Kommentar ohne Anhaenge, nie {@code null}
   * @return {@link CommentModel} des neuen Kommentars mit den gespeicherten Anhaengen (in der
   *     Reihenfolge der Eingabe, nicht entfernbar)
   * @throws EntityNotFoundException wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws NullPointerException wenn {@code attachments} {@code null} ist (der Aufrufer uebergibt
   *     {@code List.of()}); die Pruefung steht vor jedem Zugriff
   * @throws EntityValidationException wenn Text leer/null, Eltern-Kommentar ungueltig, eine
   *     erwaehnte Abteilung unbekannt oder inaktiv ist, die erwaehnten Abteilungen zu viele
   *     Personen umfassen oder mehr Anhaenge als erlaubt uebergeben wurden
   * @throws java.io.UncheckedIOException wenn eine Anhang-Datei nicht geschrieben werden kann
   */
  @Transactional
  public CommentModel createComment(
      final UUID taskExternalId,
      final CommentCreateInput input,
      final List<PreparedAttachment> attachments) {
    Objects.requireNonNull(
        attachments, "Die Liste der Anhänge darf nicht null sein (leer für keinen Anhang).");

    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Standard-Selfservice (Slice 3): WRITE-Member-Guard NACH Tenant-/ACTIVE-Check und VOR jeder
    // Mutation. Nur aktive, nicht ausgetretene PERSON-Mitglieder duerfen kommentieren;
    // Non-/Ex-Member
    // -> neutrale 404 (kein Kommentar, kein Count/Activity-Bump, kein Notify).
    membershipGuard.requireWriteMember(taskId, actorRef, tenantId);

    // Eltern-Kommentar aufloesen (falls angegeben)
    TaskComment parentComment = null;
    if (input.parentCommentId() != null) {
      parentComment = resolveParentComment(input.parentCommentId(), taskId, tenantId);
    }

    // ---- Lock-Reihenfolge (Intake #136): ALLE Membership-/Participant-Reads des Methodenpfads VOR
    // dem TASK-save (commentCount/LAST_ACTIVITY_AT) ausfuehren - nicht nur die Reader-/Archiv-Refs,
    // sondern auch die REPLY-Autor-WRITE-Pruefung, die MENTION-Empfaenger-Aufloesung UND die
    // aktiven
    // PERSON-Assignees. Grund: AssigneeService.updateAssignees() sperrt Teilnehmerzeilen VOR seinem
    // TASK-save (Teilnehmer-dann-Task). Wenn createComment den Task sperrt (save) und DANACH
    // Teilnehmerzeilen liest, entsteht die invertierte Lock-Reihenfolge -> moeglicher
    // MSSQL-Deadlock
    // (ausgeloest durch Top-Level-Mention oder die neue Assignee-Benachrichtigung). Daher: zuerst
    // alle Membership-/Assignee-Daten lesen, DANN task.save, DANN Emit aus den vorab geladenen
    // Mengen. Etabliertes Muster (vgl. TaskCommandService.patch "vor dem TASK-save lesen").
    // Intake #156: auch die Aufnahme erwaehnter Nicht-Mitglieder (Teilnehmer- und State-WRITES)
    // liegt vor dem task.save (Teilnehmer-dann-Task wie updateAssignees); der sortierte Bump der
    // Aufgenommenen folgt mit allen Lesern nach dem save.

    // Reader-Refs vor dem TASK-save materialisieren, damit kein Membership-SELECT unter dessen
    // X-Lock gegen einen Exit mit umgekehrter Lock-Reihenfolge laeuft.
    final Set<UUID> readerRefs = new LinkedHashSet<>();
    userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
        .map(TaskUserState::getUserRef)
        .forEach(readerRefs::add);
    participantQueryRepository.findArchiveReadersByTaskId(taskId).stream()
        .map(TaskParticipant::getParticipantRef)
        .forEach(readerRefs::add);

    // REPLY-Empfaenger VOR dem TASK-save bestimmen: der Eltern-Autor erhaelt genau dann eine REPLY-
    // Benachrichtigung, wenn er nicht der Akteur und aktiver WRITE-Member ist (sonst kein Ping an
    // eine verlassene/fremde Task). Die WRITE-Pruefung ist ein reiner EXISTS-Read.
    UUID replyRecipient = null;
    if (parentComment != null) {
      final UUID parentAuthor = parentComment.getAuthorRef();
      if (!actorRef.equals(parentAuthor)
          && membershipGuard.isWriteMember(taskId, parentAuthor, tenantId)) {
        replyRecipient = parentAuthor;
      }
    }

    // MENTION-Empfaenger VOR dem TASK-save ermitteln (MentionRecipientService, Intake #190 Task 10;
    // vorher hier inline), hier die Lesephase; die Aufnahme folgt nach den Assignee-Reads. Mentions
    // sind explizite Ziel-GUIDs des Clients (NICHT aus dem Text geparst); erwaehnte Abteilungen
    // loest der Dienst ueber die lokale Projektion auf (400 bei
    // unbekannter/inaktiver Abteilung oder ueber der Personengrenze, dann ist nichts geschrieben).
    // Nur aktive Mandanten-Nutzer; Selbst-Notify und REPLY-Vorrang (excludedRecipient) sind
    // herausgefiltert; WRITE-Mitglieder sind Ziel. Erwaehnte Nicht-Mitglieder nimmt der Dienst
    // unter
    // dem Recht von POST /participants als Beteiligte auf (Intake #156): das sind Teilnehmer- und
    // State-Writes und gehoeren deshalb VOR den TASK-save (Mitgliedschaft vor Aufgabe, wie bei
    // updateAssignees); der sortierte Generation-Bump folgt NACH dem save, gemeinsam mit den
    // uebrigen Lesern (readerRefs). Die Aufgenommenen bekommen NUR die MENTION unten, keine
    // PARTICIPANT-Benachrichtigung; ein Mute bleibt (MENTION ist ohnehin davon ausgenommen).
    final UUID finalReplyRecipient = replyRecipient;
    final MentionRecipientService.Candidates mentionCandidates =
        mentionRecipientService.resolve(
            taskId,
            tenantId,
            actorRef,
            finalReplyRecipient,
            input.mentions(),
            input.divisionMentions());

    // COMMENT-Empfaenger (Intake #136) VOR dem TASK-save bestimmen: aktive PERSON-Assignees der
    // Aufgabe. Nur fuer Top-Level-Kommentare (parentComment == null) relevant; der Read laeuft
    // dennoch im Lock-Order-Block (keine zusaetzlichen Reads nach dem save).
    // Intake #158: Personen, die die Aufgabe stummgeschaltet haben, erhalten kein COMMENT. Die
    // Mute-Zustaende werden gebuendelt (eine Query) ebenfalls VOR dem TASK-save gelesen; MENTION
    // und REPLY sind von der Regel ausgenommen und bleiben unberuehrt (NotificationMuteFilter).
    final List<UUID> assigneeRefs;
    if (parentComment == null) {
      assigneeRefs =
          muteFilter.withoutMuted(
              taskId,
              NotificationTypeCodes.COMMENT,
              participantQueryRepository.findActiveAssigneesByTaskId(taskId).stream()
                  .map(TaskParticipant::getParticipantRef)
                  .toList());
    } else {
      assigneeRefs = List.of();
    }

    // Aufnahme der erwaehnten Nicht-Mitglieder: erst jetzt, nachdem auch die Assignees gelesen sind
    // (die Aufnahme ist die erste Schreibung des Pfads).
    final MentionRecipients mentions = mentionRecipientService.admit(mentionCandidates);
    final Set<UUID> mentionTargets = mentions.notifyTargets();
    readerRefs.addAll(mentions.admitted());

    // Test-Seam: deterministische Synchronisation der Nebenlaeufigkeits-ITs GENAU NACH den
    // Membership-/Assignee-Reads und VOR dem versionierten TASK-save - zu diesem Zeitpunkt haelt
    // der
    // Verlierer noch KEINE TASK-Schreibsperre (der requireTask-SELECT gab seine Shared-Locks frei),
    // sodass ein nebenlaeufiger Gewinner (updateAssignees) blockierungsfrei committen und die
    // TASK-VERSION bumpen kann; der Verlierer trifft dann beim versionierten save die stale VERSION
    // -> 409 + voller Rollback. Produktiv ein No-Op.
    onBeforeTaskSaveForTest(taskId);

    // Zaehler und Aktivitaet aktualisieren. Der KSF-save prueft optimistisch die TASK-VERSION; ein
    // nebenlaeufiger VERSION-Bump (z.B. updateAssignees) laesst den Verlierer hier mit
    // BusinessRuleException (409) scheitern -> die GANZE createComment-Tx rollt zurueck (Aufnahme,
    // Notifications; der Kommentar ist zu diesem Zeitpunkt noch nicht angelegt, der
    // After-Commit-Push feuert nicht). Kein Teil-Commit.
    task.setCommentCount(task.getCommentCount() + 1);
    // Intake #194: Anhaenge des neuen Kommentars am selben save zaehlen; scheitert das Speichern
    // der Anhaenge danach, rollt der Zaehler mit der ganzen Transaktion zurueck.
    task.setAttachmentCount(task.getAttachmentCount() + attachments.size());
    task.setLastActivityAt(LocalDateTime.now());
    taskRepository.save(task);

    // Kommentar erst NACH dem TASK-save anlegen (Review #150): KSF stempelt ANGELEGT_AM beim save,
    // und dieser Zeitstempel muss unter der Schreibsperre der Aufgabenzeile entstehen, die bis zum
    // Commit gehalten wird. MarkerService#markSeen stempelt SEEN_AT unter einer Update-Sperre
    // derselben Zeile; so kann kein Kommentar mit Zeitstempel vor SEEN_AT erst nach dessen Commit
    // sichtbar werden (isNew bliebe sonst faelschlich false). Ein Versionskonflikt am TASK-save
    // rollt wie bisher alles zurueck; der Kommentar entsteht dann gar nicht erst. Neue Entity und
    // save stehen beide hier, gleich ob KSF ANGELEGT_AM beim Erzeugen oder beim save setzt.
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

    // Intake #152 B2: Anhaenge am neuen Kommentar speichern, NACH dem Kommentar-Insert (die
    // Kommentar-ID entsteht erst dort) und VOR jeder Benachrichtigung. Scheitert das Speichern,
    // rollt die ganze Transaktion zurueck (Kommentar, Zaehler, Aufnahme) und es wird niemand
    // benachrichtigt; der Writer raeumt die schon geschriebenen Dateien selbst weg.
    final List<AttachmentModel> attachmentModels =
        storeCommentAttachments(task, saved.getId(), actorRef, taskExternalId, attachments);

    // Benachrichtigungs-Fan-out (NACH dem TASK-save) aus den VOR dem save geladenen Mengen -
    // Deduplizierung + kein Selbst-Notify. Die Empfaenger-Filterung (Self-Exclusion, REPLY-Vorrang,
    // WRITE-Member) ist bereits in replyRecipient/mentionTargets/assigneeRefs materialisiert; hier
    // folgt nur noch der Emit. So lesen die Membership-/Assignee-SELECTs nicht unter dem TASK-save-
    // X-Lock gegen einen Exit mit umgekehrter Lock-Reihenfolge (Intake #136).

    // REPLY (nur bei Reply-Kommentar): an den Eltern-Autor, sofern aktiver WRITE-Member und nicht
    // der Akteur (Standard-Selfservice Slice 3/Auflage 4; bereits oben geprueft).
    if (replyRecipient != null) {
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

    // MENTION: an jeden vorab gefilterten Mention-Empfaenger (aktiver WRITE-Member, nicht Akteur,
    // nicht bereits REPLY-Empfaenger).
    for (final UUID mentionRef : mentionTargets) {
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

    // COMMENT (Intake #136): nur fuer Top-Level-Kommentare (parentComment == null). Empfaenger =
    // vorab geladene aktive PERSON-Assignees. Dedup (D4): Self-Exclusion (actorRef == assignee);
    // MENTION/REPLY-Vorrang (ein Assignee, der in DIESEM Create bereits als MENTION-Empfaenger
    // (mentionTargets) oder REPLY-Empfaenger (finalReplyRecipient) benachrichtigt wurde, bekommt
    // kein
    // zusaetzliches COMMENT). REPLY kann bei Top-Level nicht auftreten (parentComment == null);
    // defensiv dennoch geprueft. Keine reinen Participants; ein Creator ausserhalb der
    // Assignee-Menge
    // wird nicht benachrichtigt.
    for (final UUID assigneeRef : assigneeRefs) {
      if (actorRef.equals(assigneeRef)) {
        continue;
      }
      if (assigneeRef.equals(finalReplyRecipient)) {
        continue;
      }
      if (mentionTargets.contains(assigneeRef)) {
        continue;
      }
      notificationService.emit(
          assigneeRef,
          taskId,
          NotificationTypeCodes.COMMENT,
          NotificationSeverityCodes.INFO,
          "Neuer Kommentar bei der dir zugewiesenen Aufgabe.",
          task.getTitle(),
          actorRef);
      log.debug(
          "CommentService.createComment: COMMENT-Benachrichtigung an Assignee {} emittiert.",
          assigneeRef);
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
        createdAt,
        // Eigener Kommentar: fuer den Autor nie neu (Intake #150).
        false,
        // Intake #152: gespeicherte Anhaenge in Eingabereihenfolge; ohne Anhaenge leer, nie null.
        attachmentModels);
  }

  /**
   * Speichert die Anhaenge eines neuen Kommentars (Intake #152 B2). Laeuft in der Transaktion von
   * {@link #createComment(UUID, CommentCreateInput, List)} nach dem Kommentar-Insert und vor jeder
   * Benachrichtigung.
   *
   * <p>Ohne Anhaenge passiert nichts (weder {@link AttachmentWriter#store} noch der Test-Seam).
   * Sonst sichert die Methode die Hoechstzahl je Kommentar erneut ab (die eigentliche Pruefung
   * liegt vor der Transaktion im {@code AttachmentUploadPreparer}); ein neuer Kommentar hat noch
   * keine aktiven Anhaenge, daher genuegt die Zahl der neuen Dateien ohne Lesung von {@code
   * TASK_ANHANG}. Die Antwort-Modelle sind nie entfernbar: Kommentar-Anhaenge lassen sich nicht
   * einzeln entfernen.
   *
   * @param task Aufgabe des Kommentars
   * @param commentId interne ID des neuen Kommentars
   * @param actorRef UUID der hochladenden Person
   * @param taskExternalId externe UUID der Aufgabe (Pfad der Links)
   * @param attachments gepruefte und vorbereitete Dateien
   * @return Modelle der gespeicherten Anhaenge in der Reihenfolge, die der Writer liefert
   * @throws EntityValidationException wenn mehr Anhaenge als erlaubt uebergeben wurden (HTTP 400)
   */
  private List<AttachmentModel> storeCommentAttachments(
      final Task task,
      final Long commentId,
      final UUID actorRef,
      final UUID taskExternalId,
      final List<PreparedAttachment> attachments) {
    if (attachments.isEmpty()) {
      return List.of();
    }
    if (attachments.size() > attachmentProperties.maxPerTarget()) {
      throw new EntityValidationException(attachmentProperties.tooManyAttachmentsMessage());
    }
    final List<TaskAttachment> stored =
        attachmentWriter.store(task, commentId, actorRef, attachments);
    onAfterAttachmentsStoredForTest(commentId);
    log.info(
        "CommentService.createComment: {} Anhang/Anhaenge an Kommentar (id={}) der Aufgabe {}"
            + " gespeichert.",
        stored.size(),
        commentId,
        taskExternalId);
    return stored.stream()
        .map(attachment -> attachmentModelMapper.toModel(attachment, taskExternalId, false))
        .toList();
  }

  /**
   * Loest den Eltern-Kommentar anhand seiner externen UUID auf und validiert ihn.
   *
   * <p>Gibt eine identische, neutrale {@link EntityValidationException} (HTTP 400) zurueck, wenn:
   *
   * <ul>
   *   <li>der Kommentar nicht gefunden wurde,
   *   <li>er zu einer anderen Aufgabe gehoert,
   *   <li>er einem anderen Mandanten zugehoert.
   * </ul>
   *
   * @param parentUuid externe UUID des Eltern-Kommentars
   * @param taskId interne ID der aktuellen Aufgabe
   * @param tenantId aktueller Mandanten-Schluessel
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
   * {@link EntityNotFoundException} (HTTP 404), um keine Existenz-Information preiszugeben (arch-v2
   * §8 / Cross-Tenant-Disclosure-Schutz).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param tenantId erwarteter Mandanten-Schluessel
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

  /**
   * Test-Seam (Default No-Op): Haken GENAU NACH den Membership-/Assignee-Reads und VOR dem
   * versionierten {@code taskRepository.save(task)} in {@link #createComment}. Ermoeglicht
   * deterministische Nebenlaeufigkeits-ITs (Intake #136): der Verlierer wird nachweislich VOR
   * seinem ersten TASK-Write synchronisiert, sodass ein nebenlaeufiger Gewinner (z.B. {@code
   * AssigneeService.updateAssignees}) blockierungsfrei committen und die TASK-VERSION bumpen kann.
   * Im Produktivbetrieb ein No-Op; nur Test-Subklassen ueberschreiben diesen Haken.
   *
   * @param taskId interne Aufgaben-ID
   */
  protected void onBeforeTaskSaveForTest(final Long taskId) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op): Haken GENAU NACH {@link AttachmentWriter#store} und VOR der ersten
   * Benachrichtigung in {@link #createComment(UUID, CommentCreateInput, List)}; laeuft nur, wenn
   * Anhaenge uebergeben wurden (Intake #152). Ermoeglicht Rollback-ITs: eine Test-Subklasse wirft
   * hier, nachdem Anhangzeilen und Dateien dieser Transaktion geschrieben sind, und prueft, dass
   * danach weder Kommentar noch Anhaenge noch Dateien bleiben. Im Produktivbetrieb ein No-Op; nur
   * Test-Subklassen ueberschreiben diesen Haken.
   *
   * @param commentId interne ID des neuen Kommentars
   */
  protected void onAfterAttachmentsStoredForTest(final Long commentId) {
    // No-Op im Produktivbetrieb.
  }
}
