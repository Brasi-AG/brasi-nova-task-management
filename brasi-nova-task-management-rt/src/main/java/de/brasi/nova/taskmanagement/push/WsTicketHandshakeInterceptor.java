package de.brasi.nova.taskmanagement.push;

import de.brasi.nova.taskmanagement.push.WebSocketTicketService.TicketPrincipal;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Handshake-Interceptor fuer {@code WS /ws} (arch-v2 §6, Codex-BLOCKER 7).
 *
 * <p>Liest den {@code ticket}-Query-Parameter, loest ihn atomar + einmalig ueber
 * {@link WebSocketTicketService#consume(String)} ein und legt {@code tenantId}/{@code userRef} als
 * Handshake-Attribute ab (vom {@link TaskWebSocketHandler} gelesen). Ein fehlendes, unbekanntes,
 * bereits verbrauchtes oder abgelaufenes Ticket fuehrt zu HTTP 401 und bricht den Handshake ab
 * ({@code false}). <b>Kein JWT in der URL</b> - nur das opake Einmal-Ticket.
 */
@Component
public class WsTicketHandshakeInterceptor implements HandshakeInterceptor {

  private static final Logger log = LoggerFactory.getLogger(WsTicketHandshakeInterceptor.class);

  private static final String TICKET_PARAM = "ticket";

  private final WebSocketTicketService ticketService;

  /** Konstruktor-Injektion. */
  public WsTicketHandshakeInterceptor(final WebSocketTicketService ticketService) {
    this.ticketService = ticketService;
  }

  @Override
  public boolean beforeHandshake(
      final ServerHttpRequest request,
      final ServerHttpResponse response,
      final WebSocketHandler wsHandler,
      final Map<String, Object> attributes) {

    final String ticket =
        UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams()
            .getFirst(TICKET_PARAM);

    final Optional<TicketPrincipal> principal = ticketService.consume(ticket);
    if (principal.isEmpty()) {
      log.warn("WsTicketHandshakeInterceptor: Handshake ohne gueltiges Ticket abgelehnt (401).");
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }

    attributes.put(TaskWebSocketHandler.ATTR_TENANT_ID, principal.get().tenantId());
    attributes.put(TaskWebSocketHandler.ATTR_USER_REF, principal.get().userRef());
    return true;
  }

  @Override
  public void afterHandshake(
      final ServerHttpRequest request,
      final ServerHttpResponse response,
      final WebSocketHandler wsHandler,
      final Exception exception) {
    // Kein Nachbearbeitungsbedarf.
  }
}
