package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Idempotenz-Quittung des Due-Reminder-Schedulers: genau eine DUE-Benachrichtigung pro (Aufgabe,
 * Empfaenger, Faelligkeitstag). UNIQUE-Constraint in Flyway (Task 5).
 */
@NovaTable(name = "TASK_REMINDER_RECEIPT")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskReminderReceipt extends NovaEntity<TaskReminderReceipt> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf die Aufgabe. */
  @NovaColumn(name = "TASK_ID", nullable = false)
  private Long taskId;

  /** UUID des Empfaengers (PROD_BENUTZER.EXTERNALID). */
  @NovaColumn(name = "RECIPIENT_REF", nullable = false)
  private UUID recipientRef;

  /** Faelligkeitstag, fuer den die Erinnerung ausgeloest wurde. */
  @NovaColumn(name = "OCCURRENCE_DATE", nullable = false)
  private LocalDate occurrenceDate;
}
