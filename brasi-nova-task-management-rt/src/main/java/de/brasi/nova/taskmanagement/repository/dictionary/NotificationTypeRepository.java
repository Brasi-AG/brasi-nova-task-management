package de.brasi.nova.taskmanagement.repository.dictionary;

import de.brasi.nova.taskmanagement.entity.dictionary.NotificationType;
import de.brasi.nova.taskmanagement.repository.dictionary.BaseNotificationTypeRepository;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;

/**
 * Hand-geschriebenes Repository fuer {@link NotificationType} (DIC_NOTIFICATION_TYPE).
 * Ergaenzt {@link BaseNotificationTypeRepository} um einen CODE-basierten Finder fuer den
 * {@link de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver}.
 * {@code :tenantId} wird automatisch vom Framework aus dem {@code TenantContextHolder} gebunden
 * (kein tenantId-Parameter, da sonst KSF-{@code NovaQueryTenantValidator} den Start abbricht).
 */
public interface NotificationTypeRepository extends BaseNotificationTypeRepository {

  /**
   * Sucht DIC_NOTIFICATION_TYPE-Eintraege anhand ihres technischen CODE-Schluessels im aktuellen
   * Mandantenkontext (global = TENANT_ID=0 via {@code TenantContextHolder.runWithTenant(global)}).
   * Gibt eine Liste zurueck, damit der Resolver 0/1/>1 selbst fail-loud behandeln kann.
   *
   * @param code technischer CODE-Wert (z.B. "ASSIGN", "REPLY", "DUE")
   * @return Liste der gefundenen Eintraege (erwartet: genau 1 fuer System-Dictionary)
   */
  @NovaQuery(
      value =
          "SELECT * FROM DIC_NOTIFICATION_TYPE"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND GUELTIG_VON <= CAST(GETDATE() AS DATE)"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE))"
              + " AND TENANT_ID = :tenantId"
              + " AND CODE = :code",
      mssql =
          "SELECT * FROM [dbo].[DIC_NOTIFICATION_TYPE]"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND GUELTIG_VON <= CAST(GETDATE() AS DATE)"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE))"
              + " AND TENANT_ID = :tenantId"
              + " AND CODE = :code")
  List<NotificationType> findByCode(String code);

  /**
   * Liefert alle aktiven, aktuell gueltigen DIC_NOTIFICATION_TYPE-Eintraege, geordnet nach CODE.
   * Laueft im globalen Scope (TENANT_ID=0).
   *
   * <p>Eigener Methodenname (nicht {@code findAllActive}) notwendig, da {@code findAllActive}
   * im Basis-Interface als Criteria-Query vorbelegt ist und {@code @NovaQuery} dort nicht greift
   * (KSF bevorzugt die Criteria-Implementierung bei gleichem Methodennamen).
   * Filtert zusaetzlich auf vollstaendiges Gueltigkeitspraedikat (GUELTIG_VON <= heute,
   * GUELTIG_BIS IS NULL OR >= heute, Datumsvergleich per CAST).
   *
   * @return Liste aller aktiven und aktuell gueltigen Benachrichtigungs-Typ-Eintraege
   */
  @NovaQuery(
      value =
          "SELECT * FROM DIC_NOTIFICATION_TYPE"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND GUELTIG_VON <= CAST(GETDATE() AS DATE)"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE))"
              + " AND TENANT_ID = :tenantId"
              + " ORDER BY CODE",
      mssql =
          "SELECT * FROM [dbo].[DIC_NOTIFICATION_TYPE]"
              + " WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE'"
              + " AND GUELTIG_VON <= CAST(GETDATE() AS DATE)"
              + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE))"
              + " AND TENANT_ID = :tenantId"
              + " ORDER BY CODE")
  List<NotificationType> findAllValidAndActive();
}
