package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.entity.TaskAttachmentLimit;
import de.brasi.nova.taskmanagement.entity.enums.AttachmentTariff;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentLimitQueryRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ermittelt die wirksame Dateigroessen-Grenze eines Mandanten fuer Anhaenge (Intake #152).
 *
 * <p>Reihenfolge der Quellen: erst die Konfigurationsliste {@code enterprise-tenant-ids} (ergibt
 * den Tarif ENTERPRISE mit der Obergrenze der Spanne), dann die aktive Zeile in {@code
 * TASK_ANHANG_GRENZE}, sonst der Tarif STANDARD. Ein Tabellenwert {@code MAX_DATEI_BYTES} wird in
 * die Spanne des Tarifs geklemmt; fehlt er, gilt die Obergrenze der Spanne. Danach deckelt die
 * harte Obergrenze jeden Tarif. Alle Werte stammen aus {@link AttachmentProperties}.
 */
@Service
public class AttachmentLimitPolicy {

  private static final Logger log = LoggerFactory.getLogger(AttachmentLimitPolicy.class);

  private final AttachmentProperties properties;
  private final TaskAttachmentLimitQueryRepository repository;

  /**
   * Konstruktor-Injektion aller Abhaengigkeiten.
   *
   * @param properties Anhang-Konfiguration (Spannen, harte Obergrenze, ENTERPRISE-Liste)
   * @param repository Lesezugriff auf die Tarif-Grenze des Kontext-Mandanten
   */
  public AttachmentLimitPolicy(
      final AttachmentProperties properties, final TaskAttachmentLimitQueryRepository repository) {
    this.properties = properties;
    this.repository = repository;
  }

  /**
   * Liefert die wirksame Grenze je Datei fuer den Mandanten.
   *
   * <p>Die Tabelle wird fuer den Mandanten des Kontexts gelesen ({@code :tenantId} bindet KSF aus
   * dem {@code TenantContextHolder}); der Parameter {@code tenantId} muss deshalb der
   * Kontext-Mandant sein. Er dient dem Abgleich mit der ENTERPRISE-Liste und dem Protokoll. Fuer
   * einen in der Liste genannten Mandanten entfaellt der Tabellenzugriff.
   *
   * @param tenantId Mandanten-ID des Kontexts
   * @return Tarif und hoechste erlaubte Dateigroesse in Bytes
   */
  public AttachmentFileLimit limitFor(final long tenantId) {
    if (properties.enterpriseTenantIds().contains(tenantId)) {
      return resolve(tenantId, AttachmentTariff.ENTERPRISE, null, "Konfiguration");
    }
    final List<TaskAttachmentLimit> rows = repository.findActiveForTenant();
    final Optional<TaskAttachmentLimit> row =
        rows.stream().filter(candidate -> candidate.getTariff() != null).findFirst();
    if (row.isPresent()) {
      return resolve(tenantId, row.get().getTariff(), row.get().getMaxFileBytes(), "Tabelle");
    }
    return resolve(tenantId, AttachmentTariff.STANDARD, null, "Vorgabe");
  }

  /**
   * Bildet die Grenze aus Tarifspanne, optionalem Tabellenwert und harter Obergrenze.
   *
   * @param tenantId Mandanten-ID (nur fuer das Protokoll)
   * @param tariff wirksamer Tarif
   * @param requestedBytes eigene Obergrenze aus der Tabelle oder {@code null}
   * @param source Herkunft des Tarifs (nur fuer das Protokoll)
   */
  private AttachmentFileLimit resolve(
      final long tenantId,
      final AttachmentTariff tariff,
      final Long requestedBytes,
      final String source) {
    final AttachmentProperties.Limits limits = properties.limits();
    final long spanMin =
        switch (tariff) {
          case STANDARD -> limits.standardMax().toBytes();
          case EXPERT -> limits.expertMin().toBytes();
          case ENTERPRISE -> limits.enterpriseMin().toBytes();
        };
    final long spanMax =
        switch (tariff) {
          case STANDARD -> limits.standardMax().toBytes();
          case EXPERT -> limits.expertMax().toBytes();
          case ENTERPRISE -> limits.enterpriseMax().toBytes();
        };
    final long inSpan =
        requestedBytes == null ? spanMax : Math.min(Math.max(requestedBytes, spanMin), spanMax);
    final long effective = Math.min(inSpan, limits.hardMax().toBytes());
    log.debug(
        "Anhang-Grenze fuer Mandant {}: Tarif {}, Grenze {} Bytes (Quelle: {}).",
        tenantId,
        tariff,
        effective,
        source);
    return new AttachmentFileLimit(tariff, effective);
  }
}
