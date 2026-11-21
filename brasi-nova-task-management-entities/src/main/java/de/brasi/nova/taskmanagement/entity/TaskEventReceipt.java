package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.entity.enums.EventReceiptStatus;
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
 * Quittung eines eingegangenen Ereignisses (Inbox fuer die Idempotenz). Je Mandanten-UUID und
 * Ereignis-ID hoechstens eine Zeile (Unique-Schluessel in V15).
 */
@NovaTable(name = "TASK_EREIGNIS_QUITTUNG")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskEventReceipt extends NovaEntity<TaskEventReceipt> {

  private static final long serialVersionUID = 1L;

  /** Scope der Quittung: tenantId des Ereignisses. */
  @NovaColumn(name = "MANDANT_UUID", nullable = false)
  private UUID tenantUuid;

  /** ID des Ereignisses (novaEventId). */
  @NovaColumn(name = "EREIGNIS_ID", nullable = false)
  private UUID eventId;

  /** Betroffene Abteilung; nur vom Division-Consumer gesetzt, sonst leer. */
  @NovaColumn(name = "ABTEILUNG_REF", nullable = true)
  private UUID divisionRef;

  /** Ergebnis der Verarbeitung (PROCESSED | DEAD). Von KSF nativ als String persistiert. */
  @NovaColumn(name = "STATUS", nullable = false)
  private EventReceiptStatus status;

  /** Grund der Verwerfung bei DEAD (hoechstens 900 Zeichen). */
  @NovaColumn(name = "FEHLER_MELDUNG", nullable = true, length = 900)
  private String errorMessage;
}
