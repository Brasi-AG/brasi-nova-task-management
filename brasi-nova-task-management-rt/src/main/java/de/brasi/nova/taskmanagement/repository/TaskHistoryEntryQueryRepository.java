package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskHistoryEntry;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;
import org.springframework.data.repository.query.Param;

/**
 * Erweiterung des generierten {@link BaseTaskHistoryEntryRepository} mit taskscoped Abfragen.
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom
 * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 */
public interface TaskHistoryEntryQueryRepository extends BaseTaskHistoryEntryRepository {

  /**
   * Liefert alle Historieneintraege einer Aufgabe, absteigend sortiert nach Erstellungsdatum.
   *
   * @param taskId interne Aufgaben-ID
   * @return Liste der Historieneintraege (neueste zuerst)
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_HISTORY_ENTRY "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "ORDER BY ANGELEGT_AM DESC",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_HISTORY_ENTRY] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "ORDER BY ANGELEGT_AM DESC")
  List<TaskHistoryEntry> findByTaskIdOrderByCreatedAtDesc(@Param("taskId") Long taskId);
}
