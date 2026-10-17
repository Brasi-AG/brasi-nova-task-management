package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaFkScope;
import io.keel.framework.api.annotation.db.NovaForeignKey;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Zuordnung eines globalen Tags zu einer Aufgabe. */
@NovaTable(name = "TASK_TAG_ASSIGNMENT")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskTagAssignment extends NovaEntity<TaskTagAssignment> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf die Aufgabe (Tenant der Aufgabe). */
  @NovaColumn(name = "TASK_ID", nullable = false)
  private Long taskId;

  /** FK auf den globalen {@code TASK_TAG}-Katalog (Tenant 0). */
  @NovaColumn(
      name = "TAG_ID",
      nullable = false,
      foreignKey = @NovaForeignKey(referencedEntity = TaskTag.class))
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long tagId;
}
