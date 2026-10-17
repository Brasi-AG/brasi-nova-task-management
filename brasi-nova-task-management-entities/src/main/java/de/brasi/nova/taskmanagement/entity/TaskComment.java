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

/** Kommentar an einer Aufgabe (optional als Antwort auf einen anderen Kommentar). */
@NovaTable(name = "TASK_COMMENT")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
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
