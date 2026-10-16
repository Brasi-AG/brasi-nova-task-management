package de.brasi.nova.taskmanagement.refentity.query;

import io.keel.framework.api.query.path.StringProperty;
import io.keel.framework.api.query.path.IntegerProperty;
import io.keel.framework.api.query.path.LongProperty;
import io.keel.framework.persistence.query.path.NovaPropertyFactory;
import io.keel.framework.persistence.query.path.QNovaValidityEntity;

/**
 * Statisches Metamodell fuer die Referenz-Entity {@link de.brasi.nova.taskmanagement.refentity.Firm}.
 * Wird vom KSF-Framework fuer Query-Pfad-Aufloesung benoetigt.
 */
public final class QFirm extends QNovaValidityEntity {

  /** Firmenname. */
  public final StringProperty name = NovaPropertyFactory.createString("name");

  /** Kurzname. */
  public final StringProperty shortName = NovaPropertyFactory.createString("shortName");

  /** Kuerzel. */
  public final StringProperty abbreviation = NovaPropertyFactory.createString("abbreviation");

  /** Diamant-Mandantennummer. */
  public final IntegerProperty diamandMandantNr =
      NovaPropertyFactory.createInteger("diamandMandantNr");

  /** Verarbeitende Firma-ID. */
  public final LongProperty processingFirmId = NovaPropertyFactory.createLong("processingFirmId");

  /** Effektive verarbeitende Firma-ID. */
  public final LongProperty effectiveProcessorFirmIdRef =
      NovaPropertyFactory.createLong("effectiveProcessorFirmIdRef");

  /** Singleton-Instanz. */
  public static final QFirm firm = new QFirm();
}
