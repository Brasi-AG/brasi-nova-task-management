package de.brasi.nova.taskmanagement.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.brasi.nova.taskmanagement.push.model.SnapshotInvalidatedEnvelope;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Serialisiert Envelopes und stellt sie an die offenen WebSocket-Sessions des Empfaengers zu
 * (arch-v2 §6). Best-Effort: fehlende Sessions oder IO-Fehler einzelner Sessions brechen den
 * Push nicht ab (kein Einfluss auf die bereits committete DB-Transaktion).
 *
 * <p>Der {@link Instant}-Zeitstempel im {@code snapshot-invalidated}-Envelope wird bewusst zur
 * Sendezeit erzeugt (das Signal traegt keine Persistenz-Zeit).
 */
@Service
public class PushService {

  private static final Logger log = LoggerFactory.getLogger(PushService.class);

  private final PushRegistry registry;
  private final ObjectMapper objectMapper;

  /** Konstruktor-Injektion. */
  public PushService(final PushRegistry registry, final ObjectMapper objectMapper) {
    this.registry = registry;
    this.objectMapper = objectMapper;
  }

  /**
   * Sendet einen Notification-Envelope an die NOTIFICATION_READ-berechtigten Sessions des
   * Empfaengers (Gate). Sessions ohne Capability werden ausgelassen (fail-closed): so setzt sich
   * NOTIFICATION_READ auch im Push-Kanal durch, konsistent zu {@code GET /api/v1/notifications}.
   *
   * @param tenantId     interner Mandanten-Schluessel (Routing)
   * @param recipientRef UUID des Empfaengers (Routing)
   * @param envelope     serialisierbarer Notification-Envelope ({@code NotificationPushEnvelope})
   */
  public void push(final long tenantId, final UUID recipientRef, final Object envelope) {
    final TextMessage message = serialize(envelope);
    if (message == null) {
      return;
    }
    sendTo(registry.notificationSessionsFor(tenantId, recipientRef), message, recipientRef, tenantId);
  }

  /**
   * Sendet ein {@code snapshot-invalidated}-Signal an alle angegebenen Empfaenger (EINE
   * Serialisierung, an jeden Empfaenger zugestellt).
   *
   * @param tenantId interner Mandanten-Schluessel
   * @param userRefs betroffene Benutzer
   */
  public void pushSnapshotInvalidated(final long tenantId, final Collection<UUID> userRefs) {
    if (userRefs == null || userRefs.isEmpty()) {
      return;
    }
    final TextMessage message = serialize(SnapshotInvalidatedEnvelope.at(Instant.now()));
    if (message == null) {
      return;
    }
    for (final UUID userRef : userRefs) {
      // snapshot-invalidated ist task-level: an ALLE Sessions, unabhaengig von NOTIFICATION_READ.
      sendTo(registry.sessionsFor(tenantId, userRef), message, userRef, tenantId);
    }
  }

  /** Serialisiert den Envelope zu einer {@link TextMessage}; null bei Serialisierungsfehler. */
  private TextMessage serialize(final Object envelope) {
    try {
      return new TextMessage(objectMapper.writeValueAsString(envelope));
    } catch (JsonProcessingException e) {
      log.error("PushService: Envelope-Serialisierung fehlgeschlagen ({}).", envelope, e);
      return null;
    }
  }

  /** Stellt die Nachricht an die uebergebenen Sessions zu (Best-Effort je Session). */
  private void sendTo(
      final Collection<WebSocketSession> sessions,
      final TextMessage message,
      final UUID recipientRef,
      final long tenantId) {
    for (final WebSocketSession session : sessions) {
      if (!session.isOpen()) {
        continue;
      }
      try {
        session.sendMessage(message);
      } catch (IOException | IllegalStateException e) {
        log.warn(
            "PushService: Zustellung an Session {} (Empfaenger {}/Mandant {}) fehlgeschlagen.",
            session.getId(),
            recipientRef,
            tenantId,
            e);
      }
    }
  }
}
