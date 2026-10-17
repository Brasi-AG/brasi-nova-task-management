package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskTag;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;

/**
 * Handgeschriebene Erweiterung des generierten {@link BaseTaskTagRepository}
 * mit Tag-spezifischen Abfragen.
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom
 * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 * Da Tags global sind (TENANT_ID=0), muss der Aufrufer den globalen Scope per
 * {@code TenantContextHolder.runWithTenant(TenantInfo.global(), ...)} setzen.
 */
public interface TaskTagRepository extends BaseTaskTagRepository {

  /**
   * Liefert alle aktiven, gueltigen Tags des globalen Katalogs.
   *
   * <p>Filtert auf {@code ENTITY_STATUS='ACTIVE'}, laufende Gueltigkeit und den gebundenen
   * {@code :tenantId} (= 0 im globalen Scope).
   *
   * @return Liste aller aktiven Tags, nach BEZEICHNUNG sortiert
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_TAG "
              + "WHERE /* NOVA:DICTIONARY_VISIBILITY */ "
              + "ENTITY_STATUS = 'ACTIVE' "
              + "AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE()) "
              + "AND TENANT_ID = :tenantId "
              + "ORDER BY BEZEICHNUNG",
      mssql =
          "SELECT * FROM [dbo].[TASK_TAG] "
              + "WHERE /* NOVA:DICTIONARY_VISIBILITY */ "
              + "ENTITY_STATUS = 'ACTIVE' "
              + "AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE()) "
              + "AND TENANT_ID = :tenantId "
              + "ORDER BY BEZEICHNUNG")
  List<TaskTag> findAllActive();
}
