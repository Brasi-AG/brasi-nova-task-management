package de.brasi.nova.taskmanagement.entity;

import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Kommentar an einer Aufgabe (optional als Antwort auf einen anderen Kommentar). */
@NovaTable(name = "TASK_COMMENT")
@Getter
@Setter
@NoArgsConstructor
public class TaskComment extends NovaEntity<TaskComment> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf die Aufgabe. */
  @NovaColumn(name = "TASK_ID", nullable = false)
  private Long taskId;

  /** UUID des Autors (PROD_BENUTZER.EXTERNALID). */
  @NovaColumn(name = "AUTHOR_REF", nullable = false)
  private UUID authorRef;

  /** Optionaler Eltern-Kommentar (null = Top-Level, sonst Antwort). */
  @NovaColumn(name = "PARENT_COMMENT_ID", nullable = true)
  private Long parentCommentId;

  /** Kommentartext (Markdown mit @handle-Mentions). */
  @NovaColumn(name = "TEXT", nullable = false)
  private String text;

  /** Kennzeichen: Kommentar wurde nachtraeglich bearbeitet. */
  @NovaColumn(name = "IS_EDITED", nullable = false)
  private boolean isEdited;
}
