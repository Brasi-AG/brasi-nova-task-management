package de.brasi.nova.taskmanagement.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** Unit-Tests fuer {@link TaskWebSocketHandler} (Decorator-Registrierung + Unregister + fail-closed). */
class TaskWebSocketHandlerTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER_REF = UUID.randomUUID();

  private PushRegistry registry;
  private TaskWebSocketHandler handler;

  @BeforeEach
  void setUp() {
    registry = new PushRegistry();
    handler = new TaskWebSocketHandler(registry, 10000, 524288);
  }

  private WebSocketSession sessionWith(final String id, final Map<String, Object> attributes) {
    final WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    when(session.getAttributes()).thenReturn(attributes);
    return session;
  }

  @Test
  @DisplayName("afterConnectionEstablished_registriert_ConcurrentWebSocketSessionDecorator")
  void afterConnectionEstablished_registriert_concurrent_decorator() throws Exception {
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put(TaskWebSocketHandler.ATTR_TENANT_ID, TENANT_ID);
    attrs.put(TaskWebSocketHandler.ATTR_USER_REF, USER_REF);
    final WebSocketSession session = sessionWith("s-1", attrs);

    handler.afterConnectionEstablished(session);

    final Collection<WebSocketSession> sessions = registry.sessionsFor(TENANT_ID, USER_REF);
    assertThat(sessions).hasSize(1);
    // parallele_sends_serialisiert: registrierte Session ist der serialisierende Decorator
    assertThat(sessions.iterator().next()).isInstanceOf(ConcurrentWebSocketSessionDecorator.class);
  }

  @Test
  @DisplayName("afterConnectionEstablished_mit_notification_read_attr_registriert_berechtigt")
  void afterConnectionEstablished_mit_notification_read_registriert_berechtigt() throws Exception {
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put(TaskWebSocketHandler.ATTR_TENANT_ID, TENANT_ID);
    attrs.put(TaskWebSocketHandler.ATTR_USER_REF, USER_REF);
    attrs.put(TaskWebSocketHandler.ATTR_NOTIFICATION_READ, Boolean.TRUE);
    final WebSocketSession session = sessionWith("s-cap", attrs);

    handler.afterConnectionEstablished(session);

    // Berechtigt -> im Notification-View UND im Gesamt-View.
    assertThat(registry.notificationSessionsFor(TENANT_ID, USER_REF)).hasSize(1);
    assertThat(registry.sessionsFor(TENANT_ID, USER_REF)).hasSize(1);
  }

  @Test
  @DisplayName("afterConnectionEstablished_ohne_notification_read_attr_fail_closed_nicht_im_notif_view")
  void afterConnectionEstablished_ohne_notification_read_attr_fail_closed() throws Exception {
    // Nur Principal-Attribute, KEIN ATTR_NOTIFICATION_READ -> fail-closed false.
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put(TaskWebSocketHandler.ATTR_TENANT_ID, TENANT_ID);
    attrs.put(TaskWebSocketHandler.ATTR_USER_REF, USER_REF);
    final WebSocketSession session = sessionWith("s-nocap", attrs);

    handler.afterConnectionEstablished(session);

    // Registriert (sessionsFor), aber NICHT notification-berechtigt (notificationSessionsFor leer).
    assertThat(registry.sessionsFor(TENANT_ID, USER_REF)).hasSize(1);
    assertThat(registry.notificationSessionsFor(TENANT_ID, USER_REF)).isEmpty();
  }

  @Test
  @DisplayName("afterConnectionEstablished_ohne_principal_attribute_schliesst_fail_closed")
  void afterConnectionEstablished_ohne_attribute_schliesst() throws Exception {
    final WebSocketSession session = sessionWith("s-2", new HashMap<>());

    handler.afterConnectionEstablished(session);

    verify(session).close(CloseStatus.POLICY_VIOLATION);
    assertThat(registry.sessionsFor(TENANT_ID, USER_REF)).isEmpty();
  }

  @Test
  @DisplayName("afterConnectionClosed_entfernt_session_per_id")
  void afterConnectionClosed_entfernt_session() throws Exception {
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put(TaskWebSocketHandler.ATTR_TENANT_ID, TENANT_ID);
    attrs.put(TaskWebSocketHandler.ATTR_USER_REF, USER_REF);
    final WebSocketSession session = sessionWith("s-3", attrs);

    handler.afterConnectionEstablished(session);
    assertThat(registry.sessionsFor(TENANT_ID, USER_REF)).hasSize(1);

    // Schliessen liefert die undekorierte Session (gleiche getId())
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    assertThat(registry.sessionsFor(TENANT_ID, USER_REF)).isEmpty();
  }
}
