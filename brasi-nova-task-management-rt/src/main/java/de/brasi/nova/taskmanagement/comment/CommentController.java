package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 * Der Controller enthaelt keine Fachlogik — er delegiert an {@link CommentService} (Schreiben) und
 * {@link CommentQueryService} (Lesen).
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class CommentController {

  private final CommentService commentService;
  private final CommentQueryService commentQueryService;

  /** Konstruktor-Injektion der Kommentar-Services. */
  public CommentController(
      final CommentService commentService, final CommentQueryService commentQueryService) {
    this.commentService = commentService;
    this.commentQueryService = commentQueryService;
  }

  /**
   * Liefert alle aktiven Kommentare einer Aufgabe als flache, chronologische Liste.
   *
   * <p>Der Client baut den Antwort-Baum ueber {@code parentCommentId}. Task-scoped autorisiert
   * ({@code TASK_READ} + Mandant); nicht gefundene/fremde/deaktivierte Aufgabe wird neutral als
   * 404 maskiert (keine separate Mitgliedschaftspruefung, konsistent mit {@code GET /tasks/{id}}).
   *
   * @param id externe UUID der Aufgabe
   * @return 200 OK mit chronologischer Liste von {@link CommentModel}
   */
  @Operation(
      summary = "Kommentare einer Aufgabe lesen",
      description = "Liefert die flache, chronologische Kommentarliste einer Aufgabe."
          + " Der Client rekonstruiert den Antwort-Baum ueber parentCommentId.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Kommentare chronologisch aufsteigend.",
          content = @Content(
              mediaType = "application/json",
              array = @ArraySchema(schema = @Schema(implementation = CommentModel.class)))),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_READ-Berechtigung."),
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden oder fremder Mandant (neutral maskiert).")
  })
  @GetMapping("/{id}/comments")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<List<CommentModel>> getComments(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true)
          @PathVariable final UUID id) {
    return ResponseEntity.ok(commentQueryService.getComments(id));
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
