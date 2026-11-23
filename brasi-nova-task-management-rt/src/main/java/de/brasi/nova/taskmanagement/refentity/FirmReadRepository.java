package de.brasi.nova.taskmanagement.refentity;

import io.keel.framework.api.annotation.db.GlobalQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.NovaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * Read-only-Repository fuer {@link Firm} (PROD_FIRMA). Pflicht fuer den {@code
 * CachingIdMappingService}: ohne registriertes {@code NovaRepository<Firm>} wirft er beim Start
 * "Kein NovaRepository registriert". Keine Schreibpfade.
 */
public interface FirmReadRepository extends NovaRepository<Firm> {

  /**
   * Interne Mandanten-ID einer aktiven Firma zu ihrer UUID ({@code PROD_FIRMA.EXTERNALID}). Nur
   * unter {@code runAsSystem}: der Abteilungs-Consumer kennt aus dem Ereignis nur die UUID und hat
   * noch keinen Mandanten-Kontext (Intake #190). Eigener Name statt einer Ueberladung von {@code
   * NovaRepository#findIdByUuid(String)}.
   *
   * @param uuid Mandanten-UUID aus dem Ereignis
   * @return die ID, leer, wenn die Firma unbekannt oder nicht aktiv ist
   */
  @GlobalQuery(
      reason =
          "Abteilungs-Consumer loest die Mandanten-UUID eines Ereignisses auf, bevor ein"
              + " Mandanten-Kontext besteht; liefert nur die ID, danach runWithTenant.")
  @NovaQuery(
      value = "SELECT ID FROM PROD_FIRMA WHERE EXTERNALID = :uuid AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "SELECT ID FROM [dbo].[PROD_FIRMA] WHERE EXTERNALID = :uuid"
              + " AND ENTITY_STATUS = 'ACTIVE'")
  Optional<Long> findTenantIdByUuid(@Param("uuid") UUID uuid);
}
