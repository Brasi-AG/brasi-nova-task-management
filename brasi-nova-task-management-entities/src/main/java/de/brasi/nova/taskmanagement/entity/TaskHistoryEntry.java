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

/** Historieneintrag einer Aufgabe (Aktivitaetsprotokoll). */
@NovaTable(name = "TASK_HISTORY_ENTRY")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskHistoryEntry extends NovaEntity<TaskHistoryEntry> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf die Aufgabe. */
  @NovaColumn(name = "TASK_ID", nullable = false)
  private Long taskId;

  /** UUID des Akteurs (PROD_BENUTZER.EXTERNALID). */
  @NovaColumn(name = "ACTOR_REF", nullable = false)
  private UUID actorRef;

  /** Kurzbeschreibung der Aenderung (z.B. "hat Faelligkeitsdatum geaendert"). */
  @NovaColumn(name = "TEXT", nullable = false, length = 500)
  private String text;

  /** Optionales Detail der Aenderung (z.B. "Fr 02.10. -> Mo 05.10."). */
  @NovaColumn(name = "CHANGE_DETAIL", nullable = true, length = 500)
  private String changeDetail;
}
