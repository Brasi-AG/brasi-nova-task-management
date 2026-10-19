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
    // Bucket-Erzeugung UND Session-Einfuegen atomar unter dem Bin-Lock des Keys: sonst koennte
    // ein nebenlaeufiges unregister den (noch leeren) Bucket zwischen computeIfAbsent und put
    // als leer entfernen -> die neue Session landet in einem verwaisten Bucket und ist fuer
    // sessionsFor unsichtbar (Push verloren).
    byKey.compute(
        key,
        (k, bucket) -> {
          final ConcurrentHashMap<String, WebSocketSession> b =
              (bucket == null) ? new ConcurrentHashMap<>() : bucket;
          b.put(session.getId(), session);
          return b;
        });
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
    // Entfernen der Session UND Entfernen des leeren Buckets atomar unter dem Bin-Lock des Keys.
    // Serialisiert gegen register.compute auf demselben Key -> ein gleichzeitiger Reconnect kann den
    // Bucket nicht entfernen, waehrend register gerade die neue Session hinzufuegt.
    byKey.compute(
        key,
        (k, bucket) -> {
          if (bucket == null) {
            return null;
          }
          bucket.remove(sessionId);
          return bucket.isEmpty() ? null : bucket;
        });
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
