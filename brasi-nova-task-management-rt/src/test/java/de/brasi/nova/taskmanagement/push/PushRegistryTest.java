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
    registry.register(TENANT_ID, USER, s);
    assertThat(registry.sessionsFor(TENANT_ID, USER)).containsExactly(s);
  }

  @Test
  @DisplayName("mehrere_sessions_pro_key_mehrere_tabs")
  void mehrere_sessions_pro_key() {
    final PushRegistry registry = new PushRegistry();
    final WebSocketSession a = session("s-a");
    final WebSocketSession b = session("s-b");
    registry.register(TENANT_ID, USER, a);
    registry.register(TENANT_ID, USER, b);
    assertThat(registry.sessionsFor(TENANT_ID, USER)).containsExactlyInAnyOrder(a, b);
    registry.unregister("s-a");
    assertThat(registry.sessionsFor(TENANT_ID, USER)).containsExactly(b);
  }

  @Test
  @DisplayName("unregister_letzte_session_leert_den_key")
  void unregister_letzte_session_leert_den_key() {
    final PushRegistry registry = new PushRegistry();
    registry.register(TENANT_ID, USER, session("s-1"));
    registry.unregister("s-1");
    assertThat(registry.sessionsFor(TENANT_ID, USER)).isEmpty();
    // Unbekannte Session-ID -> No-op (kein Fehler)
    registry.unregister("unbekannt");
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
        // Ausgangszustand: eine alte Session offen.
        registry.register(TENANT_ID, USER, session(oldId));

        // Gleichzeitig: alte Session schliessen UND neue Session registrieren.
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final Future<?> unreg =
            pool.submit(
                () -> {
                  await(barrier);
                  registry.unregister(oldId);
                });
        final WebSocketSession freshSession = session(newId);
        final Future<?> reg =
            pool.submit(
                () -> {
                  await(barrier);
                  registry.register(TENANT_ID, USER, freshSession);
                });
        unreg.get(5, TimeUnit.SECONDS);
        reg.get(5, TimeUnit.SECONDS);

        // Die neue Session MUSS sichtbar bleiben (kein verwaister Bucket).
        assertThat(registry.sessionsFor(TENANT_ID, USER))
            .as("Iteration %d: neue Session muss nach Reconnect-Race sichtbar sein", i)
            .contains(freshSession);

        // Aufraeumen fuer die naechste Iteration.
        registry.unregister(newId);
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
