package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.participant.model.ExitInput;
import de.brasi.nova.taskmanagement.participant.model.ParticipantAddInput;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * Handgeschriebener Facade-Controller fuer Assignee-Verwaltung und Austritt (arch-v2 §7).
 *
 * <p>Jeder Handler traegt eine {@link RequiresPermission}-Annotation (Pflicht gemaess arch-v2 §8).
 * Actor-UUID kommt ausschliesslich aus dem Security-Context, NIEMALS aus Client-Eingabedaten.
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
   * <p>Neue Assignees erhalten eine ASSIGN-Notification; abgewaehlte werden mit
   * EXIT_KIND=UNSUBSCRIBED markiert.
   *
   * @param id    externe UUID der Aufgabe
   * @param input Ziel-Assignee-Menge
   * @return 204 No Content
   */
  @Operation(
      summary = "Assignees aktualisieren",
      description = "Ersetzt die aktuelle Assignee-Menge durch die gewuenschte Ziel-Menge (PUT-Set-Semantik)."
          + " Neu zugewiesene Benutzer erhalten eine ASSIGN-Notification."
          + " Der Creator kann nicht abgewaehlt werden.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Assignees erfolgreich aktualisiert."),
      @ApiResponse(responseCode = "400", description = "Ungueltige Eingabe (z.B. unbekannte Benutzer-UUID, null-Elemente)."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_WRITE-Berechtigung."),
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden oder fremder Mandant (neutral maskiert).")
  })
  @PutMapping("/{id}/assignees")
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> updateAssignees(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable final UUID id,
      @RequestBody final AssigneeUpdateInput input) {
    assigneeService.updateAssignees(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Aktueller Akteur verlaesst die Aufgabe (HANDOVER oder UNSUBSCRIBED).
   *
   * <p>Bei HANDOVER wird eine HANDOVER-Notification emittiert.
   *
   * @param id    externe UUID der Aufgabe
   * @param input Austritts-Art
   * @return 204 No Content
   */
  @Operation(
      summary = "Aufgabe verlassen",
      description = "Der angemeldete Benutzer verlaesst die Aufgabe."
          + " exitKind: HANDOVER (Aufgabe uebergeben) oder UNSUBSCRIBED (Aufgabe abgemeldet)."
          + " Der Creator kann die Aufgabe nicht verlassen (409).")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Aufgabe erfolgreich verlassen."),
      @ApiResponse(responseCode = "400", description = "Benutzer ist kein aktiver Teilnehmer der Aufgabe."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_WRITE-Berechtigung."),
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden oder fremder Mandant (neutral maskiert)."),
      @ApiResponse(responseCode = "409", description = "Der Creator kann die Aufgabe nicht verlassen.")
  })
  @PostMapping("/{id}/exit")
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> exit(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable final UUID id,
      @RequestBody final ExitInput input) {
    assigneeService.exit(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Fuegt Benutzer additiv als Teilnehmer der Aufgabe hinzu (idempotent).
   *
   * <p>Verleiht ausschliesslich die Teilnehmer-Rolle (PARTICIPANT_FLAG), NIE die Assignee-Rolle.
   * Bereits aktive Teilnehmer werden uebersprungen; ein leeres Array ist ein No-Op.
   *
   * @param id    externe UUID der Aufgabe
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
      @ApiResponse(responseCode = "204", description = "Teilnehmer erfolgreich hinzugefuegt (oder No-Op)."),
      @ApiResponse(responseCode = "400", description = "Ungueltige Eingabe (unbekannte Benutzer-UUID, null-Elemente, mehr als 100)."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_WRITE-Berechtigung."),
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden oder fremder Mandant (neutral maskiert).")
  })
  @PostMapping("/{id}/participants")
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> addParticipants(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable final UUID id,
      @RequestBody final ParticipantAddInput input) {
    assigneeService.addParticipants(id, input);
    return ResponseEntity.noContent().build();
  }
}
