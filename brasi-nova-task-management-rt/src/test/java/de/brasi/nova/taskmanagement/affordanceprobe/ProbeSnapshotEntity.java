package de.brasi.nova.taskmanagement.affordanceprobe;

import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.model.NovaEntity;
import java.util.UUID;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>Separater {@code entityClass}-Marker fuer den P3-"mit-Owner"-Snapshot-Controller, damit er ein
 * eigener Regel-Owner sein kann, ohne mit {@link ProbeOwnerController} (Owner von {@link
 * ProbeEntity}) zu kollidieren.
 */
public class ProbeSnapshotEntity extends NovaEntity<ProbeSnapshotEntity> {

  public ProbeSnapshotEntity() {
    super();
  }

  public ProbeSnapshotEntity(final Long id, final UUID uuid) {
    super(id, EntityState.ACTIVE, uuid, "probe-snapshot-seed", null, null);
  }
}
