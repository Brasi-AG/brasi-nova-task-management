package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.entity.TaskHistoryEntry;
import de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Schreibt {@link TaskHistoryEntry}-Eintraege in der aktiven Transaktion des Aufrufers.
 *
 * <p>Kein eigenes {@code @Transactional}: laueft immer in der Transaktion des aufgerufenen
 * {@link TaskCommandService} (REQUIRED-Propagation ist KSF-Standard fuer {@code save()}).
 */
@Service
public class TaskHistoryService {

  private static final Logger log = LoggerFactory.getLogger(TaskHistoryService.class);

  private final TaskHistoryEntryQueryRepository repository;

  /** Konstruktor-Injektion. */
  public TaskHistoryService(final TaskHistoryEntryQueryRepository repository) {
    this.repository = repository;
  }

  /**
   * Schreibt einen Historieneintrag fuer eine Aufgabe.
   *
   * @param taskId       interne Aufgaben-ID
   * @param tenantId     interner Mandanten-Schluessel
   * @param actorRef     UUID des Akteurs (PROD_BENUTZER.EXTERNALID)
   * @param text         Kurzbeschreibung der Aenderung
   * @param changeDetail optionales Detail der Aenderung
   */
  public void record(
      final Long taskId,
      final Long tenantId,
      final UUID actorRef,
      final String text,
      final String changeDetail) {
    final TaskHistoryEntry entry = new TaskHistoryEntry();
    entry.setTenantId(tenantId);
    entry.setTaskId(taskId);
    entry.setActorRef(actorRef);
    entry.setText(text);
    entry.setChangeDetail(changeDetail);
    repository.save(entry);
    log.debug(
        "TaskHistoryService.record: Eintrag fuer Task {} und Akteur {} gespeichert.",
        taskId,
        actorRef);
  }
}
