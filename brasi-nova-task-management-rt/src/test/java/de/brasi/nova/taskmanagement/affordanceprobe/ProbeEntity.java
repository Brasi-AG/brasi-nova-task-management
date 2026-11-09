package de.brasi.nova.taskmanagement.affordanceprobe;

import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.model.NovaEntity;
import java.util.UUID;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>Minimale {@link NovaEntity}-Subklasse, ausschliesslich als {@code entityClass}-Marker fuer
 * {@code @EnableNovaHateoas} und als via {@code BaseModel.setEntity(..)} gesetzte Entity in den
 * Probe-Controllern. Traegt keine fachliche Bedeutung; dient nur dem empirischen Nachweis der
 * KSF-2.3.24-Affordance-Mechanik.
 */
public class ProbeEntity extends NovaEntity<ProbeEntity> {

  public ProbeEntity() {
    super();
  }

  public ProbeEntity(final Long id, final UUID uuid) {
    super(id, EntityState.ACTIVE, uuid, "probe-seed", null, null);
  }
}
