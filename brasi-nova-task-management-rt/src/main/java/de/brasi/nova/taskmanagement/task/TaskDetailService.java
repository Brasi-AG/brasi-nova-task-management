package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskHistoryEntry;
import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.task.model.HistoryModel;
import de.brasi.nova.taskmanagement.task.model.TaskDetailModel;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
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
  private final DictionaryCodeResolver resolver;
  private final AuditTimeConverter auditTimeConverter;

  /** Konstruktor-Injektion. */
  public TaskDetailService(
      final BaseTaskRepository taskRepository,
      final TaskHistoryEntryQueryRepository historyRepository,
      final DictionaryCodeResolver resolver,
      final AuditTimeConverter auditTimeConverter) {
    this.taskRepository = taskRepository;
    this.historyRepository = historyRepository;
    this.resolver = resolver;
    this.auditTimeConverter = auditTimeConverter;
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
   * @return aufgebautes {@link TaskDetailModel}
   * @throws EntityNotFoundException wenn nicht gefunden oder fremdem Mandanten zugehoerig (HTTP 404)
   */
  public TaskDetailModel loadDetail(final UUID taskExternalId, final long tenantId) {
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
        history);
  }

  /**
   * Setzt READ_AT fuer den angegebenen Benutzer fuer die Aufgabe mit der externen UUID.
   *
   * <p>Wenn die Aufgabe nicht gefunden wird, wird der Aufruf ignoriert.
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
    userStateService.markRead(task.getId(), userRef);
  }
}
