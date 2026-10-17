package de.brasi.nova.taskmanagement.entity;

import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Historieneintrag einer Aufgabe (Aktivitaetsprotokoll). */
@NovaTable(name = "TASK_HISTORY_ENTRY")
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
