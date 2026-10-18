package de.brasi.nova.taskmanagement.repository.dictionary;

import de.brasi.nova.taskmanagement.entity.dictionary.TaskDirection;
import de.brasi.nova.taskmanagement.repository.dictionary.BaseTaskDirectionRepository;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;

/**
 * Hand-geschriebenes Repository fuer {@link TaskDirection} (DIC_TASK_DIRECTION).
 * Ergaenzt {@link BaseTaskDirectionRepository} um einen CODE-basierten Finder fuer den
 * {@link de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver}.
 * {@code :tenantId} wird automatisch vom Framework aus dem {@code TenantContextHolder} gebunden
 * (kein tenantId-Parameter, da sonst KSF-{@code NovaQueryTenantValidator} den Start abbricht).
 */
public interface TaskDirectionRepository extends BaseTaskDirectionRepository {

  /**
   * Sucht DIC_TASK_DIRECTION-Eintraege anhand ihres technischen CODE-Schluessels im aktuellen
   * Mandantenkontext (global = TENANT_ID=0 via {@code TenantContextHolder.runWithTenant(global)}).
   * Gibt eine Liste zurueck, damit der Resolver 0/1/>1 selbst fail-loud behandeln kann.
   *
   * @param code technischer CODE-Wert (z.B. "BRASI_CUSTOMER", "CUSTOMER_INTERNAL", "INTER_COMPANY")
   * @return Liste der gefundenen Eintraege (erwartet: genau 1 fuer System-Dictionary)
   */
  @NovaQuery(
      value =
          "SELECT * FROM DIC_TASK_DIRECTION"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND GUELTIG_VON <= CAST(GETDATE() AS DATE)"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE))"
              + " AND TENANT_ID = :tenantId"
              + " AND CODE = :code",
      mssql =
          "SELECT * FROM [dbo].[DIC_TASK_DIRECTION]"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND GUELTIG_VON <= CAST(GETDATE() AS DATE)"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE))"
              + " AND TENANT_ID = :tenantId"
              + " AND CODE = :code")
  List<TaskDirection> findByCode(String code);

  /**
   * Liefert alle aktiven, aktuell gueltigen DIC_TASK_DIRECTION-Eintraege, geordnet nach CODE.
   * Laueft im globalen Scope (TENANT_ID=0).
   *
   * <p>Eigener Methodenname (nicht {@code findAllActive}) notwendig, da {@code findAllActive}
   * im Basis-Interface als Criteria-Query vorbelegt ist und {@code @NovaQuery} dort nicht greift
   * (KSF bevorzugt die Criteria-Implementierung bei gleichem Methodennamen).
   * Filtert zusaetzlich auf vollstaendiges Gueltigkeitspraedikat (GUELTIG_VON <= heute,
   * GUELTIG_BIS IS NULL OR >= heute, Datumsvergleich per CAST).
   *
   * @return Liste aller aktiven und aktuell gueltigen Task-Richtungs-Eintraege
   */
  @NovaQuery(
      value =
          "SELECT * FROM DIC_TASK_DIRECTION"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND GUELTIG_VON <= CAST(GETDATE() AS DATE)"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE))"
              + " AND TENANT_ID = :tenantId"
              + " ORDER BY CODE",
      mssql =
          "SELECT * FROM [dbo].[DIC_TASK_DIRECTION]"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND GUELTIG_VON <= CAST(GETDATE() AS DATE)"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE))"
              + " AND TENANT_ID = :tenantId"
              + " ORDER BY CODE")
  List<TaskDirection> findAllValidAndActive();
}
