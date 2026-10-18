package de.brasi.nova.taskmanagement.push;

import de.brasi.nova.taskmanagement.push.model.WsTicketResponse;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
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

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public WebSocketTicketController(
      final WebSocketTicketService ticketService, final UserAccessor userAccessor) {
    this.ticketService = ticketService;
    this.userAccessor = userAccessor;
  }

  /**
   * Stellt ein Einmal-Ticket fuer den angemeldeten Benutzer aus.
   *
   * @return 200 OK mit {@link WsTicketResponse}
   */
  @PostMapping
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<WsTicketResponse> issueTicket() {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return ResponseEntity.ok(ticketService.issue(actor.tenant().id(), actor.user().id()));
  }
}
