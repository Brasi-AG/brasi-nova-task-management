package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.entity.dictionary.NotificationSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.NotificationType;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaFkScope;
import io.keel.framework.api.annotation.db.NovaForeignKey;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Benachrichtigung an einen Benutzer (optional ohne Aufgabenbezug = System-Benachrichtigung). */
@NovaTable(name = "TASK_NOTIFICATION")
@Getter
@Setter
@NoArgsConstructor
public class TaskNotification extends NovaEntity<TaskNotification> {

  private static final long serialVersionUID = 1L;

  /** UUID des Empfaengers (PROD_BENUTZER.EXTERNALID). */
  @NovaColumn(name = "RECIPIENT_REF", nullable = false)
  private UUID recipientRef;

  /** Optionale Referenz auf die Aufgabe (null = System-Benachrichtigung). */
  @NovaColumn(name = "TASK_ID", nullable = true)
  private Long taskId;

  /** FK auf {@code DIC_NOTIFICATION_TYPE} (global). */
  @NovaColumn(
      name = "TYPE_ID",
      nullable = false,
      foreignKey = @NovaForeignKey(referencedEntity = NotificationType.class))
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long typeId;

  /** Kennzeichen: Benachrichtigung wurde gelesen. */
  @NovaColumn(name = "IS_READ", nullable = false)
  private boolean isRead;

  /** Optionale UUID des Akteurs, der die Benachrichtigung ausgeloest hat. */
  @NovaColumn(name = "ACTOR_REF", nullable = true)
  private UUID actorRef;

  /** Anzeigetext der Benachrichtigung. */
  @NovaColumn(name = "TEXT", nullable = false, length = 500)
  private String text;

  /** Optionale Vorschau (z.B. Aufgabentitel). */
  @NovaColumn(name = "PREVIEW", nullable = true, length = 500)
  private String preview;

  /** Optionale FK auf {@code DIC_NOTIFICATION_SEVERITY} (global). */
  @NovaColumn(
      name = "SEVERITY_ID",
      nullable = true,
      foreignKey = @NovaForeignKey(referencedEntity = NotificationSeverity.class))
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long severityId;
}
