package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.participant.model.MarkerInput;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
   * @param id    externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = angepinnt, {@code false} = nicht angepinnt)
   * @return 204 No Content
   */
  @Operation(
      summary = "Pin-Marker setzen",
      description = "Setzt den persoenlichen Pin-Marker des angemeldeten Benutzers fuer eine Aufgabe."
          + " true = angepinnt, false = nicht angepinnt.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Marker erfolgreich gesetzt."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @PutMapping("/{id}/pin")
  public ResponseEntity<Void> pin(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable final UUID id,
      @RequestBody final MarkerInput input) {
    markerService.setPinned(id, input.value());
    return ResponseEntity.noContent().build();
  }

  /**
   * Setzt den Flagged-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * @param id    externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = markiert, {@code false} = nicht markiert)
   * @return 204 No Content
   */
  @Operation(
      summary = "Flag-Marker setzen",
      description = "Setzt den persoenlichen Flag-Marker des angemeldeten Benutzers fuer eine Aufgabe."
          + " true = markiert, false = nicht markiert.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Marker erfolgreich gesetzt."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @PutMapping("/{id}/flag")
  public ResponseEntity<Void> flag(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable final UUID id,
      @RequestBody final MarkerInput input) {
    markerService.setFlagged(id, input.value());
    return ResponseEntity.noContent().build();
  }

  /**
   * Setzt den Muted-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * @param id    externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = stummgeschaltet, {@code false} = nicht stumm)
   * @return 204 No Content
   */
  @Operation(
      summary = "Mute-Marker setzen",
      description = "Setzt den persoenlichen Mute-Marker des angemeldeten Benutzers fuer eine Aufgabe."
          + " true = stummgeschaltet, false = nicht stumm.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Marker erfolgreich gesetzt."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @PutMapping("/{id}/mute")
  public ResponseEntity<Void> mute(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable final UUID id,
      @RequestBody final MarkerInput input) {
    markerService.setMuted(id, input.value());
    return ResponseEntity.noContent().build();
  }
}
