package de.brasi.nova.taskmanagement.entity.dictionary;

import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaDictionaryEntry;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Benachrichtigungs-Typ (Dictionary). Stabiler Resolver-Key: {@code code}. */
@NovaTable(name = "DIC_NOTIFICATION_TYPE")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class NotificationType extends NovaDictionaryEntry<NotificationType> {

  private static final long serialVersionUID = 1L;

  /**
   * Technischer Resolver-Key (unveraenderlich). Wertemenge: ASSIGN, REPLY, DUE, MENTION, DONE,
   * CHANGE, HANDOVER, SYSTEM.
   */
  @NovaColumn(name = "CODE", nullable = false, length = 50)
  private String code;
}
