package de.brasi.nova.taskmanagement.refentity;

import de.brasi.nova.taskmanagement.refentity.query.QFirm;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaFkScope;
import io.keel.framework.api.annotation.db.NovaQClass;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaValidityEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Read-only-Mapping der gemeinsamen Referenztabelle PROD_FIRMA (Mandant). Wird ausschliesslich als
 * {@code ksf.web-security.tenant.entity-class} benoetigt (UUID-&gt;ID-Aufloesung im Filter) und
 * darf nicht geschrieben werden. Kein Scaffold.
 */
@NovaTable(name = "PROD_FIRMA")
@NovaQClass(QFirm.class)
@Getter
@Setter
@NoArgsConstructor
public class Firm extends NovaValidityEntity<Firm> {

  private static final long serialVersionUID = 1L;

  /** Firmenname (PROD_FIRMA.NAME). */
  @NovaColumn(name = "NAME")
  private String name;

  /** Kurzname (PROD_FIRMA.KURZNAME). */
  @NovaColumn(name = "KURZNAME")
  private String shortName;

  /** Kuerzel (PROD_FIRMA.KUERZEL). */
  @NovaColumn(name = "KUERZEL")
  private String abbreviation;

  /** Diamant-Mandantennummer (PROD_FIRMA.DIAMAND_MANDANT_NR). */
  @NovaColumn(name = "DIAMAND_MANDANT_NR")
  private Integer diamandMandantNr;

  /**
   * Verarbeitende Firma-ID (PROD_FIRMA.PROCESSING_FIRM_ID); global-scoped (systemweiter FK auf
   * PROD_FIRMA).
   */
  @NovaColumn(name = "PROCESSING_FIRM_ID", nullable = true)
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long processingFirmId;

  /**
   * Effektive verarbeitende Firma-ID (PROD_FIRMA.EFFECTIVE_PROCESSOR_FIRM_ID_REF);
   * global-scoped.
   */
  @NovaColumn(name = "EFFECTIVE_PROCESSOR_FIRM_ID_REF", nullable = true)
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long effectiveProcessorFirmIdRef;
}
