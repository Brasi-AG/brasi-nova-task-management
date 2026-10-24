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

/** Durable, tenant/actor-scoped result of a context-task create request. */
@NovaTable(name = "TASK_SOURCE_CREATE_RECEIPT")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskSourceCreateReceipt extends NovaEntity<TaskSourceCreateReceipt> {

  private static final long serialVersionUID = 1L;

  @NovaColumn(name = "TENANT_UUID", nullable = false)
  private UUID tenantUuid;

  @NovaColumn(name = "ACTOR_REF", nullable = false)
  private UUID actorRef;

  @NovaColumn(name = "IDEMPOTENCY_KEY", nullable = false, length = 128)
  private String idempotencyKey;

  @NovaColumn(name = "INPUT_SHA256", nullable = false, length = 64)
  private String inputSha256;

  @NovaColumn(name = "TASK_UUID", nullable = false)
  private UUID taskUuid;
}
