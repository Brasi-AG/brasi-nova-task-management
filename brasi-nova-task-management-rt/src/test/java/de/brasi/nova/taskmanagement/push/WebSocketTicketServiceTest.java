package de.brasi.nova.taskmanagement.push;

import static org.assertj.core.api.Assertions.assertThat;

import de.brasi.nova.taskmanagement.push.WebSocketTicketService.TicketPrincipal;
import de.brasi.nova.taskmanagement.push.model.WsTicketResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit-Tests fuer {@link WebSocketTicketService} (einmalig + kurzlebig). */
class WebSocketTicketServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER_REF = UUID.randomUUID();
  private static final long TTL_SECONDS = 30L;

  /** Verstellbare Uhr fuer deterministische Ablaufpruefung. */
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

  @Test
  @DisplayName("ticket_einmalig_kurzlebig")
  void ticket_einmalig_kurzlebig() {
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
    final WebSocketTicketService service = new WebSocketTicketService(TTL_SECONDS, clock);

    final WsTicketResponse issued = service.issue(TENANT_ID, USER_REF);
    assertThat(issued.ticket()).isNotBlank();
    assertThat(issued.expiresAt()).isEqualTo(clock.instant().plusSeconds(TTL_SECONDS));

    // einmalig: erster consume liefert den Principal, der zweite ist leer
    final Optional<TicketPrincipal> first = service.consume(issued.ticket());
    assertThat(first).isPresent();
    assertThat(first.get().tenantId()).isEqualTo(TENANT_ID);
    assertThat(first.get().userRef()).isEqualTo(USER_REF);
    assertThat(service.consume(issued.ticket())).as("zweiter consume -> leer (einmalig)").isEmpty();
  }

  @Test
  @DisplayName("ticket_abgelaufen_wird_abgelehnt")
  void ticket_abgelaufen_wird_abgelehnt() {
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
    final WebSocketTicketService service = new WebSocketTicketService(TTL_SECONDS, clock);

    final WsTicketResponse issued = service.issue(TENANT_ID, USER_REF);
    clock.advance(Duration.ofSeconds(TTL_SECONDS + 1)); // nach Ablauf

    assertThat(service.consume(issued.ticket())).as("abgelaufenes Ticket -> leer (kurzlebig)")
        .isEmpty();
  }

  @Test
  @DisplayName("ticket_an_ablaufgrenze_noch_gueltig")
  void ticket_an_ablaufgrenze_noch_gueltig() {
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
    final WebSocketTicketService service = new WebSocketTicketService(TTL_SECONDS, clock);

    final WsTicketResponse issued = service.issue(TENANT_ID, USER_REF);
    clock.advance(Duration.ofSeconds(TTL_SECONDS)); // exakt expiresAt (nicht NACH)

    assertThat(service.consume(issued.ticket())).as("an der Grenze noch gueltig").isPresent();
  }

  @Test
  @DisplayName("consume_null_blank_unbekannt_liefert_leer")
  void consume_null_blank_unbekannt_liefert_leer() {
    final WebSocketTicketService service = new WebSocketTicketService(TTL_SECONDS, Clock.systemUTC());
    assertThat(service.consume(null)).isEmpty();
    assertThat(service.consume("  ")).isEmpty();
    assertThat(service.consume("unbekannt")).isEmpty();
  }

  @Test
  @DisplayName("issue_token_mindestens_128_bit_und_unterschiedlich")
  void issue_token_mindestens_128_bit_und_unterschiedlich() {
    final WebSocketTicketService service = new WebSocketTicketService(TTL_SECONDS, Clock.systemUTC());
    final WsTicketResponse a = service.issue(TENANT_ID, USER_REF);
    final WsTicketResponse b = service.issue(TENANT_ID, USER_REF);

    assertThat(a.ticket()).isNotEqualTo(b.ticket());
    final byte[] decoded = Base64.getUrlDecoder().decode(a.ticket());
    assertThat(decoded.length * 8).as(">= 128 Bit Entropie").isGreaterThanOrEqualTo(128);
  }

  @Test
  @DisplayName("zwei_gleichzeitige_consume_genau_ein_erfolg")
  void zwei_gleichzeitige_consume_genau_ein_erfolg() throws Exception {
    final WebSocketTicketService service =
        new WebSocketTicketService(TTL_SECONDS, Clock.systemUTC());
    final WsTicketResponse ticket = service.issue(TENANT_ID, USER_REF);

    final int threads = 8;
    final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    final java.util.concurrent.atomic.AtomicInteger successes =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

    try {
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                  if (service.consume(ticket.ticket()).isPresent()) {
                    successes.incrementAndGet();
                  }
                }));
      }
      start.countDown(); // alle Threads gleichzeitig loslaufen lassen
      for (final java.util.concurrent.Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(successes.get()).as("genau ein gleichzeitiger consume gewinnt (atomar einmalig)")
        .isEqualTo(1);
  }
}
