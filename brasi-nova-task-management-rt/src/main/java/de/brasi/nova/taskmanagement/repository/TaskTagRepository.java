package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskTag;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.query.Param;

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
   * Liefert alle aktiven, aktuell gueltigen Tags des globalen Katalogs.
   *
   * <p>Eigener Methodenname (nicht {@code findAllActive}) notwendig, da {@code findAllActive}
   * im Basis-Interface {@link io.keel.framework.api.repository.NovaDictionaryEntryRepository}
   * als Criteria-Query vorbelegt ist und {@code @NovaQuery} dort nicht greift (KSF bevorzugt
   * die Criteria-Implementierung bei gleichem Methodennamen).
   *
   * <p>Filtert auf {@code ENTITY_STATUS='ACTIVE'}, vollstaendiges Gueltigkeitspruedikat
   * (F-P2c: {@code GUELTIG_VON <= CAST(GETDATE() AS DATE)} verhindert future-dated Tags;
   * {@code GUELTIG_BIS >= CAST(GETDATE() AS DATE)} nutzt Datumsvergleich statt datetime)
   * und den gebundenen {@code :tenantId} (= 0 im globalen Scope).
   *
   * @return Liste aller aktiven Tags, nach BEZEICHNUNG sortiert
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_TAG "
              + "WHERE /* NOVA:DICTIONARY_VISIBILITY */ "
              + "ENTITY_STATUS = 'ACTIVE' "
              + "AND GUELTIG_VON <= CAST(GETDATE() AS DATE) "
              + "AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE)) "
              + "AND TENANT_ID = :tenantId "
              + "ORDER BY BEZEICHNUNG",
      mssql =
          "SELECT * FROM [dbo].[TASK_TAG] "
              + "WHERE /* NOVA:DICTIONARY_VISIBILITY */ "
              + "ENTITY_STATUS = 'ACTIVE' "
              + "AND GUELTIG_VON <= CAST(GETDATE() AS DATE) "
              + "AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE)) "
              + "AND TENANT_ID = :tenantId "
              + "ORDER BY BEZEICHNUNG")
  List<TaskTag> findAllValidAndActive();

  /**
   * Laedt einen aktiven, aktuell gueltigen Tag anhand seiner externen UUID (F-P1).
   *
   * <p>Gibt {@code empty} zurueck wenn der Tag nicht existiert, inaktiv ist oder ausserhalb
   * der Gueltigkeit liegt. Wird in {@code TaskCommandService.patch} fuer die Tag-UUID-Aufloesung
   * verwendet, damit inaktive/abgelaufene Tags zum HTTP-400-Fehler fuehren (R-PATCH).
   *
   * @param externalId externe UUID als String
   * @return Optional mit dem Tag, oder empty wenn nicht aktiv/gueltig
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_TAG "
              + "WHERE /* NOVA:DICTIONARY_VISIBILITY */ "
              + "ENTITY_STATUS = 'ACTIVE' "
              + "AND GUELTIG_VON <= CAST(GETDATE() AS DATE) "
              + "AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE)) "
              + "AND TENANT_ID = :tenantId "
              + "AND EXTERNALID = :externalId",
      mssql =
          "SELECT * FROM [dbo].[TASK_TAG] "
              + "WHERE /* NOVA:DICTIONARY_VISIBILITY */ "
              + "ENTITY_STATUS = 'ACTIVE' "
              + "AND GUELTIG_VON <= CAST(GETDATE() AS DATE) "
              + "AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE)) "
              + "AND TENANT_ID = :tenantId "
              + "AND EXTERNALID = :externalId")
  Optional<TaskTag> findActiveByUuid(String externalId);

  /**
   * FAITHFUL-Bulk-Aufloesung interner Tag-IDs auf Tags - OHNE ACTIVE-/Gueltigkeitsfilter (im
   * Gegensatz zu {@link #findActiveByUuid}/{@link #findAllValidAndActive}).
   *
   * <p>Dient dem per-Task-Read-Contract ({@code TaskDetailService.loadDetail}): ein noch AKTIV
   * ZUGEORDNETER, aber inzwischen katalog-inaktiver Tag MUSS im Detail sichtbar bleiben, sonst
   * verloere ein Read-modify-write-Client die ungesehene Zuordnung still. Konfliktregel: ein Resubmit
   * eines katalog-inaktiven Tags scheitert dann laut per PATCH ({@code findActiveByUuid} -> 400) statt
   * stillen Verlusts. Deshalb hier bewusst KEIN Status-/Gueltigkeitsfilter - nur das Tenant-/
   * Visibility-Praedikat des globalen Dictionary-Scopes.
   *
   * <p><b>Pflicht des Aufrufers:</b> {@code ids} nie leer uebergeben (MSSQL {@code IN ()} ist ein
   * Syntaxfehler) und {@code TenantContextHolder.runWithTenant(TenantInfo.global(), ...)} setzen. Die
   * Collection wird fuer {@code IN (:ids)} expandiert.
   *
   * @param ids interne Tag-IDs (nicht leer)
   * @return Liste der Tags (beliebige Reihenfolge; Aufrufer sortiert nach UUID)
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_TAG "
              + "WHERE /* NOVA:DICTIONARY_VISIBILITY */ "
              + "TENANT_ID = :tenantId "
              + "AND ID IN (:ids)",
      mssql =
          "SELECT * FROM [dbo].[TASK_TAG] "
              + "WHERE /* NOVA:DICTIONARY_VISIBILITY */ "
              + "TENANT_ID = :tenantId "
              + "AND ID IN (:ids)")
  List<TaskTag> findByIdIn(@Param("ids") Collection<Long> ids);
}
