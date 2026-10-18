package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
  @Operation(
      summary = "Kommentar erstellen",
      description = "Erstellt einen neuen Kommentar fuer eine Aufgabe."
          + " Unterstuetzt Antwort-Kommentare (parentCommentId) und @handle-Mentions."
          + " REPLY- und MENTION-Benachrichtigungen werden automatisch emittiert.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "201",
          description = "Kommentar erfolgreich erstellt.",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = CommentModel.class),
              examples = @ExampleObject(value = "{"
                  + "\"id\": \"660e8400-e29b-41d4-a716-446655440001\","
                  + "\"taskId\": \"550e8400-e29b-41d4-a716-446655440000\","
                  + "\"authorRef\": \"770e8400-e29b-41d4-a716-446655440002\","
                  + "\"parentCommentId\": null,"
                  + "\"text\": \"Bitte bis Ende der Woche erledigen.\","
                  + "\"edited\": false,"
                  + "\"createdAt\": \"2026-10-18T08:00:00Z\""
                  + "}"))),
      @ApiResponse(responseCode = "400", description = "Ungueltige Eingabe (leerer Text, ungueltiger Eltern-Kommentar)."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_WRITE-Berechtigung."),
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden oder fremder Mandant (neutral maskiert).")
  })
  @PostMapping("/{id}/comments")
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<CommentModel> createComment(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable final UUID id,
      @RequestBody final CommentCreateInput input) {
    final CommentModel model = commentService.createComment(id, input);
    return ResponseEntity.status(HttpStatus.CREATED).body(model);
  }
}
