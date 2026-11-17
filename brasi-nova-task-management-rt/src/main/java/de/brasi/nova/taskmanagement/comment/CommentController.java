package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.inbox.model.ArchiveSearchPageSchema;
import de.brasi.nova.taskmanagement.task.TaskHateoas;
import io.keel.framework.api.hateoas.annotation.HateoasLinkTarget;
import io.keel.framework.web.exception.ApiError;
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
 * <p>Beide Handler sind Standard-Selfservice (authenticated-only via KSF-Filter-Chain); die
 * Autorisierung erfolgt objekt-level ueber den {@code TaskMembershipGuard} in den delegierten
 * Services: {@code getComments} (GET) verlangt READ-Member, {@code createComment} (POST)
 * WRITE-Member (Non-/Ex-Member -> neutrale 404 bzw. kein Comment-Write). Actor-UUID kommt
 * ausschliesslich aus dem Security-Context, NIEMALS aus Client-Eingabedaten. Der Controller
 * enthaelt keine Fachlogik — er delegiert an {@link CommentService} (Schreiben) und {@link
 * CommentQueryService} (Lesen).
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
   * <p>Der Client baut den Antwort-Baum ueber {@code parentCommentId}. Standard-Selfservice:
   * READ-Member-Guard im Service (konsistent mit {@code GET /tasks/{id}}); nicht gefundene/fremde/
   * deaktivierte Aufgabe ODER kein Mitglied wird neutral als 404 maskiert.
   *
   * @param id externe UUID der Aufgabe
   * @return 200 OK mit chronologischer Liste von {@link CommentModel}
   */
  @Operation(
      summary = "Kommentare einer Aufgabe lesen",
      description =
          "Liefert die flache, chronologische Kommentarliste einer Aufgabe."
              + " Der Client rekonstruiert den Antwort-Baum ueber parentCommentId.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Kommentare chronologisch aufsteigend.",
        content =
            @Content(
                mediaType = "application/json",
                array = @ArraySchema(schema = @Schema(implementation = CommentModel.class)))),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @GetMapping("/{id}/comments")
  @HateoasLinkTarget(TaskHateoas.Targets.GET_COMMENTS)
  public ResponseEntity<List<CommentModel>> getComments(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id) {
    return ResponseEntity.ok(commentQueryService.getComments(id));
  }

  /**
   * Erstellt einen neuen Kommentar fuer eine Aufgabe.
   *
   * <p>Optional als Antwort auf einen anderen Kommentar ({@code parentCommentId} im Body). Ein
   * Eltern-Kommentar loest eine REPLY-Benachrichtigung an dessen Autor aus. Die in {@code mentions}
   * genannten Personen erhalten eine MENTION: Mitglieder der Aufgabe direkt; aktive Benutzer des
   * eigenen Mandanten, die noch kein Mitglied sind, werden zuvor als Beteiligte hinzugefuegt (nur
   * Teilnehmer, nie Assignee), wenn der Kommentierende dieselbe Berechtigung wie fuer {@code POST
   * /participants} hat (Intake #156); sonst werden sie still verworfen, ebenso unbekannte, inaktive
   * oder mandantenfremde UUIDs.
   *
   * @param id externe UUID der Aufgabe
   * @param input Kommentar-Eingabe
   * @return 201 Created mit {@link CommentModel} des neuen Kommentars
   */
  @Operation(
      summary = "Kommentar erstellen",
      description =
          "Erstellt einen neuen Kommentar fuer eine Aufgabe, optional als Antwort auf einen anderen"
              + " Kommentar (parentCommentId). Nur aktive Mitglieder der Aufgabe duerfen"
              + " kommentieren. Benachrichtigungen: Der Autor des Eltern-Kommentars erhaelt REPLY,"
              + " die in mentions genannten Personen erhalten MENTION (auch bei stummgeschalteter"
              + " Aufgabe), bei einem Kommentar ohne Eltern-Kommentar erhalten die nicht"
              + " stummgeschalteten Assignees COMMENT; die aufrufende Person selbst wird nie"
              + " benachrichtigt. Erwaehnungen sind explizite UUIDs in mentions und werden nicht"
              + " aus dem Text gelesen. Ist eine erwaehnte Person ein aktiver Benutzer des eigenen"
              + " Mandanten, aber noch kein aktives Mitglied der Aufgabe (auch wer ausgetreten ist),"
              + " wird sie als Beteiligte hinzugefuegt, sofern die aufrufende Person Beteiligte"
              + " hinzufuegen darf, also dieselbe Berechtigung TASK_WRITE hat wie bei POST"
              + " /api/v1/tasks/{id}/participants: Sie wird reiner Teilnehmer (nie Assignee; ein"
              + " Ausgetretener wird als reiner Teilnehmer reaktiviert, eine Stummschaltung bleibt)"
              + " und erhaelt nur die MENTION, keine zusaetzliche PARTICIPANT-Benachrichtigung."
              + " Fehlt der aufrufenden Person die Berechtigung, werden erwaehnte Nicht-Mitglieder"
              + " still verworfen (kein Fehler, keine Aufnahme, keine Benachrichtigung), erwaehnte"
              + " Mitglieder werden weiter benachrichtigt. Unbekannte, inaktive und"
              + " mandantenfremde UUIDs in mentions werden immer still verworfen (kein 400). Alles"
              + " oder nichts: bei einem Fehler wird weder der Kommentar noch eine Aufnahme"
              + " gespeichert.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description =
            "Kommentar erfolgreich erstellt; die Benachrichtigungen sind gespeichert, erwaehnte"
                + " Nicht-Mitglieder sind (mit Berechtigung) Beteiligte der Aufgabe.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CommentModel.class),
                examples =
                    @ExampleObject(
                        name = "antwort",
                        summary = "Antwort auf einen Kommentar",
                        description =
                            "Bei einem Kommentar ohne Eltern-Kommentar ist parentCommentId null.",
                        value = CommentApiExamples.CREATED_REPLY))),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Eingabe: text fehlt oder ist leer, mehr als 100 oder ein null-Element in"
                + " mentions, Eltern-Kommentar unbekannt, zu einer anderen Aufgabe gehoerig oder"
                + " nicht aktiv, kein gueltiges JSON oder keine UUID im Pfad. Eine unbekannte,"
                + " inaktive oder mandantenfremde UUID in mentions ist kein Fehler (still"
                + " verworfen).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "eltern-kommentar-ungueltig",
                      summary = "Eltern-Kommentar unbekannt, fremd oder inaktiv",
                      value = CommentApiExamples.BAD_REQUEST_PARENT),
                  @ExampleObject(
                      name = "text-fehlt",
                      summary = "Body ohne Text oder mit leerem Text",
                      value = CommentApiExamples.BAD_REQUEST_BODY)
                })),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = CommentApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder kein aktives Mitglied der Aufgabe"
                + " (neutral maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = CommentApiExamples.NOT_FOUND))),
    @ApiResponse(
        responseCode = "409",
        description =
            "Nebenlaeufiger Konflikt: die Aufgabe (Zaehler und Aktivitaet) oder bei der Aufnahme"
                + " erwaehnter Personen deren Teilnehmerzeile bzw. persoenlicher Zustand wurde"
                + " zwischenzeitlich geaendert (Versionskonflikt) oder gleichzeitig erstmals"
                + " angelegt. Nichts wurde gespeichert; die Anfrage gegen den frischen Stand"
                + " wiederholen.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = CommentApiExamples.CONFLICT)))
  })
  @PostMapping("/{id}/comments")
  @HateoasLinkTarget(TaskHateoas.Targets.CREATE_COMMENT)
  public ResponseEntity<CommentModel> createComment(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description =
                  "Kommentartext, optional Eltern-Kommentar und erwaehnte Benutzer (hoechstens"
                      + " 100).",
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = CommentCreateInput.class),
                      examples = {
                        @ExampleObject(
                            name = "top-level",
                            summary = "Kommentar ohne Eltern-Kommentar",
                            value = CommentApiExamples.REQUEST_TOP_LEVEL),
                        @ExampleObject(
                            name = "mit-erwaehnung",
                            summary = "Kommentar mit Erwaehnung einer Person",
                            description =
                                "Ist die Person noch kein Mitglied der Aufgabe, wird sie mit der"
                                    + " Berechtigung TASK_WRITE als Beteiligte hinzugefuegt und"
                                    + " erhaelt nur die MENTION; ohne Berechtigung wird die"
                                    + " Erwaehnung still verworfen.",
                            value = CommentApiExamples.REQUEST_WITH_MENTION),
                        @ExampleObject(
                            name = "antwort",
                            summary = "Antwort auf einen Kommentar",
                            value = CommentApiExamples.REQUEST_REPLY)
                      }))
          @RequestBody
          final CommentCreateInput input) {
    final CommentModel model = commentService.createComment(id, input);
    return ResponseEntity.status(HttpStatus.CREATED).body(model);
  }
}
