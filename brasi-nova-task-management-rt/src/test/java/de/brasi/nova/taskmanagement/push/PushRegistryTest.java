package de.brasi.nova.taskmanagement.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * Unit-Tests fuer {@link PushRegistry} (reine In-Memory-Registry, kein DB).
 *
 * <p>Deckt neben der Korrektheit die Reconnect-Race (Whole-Branch-Re-Gate P2 #4): register und
 * unregister muessen Bucket-Erzeugung/-Entfernung je Key atomar koordinieren, sonst kann eine neu
 * registrierte Session in einem verwaisten Bucket landen und fuer {@link PushRegistry#sessionsFor}
 * unsichtbar werden (Push verloren).
 */
class PushRegistryTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER = UUID.randomUUID();

  private static WebSocketSession session(final String id) {
    final WebSocketSession s = mock(WebSocketSession.class);
    when(s.getId()).thenReturn(id);
    return s;
  }

  @Test
  @DisplayName("register_dann_sessionsFor_liefert_session")
  void register_dann_sessionsFor_liefert_session() {
    final PushRegistry registry = new PushRegistry();
    final WebSocketSession s = session("s-1");
    registry.register(TENANT_ID, USER, s, true);
    assertThat(registry.sessionsFor(TENANT_ID, USER)).containsExactly(s);
  }

  @Test
  @DisplayName("mehrere_sessions_pro_key_mehrere_tabs")
  void mehrere_sessions_pro_key() {
    final PushRegistry registry = new PushRegistry();
    final WebSocketSession a = session("s-a");
    final WebSocketSession b = session("s-b");
    registry.register(TENANT_ID, USER, a, true);
    registry.register(TENANT_ID, USER, b, true);
    assertThat(registry.sessionsFor(TENANT_ID, USER)).containsExactlyInAnyOrder(a, b);
    registry.unregister(TENANT_ID, USER, "s-a");
    assertThat(registry.sessionsFor(TENANT_ID, USER)).containsExactly(b);
  }

  @Test
  @DisplayName("notificationSessionsFor_liefert_nur_berechtigte_sessionsFor_liefert_alle")
  void notificationSessionsFor_filtert_nach_capability() {
    final PushRegistry registry = new PushRegistry();
    final WebSocketSession capable = session("s-cap");
    final WebSocketSession notCapable = session("s-nocap");
    registry.register(TENANT_ID, USER, capable, true);
    registry.register(TENANT_ID, USER, notCapable, false);

    // sessionsFor (task-level, snapshot-invalidated) -> ALLE Sessions.
    assertThat(registry.sessionsFor(TENANT_ID, USER))
        .containsExactlyInAnyOrder(capable, notCapable);
    // notificationSessionsFor (Notification-Gate) -> NUR die berechtigte Session.
    assertThat(registry.notificationSessionsFor(TENANT_ID, USER)).containsExactly(capable);
  }

  @Test
  @DisplayName("unregister_letzte_session_leert_den_key")
  void unregister_letzte_session_leert_den_key() {
    final PushRegistry registry = new PushRegistry();
    registry.register(TENANT_ID, USER, session("s-1"), true);
    registry.unregister(TENANT_ID, USER, "s-1");
    assertThat(registry.sessionsFor(TENANT_ID, USER)).isEmpty();
    // Unbekannte Session-ID -> No-op (kein Fehler)
    registry.unregister(TENANT_ID, USER, "unbekannt");
    assertThat(registry.sessionsFor(TENANT_ID, USER)).isEmpty();
  }

  @Test
  @DisplayName(
      "unregister_mit_fremdem_key_entfernt_nichts:"
          + " key-scoped Unregister - falscher (Mandant,Benutzer) laesst die Session unberuehrt")
  void unregister_mit_fremdem_key_entfernt_nichts() {
    final PushRegistry registry = new PushRegistry();
    final WebSocketSession s = session("s-1");
    registry.register(TENANT_ID, USER, s, true);

    // Unregister mit falschem Benutzer bzw. Mandant -> No-op (Bucket des korrekten Keys unveraendert).
    registry.unregister(TENANT_ID, UUID.randomUUID(), "s-1");
    registry.unregister(999L, USER, "s-1");
    assertThat(registry.sessionsFor(TENANT_ID, USER)).containsExactly(s);

    // Mit korrektem Key -> entfernt.
    registry.unregister(TENANT_ID, USER, "s-1");
    assertThat(registry.sessionsFor(TENANT_ID, USER)).isEmpty();
  }

  @Test
  @DisplayName("reconnect_race_neue_session_bleibt_sichtbar")
  void reconnect_race_neue_session_bleibt_sichtbar() throws Exception {
    final PushRegistry registry = new PushRegistry();
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 1000; i++) {
        final String oldId = "old-" + i;
        final String newId = "new-" + i;
        // Ausgangszustand: eine alte (notification-berechtigte) Session offen.
        registry.register(TENANT_ID, USER, session(oldId), true);

        // Gleichzeitig: alte Session schliessen UND neue Session registrieren.
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final Future<?> unreg =
            pool.submit(
                () -> {
                  await(barrier);
                  registry.unregister(TENANT_ID, USER, oldId);
                });
        // Die neue Session ist bewusst NICHT notification-berechtigt (gemischte Capability unter
        // der Race) -> beweist, dass der SessionEntry-Umbau die atomare Bucket-Liveness nicht bricht.
        final WebSocketSession freshSession = session(newId);
        final Future<?> reg =
            pool.submit(
                () -> {
                  await(barrier);
                  registry.register(TENANT_ID, USER, freshSession, false);
                });
        unreg.get(5, TimeUnit.SECONDS);
        reg.get(5, TimeUnit.SECONDS);

        // Die neue Session MUSS sichtbar bleiben (kein verwaister Bucket) - unabhaengig von Capability.
        assertThat(registry.sessionsFor(TENANT_ID, USER))
            .as("Iteration %d: neue Session muss nach Reconnect-Race sichtbar sein", i)
            .contains(freshSession);
        // Capability respektiert: die nicht-berechtigte Session taucht NICHT im Notification-View auf.
        assertThat(registry.notificationSessionsFor(TENANT_ID, USER))
            .as("Iteration %d: nicht-berechtigte Session nicht im Notification-View", i)
            .doesNotContain(freshSession);

        // Aufraeumen fuer die naechste Iteration.
        registry.unregister(TENANT_ID, USER, newId);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static void await(final CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
