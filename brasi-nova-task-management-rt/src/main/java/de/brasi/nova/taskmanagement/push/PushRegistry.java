package de.brasi.nova.taskmanagement.push;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Thread-sichere Registry offener WebSocket-Sessions je Empfaenger (arch-v2 §6, single-instance).
 *
 * <p>Schluessel = (Mandant, Benutzer); ein Benutzer kann mehrere Sessions haben (mehrere Tabs).
 * Die registrierte Session ist bereits ein
 * {@link org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator} (der Handler
 * dekoriert vor dem Registrieren), damit parallele Sends auf dieselbe Session serialisiert werden.
 *
 * <p>Unregistrierung laeuft ueber die Session-ID (beim Schliessen liefert Spring die undekorierte
 * Session; deren {@code getId()} ist identisch mit der dekorierten).
 */
@Component
public class PushRegistry {

  /** (Mandant, Benutzer) als Registry-Schluessel. */
  private record Key(long tenantId, UUID userRef) {}

  /** Key -> (SessionId -> Session). */
  private final ConcurrentHashMap<Key, ConcurrentHashMap<String, WebSocketSession>> byKey =
      new ConcurrentHashMap<>();

  /** SessionId -> Key (fuer O(1)-Unregister beim Schliessen). */
  private final ConcurrentHashMap<String, Key> keyBySession = new ConcurrentHashMap<>();

  /**
   * Registriert eine (dekorierte) Session fuer den Empfaenger.
   *
   * @param tenantId interner Mandanten-Schluessel
   * @param userRef  UUID des Benutzers
   * @param session  dekorierte WebSocket-Session
   */
  public void register(final long tenantId, final UUID userRef, final WebSocketSession session) {
    final Key key = new Key(tenantId, userRef);
    byKey.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(session.getId(), session);
    keyBySession.put(session.getId(), key);
  }

  /**
   * Entfernt eine Session anhand ihrer ID (beim Schliessen).
   *
   * @param sessionId Session-ID der zu entfernenden Session
   */
  public void unregister(final String sessionId) {
    final Key key = keyBySession.remove(sessionId);
    if (key == null) {
      return;
    }
    final ConcurrentHashMap<String, WebSocketSession> sessions = byKey.get(key);
    if (sessions != null) {
      sessions.remove(sessionId);
      // Leere Buckets entfernen (nur wenn weiterhin leer, atomar)
      byKey.computeIfPresent(key, (k, v) -> v.isEmpty() ? null : v);
    }
  }

  /**
   * Liefert eine Momentaufnahme der offenen Sessions des Empfaengers.
   *
   * @param tenantId interner Mandanten-Schluessel
   * @param userRef  UUID des Benutzers
   * @return Liste der Sessions (leer, wenn keine Session offen)
   */
  public Collection<WebSocketSession> sessionsFor(final long tenantId, final UUID userRef) {
    final ConcurrentHashMap<String, WebSocketSession> sessions = byKey.get(new Key(tenantId, userRef));
    return sessions == null ? List.of() : List.copyOf(sessions.values());
  }
}
