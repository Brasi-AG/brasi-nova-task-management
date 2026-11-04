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

  /**
   * FK auf {@code DIC_TAG_SEVERITY} (global). Seit Intake #135 (Phase 1) nullable: Tags koennen ohne
   * severity existieren (color-only oder neutral).
   *
   * <p>{@code updateable = true}: Die severity eines Tags muss sich ueber {@code PUT /tags/{id}}
   * aendern koennen (severity setzen bzw. beim color-Loeschen auf null neutralisieren). KSF filtert
   * FK-Spalten per Default ({@code @NovaForeignKey.updateable=false}) aus dem UPDATE-SET
   * ({@code MutationExecutor.performUpdate}), wodurch {@code setSeverityId(...)} beim {@code save()}
   * still verworfen wurde (Insert ok / Update gedroppt - belegt durch Slice-3-IT AC5 i/iii). Mit
   * {@code updateable=true} wird die severity-Aenderung korrekt persistiert (analog
   * {@code Task.taskStatusId}). {@code @NovaFkScope(GLOBAL)} bleibt fuer die UUID-Aufloesung.
   */
  @NovaColumn(
      name = "SEVERITY_ID",
      nullable = true,
      foreignKey = @NovaForeignKey(referencedEntity = TagSeverity.class, updateable = true))
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long severityId;

  /**
   * UI-Farbe des Tags als Hex-String ({@code #RRGGBB} / {@code #RGB}, normalisiert Grossbuchstaben).
   * Additiv seit Intake #135 (Phase 1); {@code null} = keine Farbe (neutraler Tag).
   */
  @NovaColumn(name = "COLOR", nullable = true, length = 7)
  private String color;
}
