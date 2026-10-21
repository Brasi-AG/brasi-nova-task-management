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
 * Uebergabe-Ereignis einer Aufgabe (BE-7). Jede Uebergabe (auch wiederholte) ist ein eigenes
 * Ereignis; die Ereigniszeit ist die Nova-Basisspalte {@code ANGELEGT_AM}. Die Uebergabeziele
 * haengen als {@link TaskHandoverTarget} an diesem Ereignis.
 *
 * <p>Bewusst KEIN (TASK_ID, FROM_REF)-UNIQUE: wiederholte Uebergaben desselben Akteurs an
 * verschiedene Ziele sind getrennte Ereignisse. {@code SHOULD_STAY} haelt fest, ob der Akteur bei
 * der Uebergabe als Teilnehmer verbleiben wollte (dann kein Austritt).
 */
@NovaTable(name = "TASK_HANDOVER")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskHandover extends NovaEntity<TaskHandover> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf die Aufgabe. */
  @NovaColumn(name = "TASK_ID", nullable = false)
  private Long taskId;

  /** UUID des uebergebenden Akteurs (PROD_BENUTZER.EXTERNALID). */
  @NovaColumn(name = "FROM_REF", nullable = false)
  private UUID fromRef;

  /** Kennzeichen: der Akteur bleibt bei dieser Uebergabe Teilnehmer (kein Austritt). */
  @NovaColumn(name = "SHOULD_STAY", nullable = false)
  private boolean shouldStay;
}
