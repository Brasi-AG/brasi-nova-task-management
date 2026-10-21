package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Uebergabeziel eines {@link TaskHandover}-Ereignisses (BE-7). Jedes Ziel ist ein Benutzer, der
 * durch die Uebergabe zum Assignee wird. UNIQUE(HANDOVER_ID, TO_REF) verhindert doppelte Ziele im
 * selben Ereignis.
 */
@NovaTable(name = "TASK_HANDOVER_TARGET")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskHandoverTarget extends NovaEntity<TaskHandoverTarget> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf das {@link TaskHandover}-Ereignis. */
  @NovaColumn(name = "HANDOVER_ID", nullable = false)
  private Long handoverId;

  /** UUID des Uebergabeziels (PROD_BENUTZER.EXTERNALID). */
  @NovaColumn(name = "TO_REF", nullable = false)
  private UUID toRef;
}
