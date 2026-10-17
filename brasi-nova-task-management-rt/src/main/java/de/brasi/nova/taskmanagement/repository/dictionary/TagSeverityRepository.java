package de.brasi.nova.taskmanagement.repository.dictionary;

import de.brasi.nova.taskmanagement.entity.dictionary.TagSeverity;
import de.brasi.nova.taskmanagement.repository.dictionary.BaseTagSeverityRepository;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;

/**
 * Hand-geschriebenes Repository fuer {@link TagSeverity} (DIC_TAG_SEVERITY).
 * Ergaenzt {@link BaseTagSeverityRepository} um einen CODE-basierten Finder fuer den
 * {@link de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver}.
 * {@code :tenantId} wird automatisch vom Framework aus dem {@code TenantContextHolder} gebunden
 * (kein tenantId-Parameter, da sonst KSF-{@code NovaQueryTenantValidator} den Start abbricht).
 */
public interface TagSeverityRepository extends BaseTagSeverityRepository {

  /**
   * Sucht DIC_TAG_SEVERITY-Eintraege anhand ihres technischen CODE-Schluessels im aktuellen
   * Mandantenkontext (global = TENANT_ID=0 via {@code TenantContextHolder.runWithTenant(global)}).
   * Gibt eine Liste zurueck, damit der Resolver 0/1/>1 selbst fail-loud behandeln kann.
   *
   * @param code technischer CODE-Wert (z.B. "PRIMARY", "SUCCESS", "WARN", "DANGER", "SECONDARY")
   * @return Liste der gefundenen Eintraege (erwartet: genau 1 fuer System-Dictionary)
   */
  @NovaQuery(
      value =
          "SELECT * FROM DIC_TAG_SEVERITY"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE())"
              + " AND TENANT_ID = :tenantId"
              + " AND CODE = :code",
      mssql =
          "SELECT * FROM [dbo].[DIC_TAG_SEVERITY]"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE())"
              + " AND TENANT_ID = :tenantId"
              + " AND CODE = :code")
  List<TagSeverity> findByCode(String code);

  /**
   * Liefert alle aktiven DIC_TAG_SEVERITY-Eintraege, geordnet nach CODE.
   * Laueft im globalen Scope (TENANT_ID=0).
   *
   * @return Liste aller aktiven Tag-Schweregrad-Eintraege
   */
  @NovaQuery(
      value =
          "SELECT * FROM DIC_TAG_SEVERITY"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE())"
              + " AND TENANT_ID = :tenantId"
              + " ORDER BY CODE",
      mssql =
          "SELECT * FROM [dbo].[DIC_TAG_SEVERITY]"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE())"
              + " AND TENANT_ID = :tenantId"
              + " ORDER BY CODE")
  List<TagSeverity> findAllActive();
}
