package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskHistoryEntry;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.inbox.InboxEnrichmentAssembler;
import de.brasi.nova.taskmanagement.inbox.TaskEnrichment;
import de.brasi.nova.taskmanagement.inbox.model.ExitModel;
import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.HandoverTargetRow;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.task.model.HistoryModel;
import de.brasi.nova.taskmanagement.task.model.TaskDetailModel;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Laedt und montiert das {@link TaskDetailModel} fuer den GET-Endpunkt.
 *
 * <p>Alle Reads laufen ohne eigene Transaktion (KSF-Default: REQUIRED) in der des Aufrufers.
 * Der Aufrufer (Controller) traegt die Verantwortung fuer den Mandanten-Kontext.
 */
@Service
public class TaskDetailService {

  private final BaseTaskRepository taskRepository;
  private final TaskHistoryEntryQueryRepository historyRepository;
  private final TaskParticipantQueryRepository participantQueryRepository;
  private final TaskTagAssignmentQueryRepository tagAssignmentQueryRepository;
  private final TaskUserStateQueryRepository userStateQueryRepository;
  private final TaskHandoverQueryRepository handoverQueryRepository;
  private final InboxEnrichmentAssembler enrichmentAssembler;
  private final DictionaryCodeResolver resolver;
  private final AuditTimeConverter auditTimeConverter;
  private final TaskMembershipGuard membershipGuard;

  /** Konstruktor-Injektion. */
  public TaskDetailService(
      final BaseTaskRepository taskRepository,
      final TaskHistoryEntryQueryRepository historyRepository,
      final TaskParticipantQueryRepository participantQueryRepository,
      final TaskTagAssignmentQueryRepository tagAssignmentQueryRepository,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final TaskHandoverQueryRepository handoverQueryRepository,
      final InboxEnrichmentAssembler enrichmentAssembler,
      final DictionaryCodeResolver resolver,
      final AuditTimeConverter auditTimeConverter,
      final TaskMembershipGuard membershipGuard) {
    this.taskRepository = taskRepository;
    this.historyRepository = historyRepository;
    this.participantQueryRepository = participantQueryRepository;
    this.tagAssignmentQueryRepository = tagAssignmentQueryRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.handoverQueryRepository = handoverQueryRepository;
    this.enrichmentAssembler = enrichmentAssembler;
    this.resolver = resolver;
    this.auditTimeConverter = auditTimeConverter;
    this.membershipGuard = membershipGuard;
  }

  /**
   * Laedt das Detail einer Aufgabe anhand ihrer externen UUID.
   *
   * <p>Nicht gefunden UND fremder Mandant werden mit identischer, neutraler
   * {@link EntityNotFoundException} (HTTP 404) beantwortet, um keine Existenz-Information
   * preiszugeben (arch-v2 §8; konsistent zu {@code TaskCommandService.requireTask}). Die Meldung
   * enthaelt bewusst KEINE UUID und unterscheidet die beiden Faelle NICHT.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param tenantId       aktueller Mandanten-Schluessel (Plausibilitaetspruefung)
   * @param userRef        UUID des Anfragers (Actor) fuer persoenliche Marker + Austritt
   * @return aufgebautes {@link TaskDetailModel}
   * @throws EntityNotFoundException wenn nicht gefunden oder fremdem Mandanten zugehoerig (HTTP 404)
   */
  public TaskDetailModel loadDetail(
      final UUID taskExternalId, final long tenantId, final UUID userRef) {
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

    // Standard-Selfservice (Slice 2): objekt-level Membership-Guard VOR jeder Anreicherung. READ-
    // Member (aktive PERSON-Zeile im Task-Mandanten; ausgetretene eingeschlossen) darf das Detail
    // (inkl. Archiv) lesen; Non-Member -> neutrale 404 (identisch zu fremd/nicht-gefunden). Der
    // Guard laeuft NACH Tenant-/ACTIVE-Check und VOR dem History-/Anreicherungs-Read.
    membershipGuard.requireReadMember(task.getId(), userRef, tenantId);

    // Dictionary-IDs auf CODEs aufloesen
    final String statusCode = resolver.resolveTaskStatusCode(task.getTaskStatusId());
    final String priorityCode = resolver.resolveTaskPriorityCode(task.getPriorityId());
    final String directionCode = resolver.resolveTaskDirectionCode(task.getDirectionId());

    // Historieneintraege laden
    final List<TaskHistoryEntry> entries =
        historyRepository.findByTaskIdOrderByCreatedAtDesc(task.getId());
    final List<HistoryModel> history =
        entries.stream()
            .map(
                e ->
                    new HistoryModel(
                        e.getUuid(),
                        e.getActorRef(),
                        e.getText(),
                        e.getChangeDetail(),
                        auditTimeConverter.toInstant(e.getCreatedAt())))
            .toList();

    // Aktuelle Assignee-GUIDs (faithful: alle aktiven Assignee-Assignments, stabil sortiert) -
    // Read-before-edit fuer PUT /assignees. Resubmit bestehender Assignees ist clean (PUT validiert
    // nur toAdd), daher kein User-ACTIVE-Filter noetig.
    final List<UUID> assignees =
        participantQueryRepository.findActiveAssigneesByTaskId(task.getId()).stream()
            .map(TaskParticipant::getParticipantRef)
            .sorted(Comparator.comparing(UUID::toString))
            .toList();

    // Aktuelle Tag-GUIDs (faithful): EINE JOIN-Projektion liefert die EXTERNALIDs ALLER aktiven
    // Tag-Zuordnungen - unabhaengig von Katalog-Status/Visibility des Tags -, damit kein zugeordneter
    // Tag im Detail verloren geht (kein stiller Verlust beim Read-modify-write). Die Konfliktpruefung
    // (inaktiv/abgelaufen) bleibt beim PATCH (findActiveByUuid -> 400). Nur EIN gebundener :taskId-
    // Parameter (plus auto-:tenantId) -> kein SQL-Parameterlimit-Risiko bei vielen Zuordnungen.
    final List<UUID> tagIds =
        tagAssignmentQueryRepository.findActiveTagUuidsByTaskId(task.getId()).stream()
            .map(UUID::fromString)
            .sorted(Comparator.comparing(UUID::toString))
            .toList();

    // Actor-/Personen-Anreicherung (BE-3): der BE-1-Assembler wird fuer DIESE eine Aufgabe
    // wiederverwendet. Der Detail-Read ist LIVE (kein asOf-Wasserzeichen): asOf=now() liefert den
    // AKTUELLEN Actor-Austritt. Tags sind bereits oben (faithful) geladen -> leere Tag-Rows an den
    // Assembler. Nicht-Teilnehmer-Actor -> EMPTY-Anreicherung (Marker false, exit null), waehrend
    // createdById/participantIds den Ersteller/Teilnehmer der Aufgabe spiegeln.
    final List<Long> taskIds = List.of(task.getId());
    final List<TaskParticipant> participantRows =
        participantQueryRepository.findActiveByTaskIds(taskIds);
    final List<TaskUserState> actorStateRows =
        userStateQueryRepository.findByTaskIdsAndUserRef(taskIds, userRef);
    final LocalDateTime asOf = LocalDateTime.now(auditTimeConverter.zone());
    // BE-7: Uebergabeziele des sichtbaren Austritts-Handover-Ereignisses des Anfragers. LIVE-Read
    // (asOf=now, KEIN Generation-Bracket - PA7): das juengste SHOULD_STAY=false-Ereignis <=now.
    final List<HandoverTargetRow> handoverRows =
        handoverQueryRepository.findVisibleHandoverTargets(taskIds, userRef, asOf);
    final TaskEnrichment enrichment =
        enrichmentAssembler
            .assemble(
                taskIds, participantRows, List.of(), actorStateRows, handoverRows, userRef, asOf)
            .getOrDefault(task.getId(), TaskEnrichment.EMPTY);
    final ExitModel exit =
        enrichment.exitKind() == null
            ? null
            : new ExitModel(enrichment.exitKind(), enrichment.handedOverToIds());

    return new TaskDetailModel(
        task.getUuid(),
        task.getTitle(),
        task.getDescription(),
        statusCode,
        priorityCode,
        directionCode,
        task.getStartDate(),
        task.getDueDate(),
        auditTimeConverter.toInstant(task.getLastActivityAt()),
        task.getCommentCount(),
        history,
        assignees,
        tagIds,
        enrichment.createdById(),
        enrichment.participantIds(),
        enrichment.isPinned(),
        enrichment.isFlagged(),
        enrichment.isMuted(),
        exit);
  }

  /**
   * Setzt READ_AT fuer den angegebenen Benutzer fuer die Aufgabe mit der externen UUID.
   *
   * <p>Wenn die Aufgabe nicht gefunden wird, wird der Aufruf ignoriert.
   *
   * <p>Standard-Selfservice (Slice 2): READ_AT wird nur fuer READ-Member gesetzt. Fuer einen
   * Non-Member wird KEIN READ_AT geschrieben (best-effort, kein Fehler): der Guard ist reiner
   * EXISTS-Read; schlaegt er fehl, wird der Aufruf still ignoriert (konsistent zum Not-Found-/
   * Fremd-Tenant-Pfad). Im reaelen Request-Fluss hat {@code loadDetail} fuer einen Non-Member bereits
   * 404 geworfen, sodass diese Methode dann gar nicht mehr erreicht wird (Defense-in-Depth).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param tenantId       aktueller Mandant
   * @param userRef        UUID des Benutzers
   * @param userStateService der {@link UserStateService}
   */
  public void markReadForUser(
      final UUID taskExternalId,
      final long tenantId,
      final UUID userRef,
      final UserStateService userStateService) {
    final Optional<Task> opt = taskRepository.findByUuid(taskExternalId.toString());
    if (opt.isEmpty()) {
      return;
    }
    final Task task = opt.get();
    if (!Objects.equals(tenantId, task.getTenantId())) {
      return;
    }
    // Kein READ_AT fuer Non-Member (best-effort): nur READ-Member erhalten einen READ_AT-Write. Der
    // Guard wirft bei Nicht-Member EntityNotFoundException (404); hier wird der Aufruf still
    // ignoriert (wie der Not-Found-/Fremd-Tenant-Pfad oben), nicht als Fehler propagiert.
    try {
      membershipGuard.requireReadMember(task.getId(), userRef, tenantId);
    } catch (final EntityNotFoundException ignored) {
      return;
    }
    userStateService.markRead(task.getId(), userRef);
  }
}
