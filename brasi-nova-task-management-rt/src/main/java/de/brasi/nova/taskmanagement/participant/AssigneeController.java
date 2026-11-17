package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.inbox.model.ArchiveSearchPageSchema;
import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.participant.model.ExitInput;
import de.brasi.nova.taskmanagement.participant.model.ParticipantAddInput;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.task.TaskHateoas;
import io.keel.framework.api.hateoas.annotation.HateoasLinkTarget;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.web.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer Assignee-Verwaltung und Austritt (arch-v2 Abschnitt 7).
 *
 * <p>Die Admin-/Voll-Endpunkte ({@code updateAssignees}, {@code addParticipants}) tragen eine
 * {@link RequiresPermission}-Annotation (arch-v2 Abschnitt 8). {@code exit} ist seit dem
 * Standard-Selfservice (Slice 4) nur noch authenticated-only (anonym -&gt; 401 ueber die
 * KSF-Filter-Chain); der objekt-level Membership-Guard im {@link AssigneeService} ersetzt das grobe
 * Permission-Gate (grant-freier WRITE-Member darf austreten/uebergeben, die Handover-Grenze bleibt
 * TASK_WRITE-geschuetzt). Actor-UUID kommt ausschliesslich aus dem Security-Context, NIEMALS aus
 * Client-Eingabedaten.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class AssigneeController {

  private final AssigneeService assigneeService;

  /** Konstruktor-Injektion des {@link AssigneeService}. */
  public AssigneeController(final AssigneeService assigneeService) {
    this.assigneeService = assigneeService;
  }

  /**
   * Aktualisiert die Assignee-Menge einer Aufgabe (PUT-Semantik: Ziel-Menge).
   *
   * <p>Neue Assignees erhalten eine ASSIGN-Notification und werden fuer diese Aufgabe entstummt
   * (der Mute-Marker wird zurueckgesetzt, Folge von Intake #158); unveraendert bleibende Assignees
   * behalten ihren Mute. Abgewaehlte werden mit EXIT_KIND=UNSUBSCRIBED markiert.
   *
   * @param id externe UUID der Aufgabe
   * @param input Ziel-Assignee-Menge
   * @return 204 No Content
   */
  @Operation(
      summary = "Assignees aktualisieren",
      description =
          "Ersetzt die aktuelle Assignee-Menge durch die gewuenschte Ziel-Menge (PUT-Set-Semantik)."
              + " Neu zugewiesene Benutzer erhalten eine ASSIGN-Notification."
              + " Neu zugewiesene Benutzer werden fuer diese Aufgabe entstummt: eine zuvor gesetzte"
              + " Stummschaltung (PUT /api/v1/tasks/{id}/mute) wird aufgehoben; das gilt auch fuer"
              + " zuvor ausgetretene oder als reine Teilnehmer gefuehrte Benutzer, die jetzt"
              + " zugewiesen werden. Assignees, die in der Ziel-Menge unveraendert bleiben, behalten"
              + " ihre Stummschaltung."
              + " Ein ausgelassener Creator wird nur de-assigned und bleibt Mitglied der Aufgabe"
              + " (kein Austritt).")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Assignees erfolgreich aktualisiert."),
    @ApiResponse(
        responseCode = "400",
        description = "Ungueltige Eingabe (z.B. unbekannte Benutzer-UUID, null-Elemente).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.BAD_REQUEST_ASSIGNEES))),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = ParticipantApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "403",
        description = "Fehlende TASK_WRITE-Berechtigung.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.FORBIDDEN_ASSIGNEES))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder keine aktive Mitgliedschaft (neutral"
                + " maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.NOT_FOUND_ASSIGNEES))),
    @ApiResponse(
        responseCode = "409",
        description =
            "Nebenlaeufiger Versionskonflikt: die Aufgabe wurde zwischenzeitlich durch eine"
                + " parallele Aenderung der Mitglieder (PUT /assignees, Austritt, Uebergabe)"
                + " geaendert; die Anfrage gegen den frischen Stand wiederholen.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.CONFLICT_ASSIGNEES)))
  })
  @PutMapping("/{id}/assignees")
  @HateoasLinkTarget(TaskHateoas.Targets.UPDATE_ASSIGNEES)
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> updateAssignees(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description = "Gewuenschte Ziel-Menge der Assignees (ersetzt den aktuellen Stand).",
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = AssigneeUpdateInput.class),
                      examples = {
                        @ExampleObject(
                            name = "zuweisen",
                            summary = "Zwei Benutzer als Assignees setzen",
                            description =
                                "Neu hinzukommende Benutzer werden zugewiesen und entstummt;"
                                    + " nicht mehr genannte Assignees werden abgewaehlt.",
                            value =
                                "{\"assignees\":[\""
                                    + ParticipantApiExamples.USER_REF_A
                                    + "\",\""
                                    + ParticipantApiExamples.USER_REF_B
                                    + "\"]}"),
                        @ExampleObject(
                            name = "alle-abwaehlen",
                            summary = "Alle Nicht-Creator-Assignees abwaehlen",
                            description =
                                "Ein leeres Array leert die Menge; ein Creator wird nur"
                                    + " de-assigned und bleibt Mitglied.",
                            value = "{\"assignees\":[]}")
                      }))
          @RequestBody
          final AssigneeUpdateInput input) {
    assigneeService.updateAssignees(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Aktueller Akteur verlaesst die Aufgabe (HANDOVER oder UNSUBSCRIBED).
   *
   * <p>Bei HANDOVER werden die {@code handedOverToIds} zu Assignees und erhalten eine
   * HANDOVER-Notification. Ziele, die vorher kein aktiver Assignee waren, werden zudem fuer diese
   * Aufgabe entstummt (Mute-Marker zurueckgesetzt, Folge von Intake #158); ein bereits aktiver
   * Assignee behaelt seinen Mute. Der Akteur wird de-assignt. Mit {@code shouldStay=true} bleibt
   * der Akteur reiner Teilnehmer (kein Austritt). Ein Creator kann die Aufgabe nur per HANDOVER mit
   * {@code shouldStay=true} abgeben (sonst 409).
   *
   * @param id externe UUID der Aufgabe
   * @param input Austritts-Eingabe (exitKind, handedOverToIds, shouldStay)
   * @return 204 No Content
   */
  @Operation(
      summary = "Aufgabe verlassen / uebergeben",
      description =
          "Der angemeldete Benutzer verlaesst die Aufgabe oder uebergibt sie."
              + " exitKind: HANDOVER (Aufgabe uebergeben) oder UNSUBSCRIBED (Aufgabe abgemeldet)."
              + " Bei HANDOVER werden handedOverToIds zu Assignees."
              + " Ziele, die vorher kein aktiver Assignee waren, werden dabei fuer diese Aufgabe"
              + " entstummt: eine zuvor gesetzte Stummschaltung (PUT /api/v1/tasks/{id}/mute)"
              + " wird aufgehoben. Ein Ziel, das bereits aktiver Assignee ist, behaelt seine"
              + " Stummschaltung. Die HANDOVER-Benachrichtigung erreicht alle Ziele ohnehin."
              + " shouldStay=true laesst den Akteur als reinen Teilnehmer verbleiben (kein"
              + " Austritt). Der Creator kann die Aufgabe nur per HANDOVER mit shouldStay=true"
              + " abgeben (ansonsten 409).")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Aufgabe erfolgreich verlassen/uebergeben."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Eingabe: UNSUBSCRIBED mit handedOverToIds/shouldStay; shouldStay=true ohne"
                + " Ziele; Selbstziel; unbekannte/fremde Ziel-UUID; mehr als 100 Ziele;"
                + " null-Element.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.BAD_REQUEST_EXIT))),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = ParticipantApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "403",
        description =
            "Standard-Selfservice (Slice 4): der Akteur ist bestaetigtes Mitglied, aber ein"
                + " HANDOVER-Ziel ist noch kein WRITE-Member (neu/ausgetreten/inaktiv/TEAM/fremder"
                + " Mandant) -> die Ziel-Einfuehrung erfordert TASK_WRITE.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.FORBIDDEN_EXIT))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder keine aktive Mitgliedschaft (neutral"
                + " maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.NOT_FOUND_EXIT))),
    @ApiResponse(
        responseCode = "409",
        description =
            "Der Creator kann die Aufgabe nur per HANDOVER mit shouldStay=true abgeben;"
                + " ensureAssignee-Auswahlvertrag fail-closed (Ziel-Zeile TEAM oder fremdes"
                + " Basis-TENANT_ID); oder nebenlaeufiger Versionskonflikt (Retry gegen frischen"
                + " Stand).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.CONFLICT_EXIT)))
  })
  @PostMapping("/{id}/exit")
  @HateoasLinkTarget(TaskHateoas.Targets.EXIT)
  public ResponseEntity<Void> exit(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description = "Art des Austritts, Uebergabeziele und optionaler Verbleib.",
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = ExitInput.class),
                      examples = {
                        @ExampleObject(
                            name = "uebergeben",
                            summary = "Aufgabe an einen Benutzer uebergeben und verlassen",
                            description =
                                "Das Ziel wird Assignee (entstummt, falls es vorher kein aktiver"
                                    + " Assignee war); der Akteur verlaesst die Aufgabe.",
                            value =
                                "{\"exitKind\":\"HANDOVER\",\"handedOverToIds\":[\""
                                    + ParticipantApiExamples.USER_REF_B
                                    + "\"],\"shouldStay\":false}"),
                        @ExampleObject(
                            name = "abmelden",
                            summary = "Aufgabe abmelden",
                            description = "Der Akteur meldet sich von der Aufgabe ab.",
                            value = "{\"exitKind\":\"UNSUBSCRIBED\"}")
                      }))
          @RequestBody
          final ExitInput input) {
    assigneeService.exit(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Fuegt Benutzer additiv als Teilnehmer der Aufgabe hinzu (idempotent).
   *
   * <p>Verleiht ausschliesslich die Teilnehmer-Rolle (PARTICIPANT_FLAG), NIE die Assignee-Rolle.
   * Bereits aktive Teilnehmer werden uebersprungen; ein leeres Array ist ein No-Op.
   *
   * @param id externe UUID der Aufgabe
   * @param input hinzuzufuegende Teilnehmer-UUIDs
   * @return 204 No Content
   */
  @Operation(
      summary = "Teilnehmer hinzufuegen",
      description =
          "Fuegt die angegebenen Benutzer additiv als Teilnehmer der Aufgabe hinzu (idempotent)."
              + " Verleiht ausschliesslich die Teilnehmer-Rolle, nicht die Assignee-Rolle."
              + " Bereits aktive Teilnehmer werden uebersprungen; ein leeres Array ist ein No-Op.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "204",
        description = "Teilnehmer erfolgreich hinzugefuegt (oder No-Op)."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Eingabe (unbekannte Benutzer-UUID, null-Elemente, mehr als 100)."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(responseCode = "403", description = "Fehlende TASK_WRITE-Berechtigung."),
    @ApiResponse(
        responseCode = "404",
        description = "Aufgabe nicht gefunden oder fremder Mandant (neutral maskiert).")
  })
  @PostMapping("/{id}/participants")
  @HateoasLinkTarget(TaskHateoas.Targets.ADD_PARTICIPANTS)
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> addParticipants(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @RequestBody final ParticipantAddInput input) {
    assigneeService.addParticipants(id, input);
    return ResponseEntity.noContent().build();
  }
}
