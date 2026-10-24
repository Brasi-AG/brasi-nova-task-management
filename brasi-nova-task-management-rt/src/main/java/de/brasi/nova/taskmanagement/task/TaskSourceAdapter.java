package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.security.CurrentActor;
import java.util.UUID;

/** A module-owned proof that the current actor can read an entity in the current tenant. */
public interface TaskSourceAdapter {
  String module();
  String entityType();
  void requireReadable(CurrentActor actor, UUID entityRef);
  String href(UUID tenantUuid, UUID entityRef);
}
