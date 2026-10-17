package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.entity.dictionary.TagSeverity;
import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaFkScope;
import io.keel.framework.api.annotation.db.NovaForeignKey;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaDictionaryEntry;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Globaler Tag-Katalog (user-verwalteter Dictionary, TENANT_ID=0). Der Tag-Name ist das geerbte
 * {@code entryValue} (Spalte BEZEICHNUNG), kein eigenes NAME-Feld.
 */
@NovaTable(name = "TASK_TAG")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskTag extends NovaDictionaryEntry<TaskTag> {

  private static final long serialVersionUID = 1L;

  /** FK auf {@code DIC_TAG_SEVERITY} (global). */
  @NovaColumn(
      name = "SEVERITY_ID",
      nullable = false,
      foreignKey = @NovaForeignKey(referencedEntity = TagSeverity.class))
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long severityId;
}
