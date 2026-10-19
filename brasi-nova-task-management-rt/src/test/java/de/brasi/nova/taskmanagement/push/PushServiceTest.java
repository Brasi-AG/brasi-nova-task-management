package de.brasi.nova.taskmanagement.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/** Unit-Tests fuer {@link PushService} (Routing, Wire-JSON, snapshot-invalidated, Best-Effort). */
class PushServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER_A = UUID.randomUUID();
  private static final UUID USER_B = UUID.randomUUID();

  private PushRegistry registry;
  private ObjectMapper objectMapper;
  private PushService service;

  @BeforeEach
  void setUp() {
    registry = new PushRegistry();
    // Spiegelt die Spring-Boot-ObjectMapper-Konfiguration: JavaTimeModule + ISO-8601 Instants
    // (Boot disabled WRITE_DATES_AS_TIMESTAMPS; der rohe Builder nicht -> explizit setzen).
    objectMapper =
        Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    service = new PushService(registry, objectMapper);
  }

  private WebSocketSession openSession(final String id) {
    final WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    when(session.isOpen()).thenReturn(true);
    return session;
  }

  @Test
  @DisplayName("push_sendet_notification_envelope_als_json_an_empfaenger_sessions")
  void push_sendet_notification_envelope_als_json_an_empfaenger_sessions() throws Exception {
    final WebSocketSession session = openSession("s-a");
    registry.register(TENANT_ID, USER_A, session, true);

    final UUID notifId = UUID.randomUUID();
    final NotificationPushEnvelope envelope =
        NotificationPushEnvelope.of(notifId, Instant.parse("2026-10-18T08:30:00Z"), "ASSIGN",
            "INFO", "Dir wurde eine Aufgabe zugewiesen", "Rechnung pruefen");

    service.push(TENANT_ID, USER_A, envelope);

    final ArgumentCaptor<WebSocketMessage<?>> captor = messageCaptor();
    verify(session).sendMessage(captor.capture());
    final JsonNode json = objectMapper.readTree(((TextMessage) captor.getValue()).getPayload());

    // T9-Wire-Vertrag
    assertThat(json.get("type").asText()).isEqualTo("notification");
    assertThat(json.get("version").asText()).isEqualTo("1");
    assertThat(json.get("notificationId").asText()).isEqualTo(notifId.toString());
    assertThat(json.get("category").asText()).isEqualTo("ASSIGN");
    assertThat(json.get("severity").asText()).isEqualTo("INFO");
    assertThat(json.get("timestamp").asText()).isEqualTo("2026-10-18T08:30:00Z");
    assertThat(json.get("payload").get("text").asText())
        .isEqualTo("Dir wurde eine Aufgabe zugewiesen");
    assertThat(json.get("payload").get("preview").asText()).isEqualTo("Rechnung pruefen");
  }

  @Test
  @DisplayName("push_nicht_an_fremden_empfaenger")
  void push_nicht_an_fremden_empfaenger() throws Exception {
    final WebSocketSession sessionA = openSession("s-a");
    registry.register(TENANT_ID, USER_A, sessionA, true);

    service.push(
        TENANT_ID, USER_B,
        NotificationPushEnvelope.of(UUID.randomUUID(), Instant.now(), "ASSIGN", null, "x", null));

    verify(sessionA, never()).sendMessage(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("push_geschlossene_session_wird_uebersprungen")
  void push_geschlossene_session_wird_uebersprungen() throws Exception {
    final WebSocketSession closed = mock(WebSocketSession.class);
    when(closed.getId()).thenReturn("s-closed");
    when(closed.isOpen()).thenReturn(false);
    registry.register(TENANT_ID, USER_A, closed, true);

    service.push(
        TENANT_ID, USER_A,
        NotificationPushEnvelope.of(UUID.randomUUID(), Instant.now(), "ASSIGN", null, "x", null));

    verify(closed, never()).sendMessage(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("push_nicht_an_session_ohne_notification_read_aber_an_berechtigte")
  void push_gated_by_notification_read() throws Exception {
    // Gleicher Empfaenger, zwei Sessions: eine berechtigt, eine nicht.
    final WebSocketSession capable = openSession("s-cap");
    final WebSocketSession notCapable = openSession("s-nocap");
    registry.register(TENANT_ID, USER_A, capable, true);
    registry.register(TENANT_ID, USER_A, notCapable, false);

    service.push(
        TENANT_ID, USER_A,
        NotificationPushEnvelope.of(UUID.randomUUID(), Instant.now(), "ASSIGN", null, "x", null));

    // Nur die berechtigte Session erhaelt den Notification-Push (fail-closed Gate).
    verify(capable).sendMessage(org.mockito.ArgumentMatchers.any());
    verify(notCapable, never()).sendMessage(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("snapshot_invalidated_auch_an_session_ohne_notification_read")
  void snapshot_invalidated_ignoriert_das_gate() throws Exception {
    // Eine NICHT notification-berechtigte Session muss das task-level snapshot-invalidated-Signal
    // trotzdem erhalten (Gate gilt nur fuer Notification-Pushes).
    final WebSocketSession notCapable = openSession("s-nocap");
    registry.register(TENANT_ID, USER_A, notCapable, false);

    service.pushSnapshotInvalidated(TENANT_ID, List.of(USER_A));

    assertSnapshotInvalidated(notCapable);
  }

  @Test
  @DisplayName("pushSnapshotInvalidated_sendet_an_alle_userRefs")
  void pushSnapshotInvalidated_sendet_an_alle_userRefs() throws Exception {
    final WebSocketSession sessionA = openSession("s-a");
    final WebSocketSession sessionB = openSession("s-b");
    // Bewusst OHNE NOTIFICATION_READ: snapshot-invalidated ist task-level und ignoriert das Gate.
    registry.register(TENANT_ID, USER_A, sessionA, false);
    registry.register(TENANT_ID, USER_B, sessionB, false);

    service.pushSnapshotInvalidated(TENANT_ID, List.of(USER_A, USER_B));

    assertSnapshotInvalidated(sessionA);
    assertSnapshotInvalidated(sessionB);
  }

  @Test
  @DisplayName("pushSnapshotInvalidated_leere_menge_ist_noop")
  void pushSnapshotInvalidated_leere_menge_ist_noop() {
    // Kein Empfaenger -> kein Fehler, keine Zustellung
    service.pushSnapshotInvalidated(TENANT_ID, List.of());
  }

  private void assertSnapshotInvalidated(final WebSocketSession session) throws Exception {
    final ArgumentCaptor<WebSocketMessage<?>> captor = messageCaptor();
    verify(session).sendMessage(captor.capture());
    final JsonNode json = objectMapper.readTree(((TextMessage) captor.getValue()).getPayload());
    assertThat(json.get("type").asText()).isEqualTo("snapshot-invalidated");
    assertThat(json.get("version").asText()).isEqualTo("1");
    assertThat(json.get("timestamp").asText()).isNotBlank();
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<WebSocketMessage<?>> messageCaptor() {
    return ArgumentCaptor.forClass((Class<WebSocketMessage<?>>) (Class<?>) WebSocketMessage.class);
  }
}
