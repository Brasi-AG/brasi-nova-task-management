package de.brasi.nova.taskmanagement.push;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket-Handler fuer den push-only Task-Kanal (arch-v2 §6).
 *
 * <p>Bei Verbindungsaufbau werden {@code tenantId}/{@code userRef} aus den (durch
 * {@link WsTicketHandshakeInterceptor} gesetzten) Handshake-Attributen gelesen; fehlen sie, wird
 * die Session mit {@link CloseStatus#POLICY_VIOLATION} geschlossen. Die Session wird vor dem
 * Registrieren in einen {@link ConcurrentWebSocketSessionDecorator} gewickelt, damit parallele
 * Sends serialisiert werden. Eingehende Nachrichten werden ignoriert (reiner Push-Kanal).
 */
@Component
public class TaskWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(TaskWebSocketHandler.class);

  /** Handshake-Attribut: interner Mandanten-Schluessel ({@link Long}). */
  public static final String ATTR_TENANT_ID = "taskmgmt.tenantId";

  /** Handshake-Attribut: Benutzer-UUID ({@link UUID}). */
  public static final String ATTR_USER_REF = "taskmgmt.userRef";

  private final PushRegistry registry;
  private final int sendTimeLimitMs;
  private final int sendBufferSizeBytes;

  /** Konstruktor-Injektion inkl. Decorator-Grenzen. */
  public TaskWebSocketHandler(
      final PushRegistry registry,
      @Value("${task.websocket.send-time-limit-ms:10000}") final int sendTimeLimitMs,
      @Value("${task.websocket.send-buffer-size-bytes:524288}") final int sendBufferSizeBytes) {
    this.registry = registry;
    this.sendTimeLimitMs = sendTimeLimitMs;
    this.sendBufferSizeBytes = sendBufferSizeBytes;
  }

  @Override
  public void afterConnectionEstablished(final WebSocketSession session) throws Exception {
    final Object tenantAttr = session.getAttributes().get(ATTR_TENANT_ID);
    final Object userAttr = session.getAttributes().get(ATTR_USER_REF);
    if (!(tenantAttr instanceof Long tenantId) || !(userAttr instanceof UUID userRef)) {
      // Darf regulaer nicht passieren (Interceptor setzt beide); fail-closed.
      log.warn("TaskWebSocketHandler: Session {} ohne Principal-Attribute -> geschlossen.",
          session.getId());
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    final WebSocketSession decorated =
        new ConcurrentWebSocketSessionDecorator(session, sendTimeLimitMs, sendBufferSizeBytes);
    registry.register(tenantId, userRef, decorated);
    log.debug("TaskWebSocketHandler: Session {} registriert (Mandant {}, Benutzer {}).",
        session.getId(), tenantId, userRef);
  }

  @Override
  public void afterConnectionClosed(final WebSocketSession session, final CloseStatus status) {
    registry.unregister(session.getId());
    log.debug("TaskWebSocketHandler: Session {} entfernt ({}).", session.getId(), status);
  }
}
