package de.brasi.nova.taskmanagement.entity;

import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Benutzerspezifischer Zustand einer Aufgabe (Pinned, Flagged, Muted, gelesen). */
@NovaTable(name = "TASK_USER_STATE")
@Getter
@Setter
@NoArgsConstructor
public class TaskUserState extends NovaEntity<TaskUserState> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf die Aufgabe. */
  @NovaColumn(name = "TASK_ID", nullable = false)
  private Long taskId;

  /** UUID des Benutzers. */
  @NovaColumn(name = "USER_REF", nullable = false)
  private UUID userRef;

  /** Mandant des Benutzers. */
  @NovaColumn(name = "USER_TENANT_ID", nullable = false)
  private Long userTenantId;

  /** Aufgabe ist angepinnt. */
  @NovaColumn(name = "IS_PINNED", nullable = false)
  private boolean isPinned;

  /** Aufgabe ist markiert. */
  @NovaColumn(name = "IS_FLAGGED", nullable = false)
  private boolean isFlagged;

  /** Benachrichtigungen sind stummgeschaltet. */
  @NovaColumn(name = "IS_MUTED", nullable = false)
  private boolean isMuted;

  /** Optionaler Zeitpunkt des letzten Lesens. */
  @NovaColumn(name = "READ_AT", nullable = true)
  private LocalDateTime readAt;

  /** Optionaler Zeitpunkt des Abmeldens. */
  @NovaColumn(name = "UNSUBSCRIBED_AT", nullable = true)
  private LocalDateTime unsubscribedAt;
}
