package de.brasi.nova.taskmanagement.configuration;

import de.brasi.nova.taskmanagement.push.TaskWebSocketHandler;
import de.brasi.nova.taskmanagement.push.WsTicketHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registriert den rohen WebSocket-Endpunkt {@code /ws} (arch-v2 §6, single-instance in 1a).
 *
 * <p>Der {@link WsTicketHandshakeInterceptor} authentifiziert den Handshake ueber ein opakes
 * Einmal-Ticket (kein JWT in der URL). {@code /ws} ist bewusst ticket-gated statt JWT-gated; die
 * REST-Schicht (inkl. {@code POST /api/v1/ws-tickets}) bleibt JWT-/Permission-geschuetzt.
 *
 * <p>Origin-Patterns sind konfigurierbar ({@code task.websocket.allowed-origin-patterns}); in 1a
 * liegt der Service hinter dem Gateway, das die Origin-Pruefung durchsetzt.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final TaskWebSocketHandler taskWebSocketHandler;
  private final WsTicketHandshakeInterceptor handshakeInterceptor;
  private final String[] allowedOriginPatterns;

  /** Konstruktor-Injektion. */
  public WebSocketConfig(
      final TaskWebSocketHandler taskWebSocketHandler,
      final WsTicketHandshakeInterceptor handshakeInterceptor,
      @Value("${task.websocket.allowed-origin-patterns:*}") final String[] allowedOriginPatterns) {
    this.taskWebSocketHandler = taskWebSocketHandler;
    this.handshakeInterceptor = handshakeInterceptor;
    this.allowedOriginPatterns = allowedOriginPatterns.clone();
  }

  @Override
  public void registerWebSocketHandlers(final WebSocketHandlerRegistry registry) {
    registry
        .addHandler(taskWebSocketHandler, "/ws")
        .addInterceptors(handshakeInterceptor)
        .setAllowedOriginPatterns(allowedOriginPatterns);
  }
}
