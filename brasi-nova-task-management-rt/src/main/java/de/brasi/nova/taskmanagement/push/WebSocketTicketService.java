package de.brasi.nova.taskmanagement.push;

import de.brasi.nova.taskmanagement.push.model.WsTicketResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ausgabe und Einloesung kurzlebiger WebSocket-Einmal-Tickets (arch-v2 §6, Codex-BLOCKER 7).
 *
 * <p>Das Ticket wird ausschliesslich aus dem aktuellen Principal/Tenant erzeugt (nie aus
 * Clientdaten) und beim {@code WS /ws}-Handshake <b>atomar + einmalig</b> eingeloest
 * ({@link java.util.concurrent.ConcurrentHashMap#remove(Object)}). Es ersetzt NICHT das JWT der
 * REST-Schicht - es ist nur das Transport-Geheimnis fuer den Handshake (kein JWT in der WS-URL).
 *
 * <p><b>Single-Instance (1a):</b> Der Store ist lokal je RT-Instanz. Mehrinstanz-Fanout ist 1b
 * (arch-v2 §6: 1a = Single-Instance bzw. Sticky-Session). Abgelaufene Tickets werden lazy beim
 * {@code consume} verworfen.
 */
@Service
public class WebSocketTicketService {

  /** 32 Byte = 256 Bit Zufall (&gt;= 128 Bit gefordert). */
  private static final int TOKEN_BYTES = 32;

  private final SecureRandom secureRandom = new SecureRandom();
  private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
  private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
  private final long ttlSeconds;
  private final Clock clock;

  /**
   * Produktiv-Konstruktor. Nutzt {@link Clock#systemUTC()}.
   *
   * @param ttlSeconds Ticket-Lebensdauer in Sekunden ({@code task.websocket.ticket-ttl-seconds})
   */
  @Autowired
  public WebSocketTicketService(
      @Value("${task.websocket.ticket-ttl-seconds:30}") final long ttlSeconds) {
    this(ttlSeconds, Clock.systemUTC());
  }

  /**
   * Test-Konstruktor mit injizierbarer Uhr (deterministische Ablaufpruefung).
   *
   * @param ttlSeconds Ticket-Lebensdauer in Sekunden
   * @param clock      Uhr fuer Ausstellung und Ablaufpruefung
   */
  WebSocketTicketService(final long ttlSeconds, final Clock clock) {
    this.ttlSeconds = ttlSeconds;
    this.clock = clock;
  }

  /**
   * Stellt ein neues Einmal-Ticket fuer den angegebenen Principal/Tenant aus.
   *
   * @param tenantId interner Mandanten-Schluessel (aus Security-Context)
   * @param userRef  UUID des Benutzers (aus Security-Context)
   * @return {@link WsTicketResponse} mit opakem Token und Ablaufzeitpunkt
   */
  public WsTicketResponse issue(final long tenantId, final UUID userRef) {
    // Lazy Cleanup: abgelaufene, nie-konsumierte Tickets entfernen um unbegrenztes Wachstum
    // des Stores zu verhindern (nie konsumierte Tickets bleiben sonst bis JVM-Neustart erhalten).
    final Instant now = clock.instant();
    store.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));

    final byte[] raw = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(raw);
    final String token = encoder.encodeToString(raw);
    final Instant expiresAt = now.plusSeconds(ttlSeconds);
    store.put(token, new Entry(tenantId, userRef, expiresAt));
    return new WsTicketResponse(token, expiresAt);
  }

  /**
   * Loest ein Ticket atomar + einmalig ein.
   *
   * <p>Ein zweiter Aufruf mit demselben Token liefert {@link Optional#empty()} (einmalig); ein
   * abgelaufenes Ticket liefert ebenfalls {@code empty} (kurzlebig) und wurde durch den
   * {@code remove} bereits verworfen.
   *
   * @param token opaker Einmal-Token (null/blank -&gt; {@code empty})
   * @return {@link TicketPrincipal} bei gueltigem, nicht abgelaufenem Ticket, sonst {@code empty}
   */
  public Optional<TicketPrincipal> consume(final String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    final Entry entry = store.remove(token);
    if (entry == null) {
      return Optional.empty();
    }
    if (clock.instant().isAfter(entry.expiresAt())) {
      return Optional.empty();
    }
    return Optional.of(new TicketPrincipal(entry.tenantId(), entry.userRef()));
  }

  /** Anzahl Eintraege im Store (fuer Tests). */
  int storeSize() {
    return store.size();
  }

  /** Interner Store-Eintrag. */
  private record Entry(long tenantId, UUID userRef, Instant expiresAt) {}

  /**
   * Aufgeloester Ticket-Principal (Routing-Identitaet fuer die WS-Session).
   *
   * @param tenantId interner Mandanten-Schluessel
   * @param userRef  UUID des Benutzers
   */
  public record TicketPrincipal(long tenantId, UUID userRef) {}
}
