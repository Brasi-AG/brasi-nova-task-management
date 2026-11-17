package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.inbox.model.ArchiveSearchPageSchema;
import de.brasi.nova.taskmanagement.participant.model.MarkerInput;
import de.brasi.nova.taskmanagement.task.TaskHateoas;
import io.keel.framework.api.hateoas.annotation.HateoasLinkTarget;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer persoenliche Marker (pin/flag/mute) (arch-v2 §7).
 *
 * <p>Alle drei Handler sind Standard-Selfservice (authenticated-only via KSF-Filter-Chain); die
 * Autorisierung erfolgt objekt-level ueber den {@code TaskMembershipGuard} im {@link MarkerService}
 * (WRITE-Member VOR dem {@code UserStateService.ensure}, Henne-Ei: ein pin/flag/mute auf eine
 * Fremd-Task legt keine Orphan-state-Zeile mehr an; Non-/Ex-Member -> neutrale 404). Die fachliche
 * Logik (Actor-Aufloesung, 404-Masking, Inbox-Invariante, Persistenz) liegt ausschliesslich im
 * {@link MarkerService}.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class UserStateController {

  private final MarkerService markerService;

  /** Konstruktor-Injektion des {@link MarkerService}. */
  public UserStateController(final MarkerService markerService) {
    this.markerService = markerService;
  }

  /**
   * Setzt den Pinned-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * @param id externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = angepinnt, {@code false} = nicht angepinnt)
   * @return 204 No Content
   */
  @Operation(
      summary = "Pin-Marker setzen",
      description =
          "Setzt den persoenlichen Pin-Marker des angemeldeten Benutzers fuer eine Aufgabe."
              + " true = angepinnt, false = nicht angepinnt.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Marker erfolgreich gesetzt."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @PutMapping("/{id}/pin")
  @HateoasLinkTarget(TaskHateoas.Targets.PIN)
  public ResponseEntity<Void> pin(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @RequestBody final MarkerInput input) {
    markerService.setPinned(id, input.value());
    return ResponseEntity.noContent().build();
  }

  /**
   * Setzt den Flagged-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * @param id externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = markiert, {@code false} = nicht markiert)
   * @return 204 No Content
   */
  @Operation(
      summary = "Flag-Marker setzen",
      description =
          "Setzt den persoenlichen Flag-Marker des angemeldeten Benutzers fuer eine Aufgabe."
              + " true = markiert, false = nicht markiert.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Marker erfolgreich gesetzt."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @PutMapping("/{id}/flag")
  @HateoasLinkTarget(TaskHateoas.Targets.FLAG)
  public ResponseEntity<Void> flag(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @RequestBody final MarkerInput input) {
    markerService.setFlagged(id, input.value());
    return ResponseEntity.noContent().build();
  }

  /**
   * Setzt den Muted-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * <p>Eine stummgeschaltete Aufgabe loest fuer den Benutzer keine Benachrichtigungen mehr aus,
   * ausser {@code MENTION}, {@code REPLY}, {@code ASSIGN} und {@code HANDOVER} ({@code
   * NotificationMuteFilter}). Eine neue Zuweisung (PUT /assignees fuer neu hinzukommende Personen,
   * HANDOVER-Ziele von POST /exit) hebt den Mute wieder auf ({@code
   * UserStateService#reactivateAsAssignee}); dieser Endpunkt selbst ruft das nie auf.
   *
   * @param id externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = stummgeschaltet, {@code false} = nicht
   *     stumm)
   * @return 204 No Content
   */
  @Operation(
      summary = "Mute-Marker setzen",
      description =
          "Setzt den persoenlichen Mute-Marker des angemeldeten Benutzers fuer eine Aufgabe."
              + " true = stummgeschaltet, false = nicht stumm."
              + " Fuer eine stummgeschaltete Aufgabe erhaelt der Benutzer keine Benachrichtigungen"
              + " mehr, ausser bei einer @-Erwaehnung (MENTION), bei einer Antwort auf einen"
              + " eigenen Kommentar (REPLY), bei einer Zuweisung (ASSIGN) und bei einer Uebergabe"
              + " (HANDOVER)."
              + " Eine neue Zuweisung der Aufgabe an den Benutzer hebt die Stummschaltung"
              + " automatisch auf: PUT /api/v1/tasks/{id}/assignees fuer neu hinzukommende Personen"
              + " und POST /api/v1/tasks/{id}/exit fuer die Ziele einer Uebergabe."
              + " Unveraendert bleibende Assignees und das Hinzufuegen als reiner Teilnehmer"
              + " (POST /api/v1/tasks/{id}/participants) lassen die Stummschaltung bestehen.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Marker erfolgreich gesetzt."),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = ParticipantApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.NOT_FOUND_MUTE)))
  })
  @PutMapping("/{id}/mute")
  @HateoasLinkTarget(TaskHateoas.Targets.MUTE)
  public ResponseEntity<Void> mute(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description = "Gewuenschter Zielzustand des Mute-Markers.",
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = MarkerInput.class),
                      examples = {
                        @ExampleObject(
                            name = "stummschalten",
                            summary = "Aufgabe stummschalten",
                            description =
                                "Benachrichtigungen dieser Aufgabe werden unterdrueckt, ausser"
                                    + " MENTION, REPLY, ASSIGN und HANDOVER.",
                            value = "{\"value\":true}"),
                        @ExampleObject(
                            name = "laut-schalten",
                            summary = "Stummschaltung aufheben",
                            description = "Die Aufgabe benachrichtigt wieder wie gewohnt.",
                            value = "{\"value\":false}")
                      }))
          @RequestBody
          final MarkerInput input) {
    markerService.setMuted(id, input.value());
    return ResponseEntity.noContent().build();
  }
}
