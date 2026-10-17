package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer Kommentar-Operationen (arch-v2 §7).
 *
 * <p>Jeder Handler traegt eine {@link RequiresPermission}-Annotation (Pflicht gemaess arch-v2 §8).
 * Actor-UUID kommt ausschliesslich aus dem Security-Context, NIEMALS aus Client-Eingabedaten.
 * Der Controller enthaelt keine Fachlogik — er delegiert an {@link CommentService}.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class CommentController {

  private final CommentService commentService;

  /** Konstruktor-Injektion des {@link CommentService}. */
  public CommentController(final CommentService commentService) {
    this.commentService = commentService;
  }

  /**
   * Erstellt einen neuen Kommentar fuer eine Aufgabe.
   *
   * <p>Optional als Antwort auf einen anderen Kommentar ({@code parentCommentId} im Body).
   * @handle-Mentions im Text loesen MENTION-Benachrichtigungen aus;
   * ein Eltern-Kommentar loest eine REPLY-Benachrichtigung an den Autor des Eltern-Kommentars aus.
   *
   * @param id    externe UUID der Aufgabe
   * @param input Kommentar-Eingabe
   * @return 201 Created mit {@link CommentModel} des neuen Kommentars
   */
  @PostMapping("/{id}/comments")
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<CommentModel> createComment(
      @PathVariable final UUID id, @RequestBody final CommentCreateInput input) {
    final CommentModel model = commentService.createComment(id, input);
    return ResponseEntity.status(HttpStatus.CREATED).body(model);
  }
}
