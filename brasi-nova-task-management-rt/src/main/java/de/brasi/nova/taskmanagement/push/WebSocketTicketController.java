package de.brasi.nova.taskmanagement.push;

import de.brasi.nova.taskmanagement.push.model.WsTicketResponse;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.PermissionEvaluator;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Controller fuer die Ausgabe von WebSocket-Einmal-Tickets (arch-v2 §6).
 *
 * <p>{@code POST /api/v1/ws-tickets} ({@link RequiresPermission}{@code (TASK_READ)}): erzeugt ein
 * kurzlebiges Ticket ausschliesslich aus dem aktuellen Principal/Tenant
 * ({@link CurrentActor#require(UserAccessor)}), NIE aus Client-Eingabedaten. Der WS-Handshake
 * loest es ein; so landet kein JWT in der WS-URL.
 */
@RestController
@RequestMapping("/api/v1/ws-tickets")
public class WebSocketTicketController {

  private final WebSocketTicketService ticketService;
  private final UserAccessor userAccessor;
  private final PermissionEvaluator permissionEvaluator;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public WebSocketTicketController(
      final WebSocketTicketService ticketService,
      final UserAccessor userAccessor,
      final PermissionEvaluator permissionEvaluator) {
    this.ticketService = ticketService;
    this.userAccessor = userAccessor;
    this.permissionEvaluator = permissionEvaluator;
  }

  /**
   * Stellt ein Einmal-Ticket fuer den angemeldeten Benutzer aus.
   *
   * @return 200 OK mit {@link WsTicketResponse}
   */
  @Operation(
      summary = "WebSocket-Ticket ausstellen",
      description = "Stellt ein kurzlebiges Einmal-Ticket fuer den WebSocket-Handshake aus."
          + " Das Ticket wird bei WS /ws?ticket=... eingeloest und danach verworfen (einmalig, TTL ca. 30s)."
          + " Die NOTIFICATION_READ-Capability wird zum Ausgabezeitpunkt am Ticket festgeschrieben:"
          + " nur Sessions aus einem Ticket MIT NOTIFICATION_READ erhalten Notification-Pushes"
          + " (snapshot-invalidated-Signale gehen task-level an alle TASK_READ-Sessions)."
          + " Hinweis: ein spaeterer Rechteentzug wirkt erst beim naechsten Ticket/Reconnect -"
          + " eine bereits offene WS-Session behaelt ihre Capability bis zum Verbindungsende.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Ticket erfolgreich ausgestellt.",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = WsTicketResponse.class),
              examples = @ExampleObject(value = "{"
                  + "\"ticket\": \"abc123XYZ_opakes-token-hier\","
                  + "\"expiresAt\": \"2026-10-18T08:00:30Z\""
                  + "}"))),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_READ-Berechtigung.")
  })
  @PostMapping
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<WsTicketResponse> issueTicket() {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    // Autz-Grenze: NOTIFICATION_READ exakt + fail-closed auswerten (aspekt-treu zu
    // @RequiresPermission) und am Ticket festschreiben; das Push-Routing gatet darueber
    // Notification-Pushes (snapshot-invalidated bleibt task-level fuer alle TASK_READ-Sessions).
    final boolean notificationRead =
        permissionEvaluator.hasExactAuthority(TaskManagementPermissions.NOTIFICATION_READ);
    return ResponseEntity.ok(
        ticketService.issue(actor.tenant().id(), actor.user().id(), notificationRead));
  }
}
