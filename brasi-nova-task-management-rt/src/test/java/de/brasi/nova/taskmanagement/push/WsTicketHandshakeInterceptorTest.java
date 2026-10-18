package de.brasi.nova.taskmanagement.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.push.model.WsTicketResponse;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

/** Unit-Tests fuer {@link WsTicketHandshakeInterceptor} (gueltig/attribute, 401, replay, expiry). */
class WsTicketHandshakeInterceptorTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER_REF = UUID.randomUUID();
  private static final long TTL_SECONDS = 30L;

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(final Instant start) {
      this.now = start;
    }

    private void advance(final Duration delta) {
      this.now = this.now.plus(delta);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }
  }

  private ServerHttpRequest requestWithUri(final String uri) {
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getURI()).thenReturn(URI.create(uri));
    return request;
  }

  @Test
  @DisplayName("gueltiges_ticket_setzt_attribute_und_erlaubt_handshake")
  void gueltiges_ticket_setzt_attribute_und_erlaubt_handshake() {
    final WebSocketTicketService service =
        new WebSocketTicketService(TTL_SECONDS, Clock.systemUTC());
    final WsTicketHandshakeInterceptor interceptor = new WsTicketHandshakeInterceptor(service);
    final WsTicketResponse ticket = service.issue(TENANT_ID, USER_REF);

    final ServerHttpRequest request =
        requestWithUri("http://host/task-management/ws?ticket=" + ticket.ticket());
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final Map<String, Object> attributes = new HashMap<>();

    final boolean allowed = interceptor.beforeHandshake(request, response, null, attributes);

    assertThat(allowed).isTrue();
    assertThat(attributes.get(TaskWebSocketHandler.ATTR_TENANT_ID)).isEqualTo(TENANT_ID);
    assertThat(attributes.get(TaskWebSocketHandler.ATTR_USER_REF)).isEqualTo(USER_REF);
  }

  @Test
  @DisplayName("fehlendes_ticket_liefert_401_und_bricht_ab")
  void fehlendes_ticket_liefert_401_und_bricht_ab() {
    final WebSocketTicketService service =
        new WebSocketTicketService(TTL_SECONDS, Clock.systemUTC());
    final WsTicketHandshakeInterceptor interceptor = new WsTicketHandshakeInterceptor(service);

    final ServerHttpRequest request = requestWithUri("http://host/task-management/ws");
    final ServerHttpResponse response = mock(ServerHttpResponse.class);

    final boolean allowed = interceptor.beforeHandshake(request, response, null, new HashMap<>());

    assertThat(allowed).isFalse();
    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("verbrauchtes_ticket_replay_liefert_401")
  void verbrauchtes_ticket_replay_liefert_401() {
    final WebSocketTicketService service =
        new WebSocketTicketService(TTL_SECONDS, Clock.systemUTC());
    final WsTicketHandshakeInterceptor interceptor = new WsTicketHandshakeInterceptor(service);
    final WsTicketResponse ticket = service.issue(TENANT_ID, USER_REF);
    final String uri = "http://host/task-management/ws?ticket=" + ticket.ticket();

    // Erster Handshake verbraucht das Ticket
    assertThat(interceptor.beforeHandshake(requestWithUri(uri), mock(ServerHttpResponse.class),
        null, new HashMap<>())).isTrue();

    // Replay mit demselben Ticket -> 401
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final boolean allowed =
        interceptor.beforeHandshake(requestWithUri(uri), response, null, new HashMap<>());
    assertThat(allowed).isFalse();
    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("abgelaufenes_ticket_liefert_401")
  void abgelaufenes_ticket_liefert_401() {
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
    final WebSocketTicketService service = new WebSocketTicketService(TTL_SECONDS, clock);
    final WsTicketHandshakeInterceptor interceptor = new WsTicketHandshakeInterceptor(service);
    final WsTicketResponse ticket = service.issue(TENANT_ID, USER_REF);

    clock.advance(Duration.ofSeconds(TTL_SECONDS + 1));

    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final boolean allowed = interceptor.beforeHandshake(
        requestWithUri("http://host/task-management/ws?ticket=" + ticket.ticket()),
        response, null, new HashMap<>());

    assertThat(allowed).isFalse();
    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
  }
}
