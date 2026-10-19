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

  /**
   * Registrierter Session-Eintrag inkl. der am Ticket festgeschriebenen NOTIFICATION_READ-Capability.
   * Notification-Pushes gehen nur an Eintraege mit {@code notificationRead=true}; das task-level
   * {@code snapshot-invalidated}-Signal an alle Sessions (unabhaengig von der Capability).
   */
  private record SessionEntry(WebSocketSession session, boolean notificationRead) {}

  /** Key -> (SessionId -> SessionEntry). */
  private final ConcurrentHashMap<Key, ConcurrentHashMap<String, SessionEntry>> byKey =
      new ConcurrentHashMap<>();

  /** SessionId -> Key (fuer O(1)-Unregister beim Schliessen). */
  private final ConcurrentHashMap<String, Key> keyBySession = new ConcurrentHashMap<>();

  /**
   * Registriert eine (dekorierte) Session fuer den Empfaenger.
   *
   * @param tenantId interner Mandanten-Schluessel
   * @param userRef  UUID des Benutzers
   * @param session  dekorierte WebSocket-Session
   * @param notificationRead ob die Session (aus ihrem Ticket) NOTIFICATION_READ besitzt und damit
   *     Notification-Pushes empfangen darf (fail-closed: {@code false} = keine Notification-Pushes)
   */
  public void register(
      final long tenantId,
      final UUID userRef,
      final WebSocketSession session,
      final boolean notificationRead) {
    final Key key = new Key(tenantId, userRef);
    // Bucket-Erzeugung UND Session-Einfuegen atomar unter dem Bin-Lock des Keys: sonst koennte
    // ein nebenlaeufiges unregister den (noch leeren) Bucket zwischen computeIfAbsent und put
    // als leer entfernen -> die neue Session landet in einem verwaisten Bucket und ist fuer
    // sessionsFor unsichtbar (Push verloren).
    byKey.compute(
        key,
        (k, bucket) -> {
          final ConcurrentHashMap<String, SessionEntry> b =
              (bucket == null) ? new ConcurrentHashMap<>() : bucket;
          b.put(session.getId(), new SessionEntry(session, notificationRead));
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
   * Liefert eine Momentaufnahme ALLER offenen Sessions des Empfaengers (unabhaengig von der
   * NOTIFICATION_READ-Capability). Nutzung: task-level {@code snapshot-invalidated}-Signal.
   *
   * @param tenantId interner Mandanten-Schluessel
   * @param userRef  UUID des Benutzers
   * @return Liste der Sessions (leer, wenn keine Session offen)
   */
  public Collection<WebSocketSession> sessionsFor(final long tenantId, final UUID userRef) {
    final ConcurrentHashMap<String, SessionEntry> sessions = byKey.get(new Key(tenantId, userRef));
    if (sessions == null) {
      return List.of();
    }
    return sessions.values().stream().map(SessionEntry::session).toList();
  }

  /**
   * Liefert eine Momentaufnahme der offenen Sessions des Empfaengers, die NOTIFICATION_READ besitzen.
   * Nutzung: Notification-Pushes (Gate). Sessions ohne Capability werden ausgelassen (fail-closed).
   *
   * @param tenantId interner Mandanten-Schluessel
   * @param userRef  UUID des Benutzers
   * @return Liste der notification-berechtigten Sessions (leer, wenn keine)
   */
  public Collection<WebSocketSession> notificationSessionsFor(
      final long tenantId, final UUID userRef) {
    final ConcurrentHashMap<String, SessionEntry> sessions = byKey.get(new Key(tenantId, userRef));
    if (sessions == null) {
      return List.of();
    }
    return sessions.values().stream()
        .filter(SessionEntry::notificationRead)
        .map(SessionEntry::session)
        .toList();
  }
}
