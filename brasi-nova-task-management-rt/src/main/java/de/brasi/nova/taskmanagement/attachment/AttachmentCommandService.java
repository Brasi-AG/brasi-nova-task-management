package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaktionale Kommando-Operationen der Anhaenge an einer Aufgabe (Intake #152).
 *
 * <p>Jede Mutation laeuft in einer eigenen Transaktion und folgt der Sperrfolge der Karte {@code
 * participants-actions}: alle Reads (Aufgabe, Mitgliedschaft per {@code requireWriteMember}, Zahl
 * der Anhaenge, Leser) vor der ersten Schreibung. Danach schreibt die Mutation die Aufgabe fort
 * ({@code LAST_ACTIVITY_AT}, {@code VERSION} per TASK-save; ein Versionskonflikt ergibt 409 und
 * rollt alles zurueck), schreibt die Historie und bumpt die Inbox-Generation aller Leser und des
 * Akteurs (Karte {@code inbox-snapshot}); das Signal folgt nach dem Commit. Eine eigene
 * Benachrichtigung gibt es nicht.
 *
 * <p>Akteur und Mandant kommen ausschliesslich aus dem Security-Context ({@link UserAccessor}),
 * nie aus Client-Eingaben.
 */
@Service
public class AttachmentCommandService {

  private static final Logger log = LoggerFactory.getLogger(AttachmentCommandService.class);

  /** Historientext je hochgeladener Datei (Detail = Dateiname). */
  static final String HISTORY_ATTACHED = "Datei angehaengt";

  private final UserAccessor userAccessor;
  private final AttachmentTaskAccess taskAccess;
  private final TaskAttachmentQueryRepository attachmentRepository;
  private final TaskUserStateQueryRepository userStateQueryRepository;
  private final TaskParticipantQueryRepository participantQueryRepository;
  private final AttachmentWriter writer;
  private final BaseTaskRepository taskRepository;
  private final TaskHistoryService historyService;
  private final InboxGenerationService inboxGenerationService;
  private final InboxSignalService inboxSignalService;
  private final AttachmentModelMapper modelMapper;
  private final AttachmentProperties properties;

  /**
   * Konstruktor-Injektion aller Abhaengigkeiten. Oeffentlich, weil Test-Unterklassen den Seam
   * {@link #onAfterAttachmentsStoredForTest(Long)} ueberschreiben.
   *
   * @param userAccessor liefert den angemeldeten Benutzer
   * @param taskAccess laedt die Aufgabe und prueft die Mitgliedschaft
   * @param attachmentRepository Lese-Abfragen der Anhaenge
   * @param userStateQueryRepository aktive persoenliche Zustaende (Leser)
   * @param participantQueryRepository Archiv-Leser (ausgetretene Mitglieder)
   * @param writer legt Zeilen an und schreibt Dateien
   * @param taskRepository Repository der Aufgaben (versionierter save)
   * @param historyService Historie der Aufgabe
   * @param inboxGenerationService Inbox-Generation der Leser
   * @param inboxSignalService Signal {@code snapshot-invalidated} nach dem Commit
   * @param modelMapper Abbildung auf das API-Modell
   * @param properties Anhang-Konfiguration (hoechste Zahl aktiver Anhaenge)
   */
  public AttachmentCommandService(
      final UserAccessor userAccessor,
      final AttachmentTaskAccess taskAccess,
      final TaskAttachmentQueryRepository attachmentRepository,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final TaskParticipantQueryRepository participantQueryRepository,
      final AttachmentWriter writer,
      final BaseTaskRepository taskRepository,
      final TaskHistoryService historyService,
      final InboxGenerationService inboxGenerationService,
      final InboxSignalService inboxSignalService,
      final AttachmentModelMapper modelMapper,
      final AttachmentProperties properties) {
    this.userAccessor = userAccessor;
    this.taskAccess = taskAccess;
    this.attachmentRepository = attachmentRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.participantQueryRepository = participantQueryRepository;
    this.writer = writer;
    this.taskRepository = taskRepository;
    this.historyService = historyService;
    this.inboxGenerationService = inboxGenerationService;
    this.inboxSignalService = inboxSignalService;
    this.modelMapper = modelMapper;
    this.properties = properties;
  }

  /**
   * Speichert vorbereitete Dateien als Anhaenge direkt an der Aufgabe (alles oder nichts).
   *
   * <p>Ablauf:
   *
   * <ol>
   *   <li>Akteur aus dem Security-Context.
   *   <li>Aufgabe laden und WRITE-Mitgliedschaft pruefen ({@link
   *       AttachmentTaskAccess#requireWriteMember}; sonst neutrale 404).
   *   <li>Zahl der aktiven Aufgaben-Anhaenge erneut in der Transaktion pruefen (die Pruefung vor
   *       der Transaktion kann ein paralleler Upload ueberholt haben); mehr als die Hoechstzahl:
   *       400.
   *   <li>Leser lesen (aktive Zustaende und Archiv-Leser) – der letzte Read vor der ersten
   *       Schreibung.
   *   <li>Erste Schreibung: Zeilen und Dateien ({@link AttachmentWriter#store}); bei Rollback
   *       raeumt der Writer die geschriebenen Dateien weg.
   *   <li>Aufgabe fortschreiben ({@code LAST_ACTIVITY_AT}, versionierter TASK-save; Konflikt: 409).
   *   <li>Historie je Datei, dann genau ein sortierter Bump und ein Signal fuer Leser und Akteur.
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param prepared gepruefte Dateien (nur innerhalb des Requests lesbar)
   * @return die neuen Anhaenge in Upload-Reihenfolge; die hochladende Person darf sie entfernen
   * @throws io.keel.framework.api.exception.EntityNotFoundException wenn die Aufgabe nicht sichtbar
   *     oder der Akteur kein aktives Mitglied ist (HTTP 404)
   * @throws EntityValidationException wenn die Aufgabe danach mehr aktive Anhaenge als erlaubt
   *     haette (HTTP 400)
   * @throws io.keel.framework.api.exception.BusinessRuleException bei einem Versionskonflikt der
   *     Aufgabe (HTTP 409)
   */
  @Transactional
  public List<AttachmentModel> storeTaskAttachments(
      final UUID taskExternalId, final List<PreparedAttachment> prepared) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = taskAccess.requireWriteMember(taskExternalId, actor);
    final Long taskId = task.getId();

    final long activeCount = attachmentRepository.countActiveTaskAttachments(taskId);
    requireRoomFor(activeCount, prepared.size());

    // Leser VOR der ersten Schreibung materialisieren (Sperrfolge, Karte participants-actions).
    final Set<UUID> readerRefs = readerRefs(taskId);

    final List<TaskAttachment> stored = writer.store(task, null, actorRef, prepared);

    onAfterAttachmentsStoredForTest(taskId);

    // Versionierter TASK-save: ein paralleler VERSION-Bump laesst diesen Upload mit 409 scheitern;
    // die ganze Transaktion rollt zurueck, der Writer entfernt die geschriebenen Dateien.
    task.setLastActivityAt(LocalDateTime.now());
    taskRepository.save(task);

    for (final TaskAttachment attachment : stored) {
      historyService.record(taskId, tenantId, actorRef, HISTORY_ATTACHED, attachment.getFileName());
    }

    readerRefs.add(actorRef);
    inboxGenerationService.bumpSorted(tenantId, readerRefs);
    inboxSignalService.invalidate(tenantId, readerRefs);

    log.info(
        "AttachmentCommandService.storeTaskAttachments: {} Anhang/Anhaenge an Aufgabe {} (id={})"
            + " von Akteur {} in Mandant {} gespeichert.",
        stored.size(),
        taskExternalId,
        taskId,
        actorRef,
        tenantId);

    return stored.stream()
        .map(attachment -> modelMapper.toModel(attachment, taskExternalId, true))
        .toList();
  }

  /**
   * Test-Seam (Default No-Op): Haken GENAU NACH dem Speichern der Zeilen und Dateien und VOR dem
   * versionierten TASK-save in {@link #storeTaskAttachments}. Erlaubt Integrationstests, die
   * Transaktion an dieser Stelle abzubrechen und das Aufraeumen der Dateien nach dem Rollback zu
   * beweisen. Im Produktivbetrieb ein No-Op; nur Test-Unterklassen ueberschreiben ihn.
   *
   * @param taskId interne Aufgaben-ID
   */
  protected void onAfterAttachmentsStoredForTest(final Long taskId) {
    // No-Op im Produktivbetrieb.
  }

  /** Wirft 400, wenn das Ziel mit den neuen Dateien mehr aktive Anhaenge als erlaubt haette. */
  private void requireRoomFor(final long activeCount, final int newFiles) {
    final int maxPerTarget = properties.maxPerTarget();
    if (activeCount + newFiles > maxPerTarget) {
      throw new EntityValidationException(
          "Höchstens " + maxPerTarget + " aktive Anhänge je Aufgabe bzw. je Kommentar.");
    }
  }

  /** Aktive Leser der Aufgabe (persoenliche Zustaende und Archiv-Leser), ohne Duplikate. */
  private Set<UUID> readerRefs(final Long taskId) {
    final Set<UUID> readerRefs = new LinkedHashSet<>();
    userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
        .map(TaskUserState::getUserRef)
        .forEach(readerRefs::add);
    participantQueryRepository.findArchiveReadersByTaskId(taskId).stream()
        .map(TaskParticipant::getParticipantRef)
        .forEach(readerRefs::add);
    return readerRefs;
  }
}
