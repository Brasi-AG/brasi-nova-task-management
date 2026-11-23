package de.brasi.nova.taskmanagement.division;

import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.PROFILE;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.awaitSubscribed;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.completeProperties;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

/**
 * Broker-Test des Abteilungs-Listeners (Intake #190, Task 6) gegen einen eingebetteten Artemis
 * (in-VM, {@code vm://1902}, ohne Datenbank). Kleiner Kontext: {@link DivisionSyncJmsConfig},
 * {@link DivisionMembershipListener}, echter KSF-Profil-Guard und {@link DivisionSignalGuard},
 * gemockter {@link DivisionSignalProcessor} (aus {@link DivisionSignalTestSupport.ListenerBeans}).
 *
 * <p>Der Broker lebt ueber alle Kontexte der Klasse; jeder Test nutzt eine eigene Subscription,
 * damit keine Nachricht eines anderen Tests ankommt. Vor dem Senden wartet jeder Test, bis der
 * Consumer fest am Topic registriert ist ({@link DivisionSignalTestSupport#awaitSubscribed}).
 *
 * <p>„Container stoppen“ heisst im Dauerhaftigkeitstest: den Kontext schliessen. Damit schliesst
 * der Consumer; eine nicht dauerhafte Subscription verschwaende samt Nachricht, die dauerhafte
 * behaelt sie bis zum naechsten Start.
 */
class DivisionSyncBrokerTest {

  private static final String BROKER_URL = "vm://1902";
  private static final UUID TEN = UUID.fromString("00000056-0000-0056-0000-000000000561");
  private static final UUID DIV = UUID.fromString("00000056-0000-0056-dddd-000000000001");
  private static final String REV = "d".repeat(64);

  private static EmbeddedActiveMQ broker;

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              DivisionSignalTestSupport.ListenerBeans.class,
              DivisionSyncJmsConfig.class,
              DivisionMembershipListener.class);

  @BeforeAll
  static void startBroker() throws Exception {
    broker = DivisionSignalTestSupport.startBroker(BROKER_URL);
  }

  @AfterAll
  static void stopBroker() throws Exception {
    if (broker != null) {
      broker.stop();
    }
  }

  @Test
  @DisplayName("Signal auf dem Topic erreicht den Processor mit Zustellversuch 1")
  void i190_listener_receives_topic_signal() {
    runner
        .withPropertyValues(completeProperties(BROKER_URL, subscription("receives")))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              awaitSubscribed(ctx);
              final DivisionSignal signal = newSignal();

              send(BROKER_URL, PROFILE, signal);

              verify(processor(ctx), timeout(5000)).process(eq(signal), eq(1));
            });
  }

  @Test
  @DisplayName("fremdes brasiProfile wird nicht verarbeitet; ein gueltiges danach schon")
  void i190_foreign_profile_not_processed() {
    runner
        .withPropertyValues(completeProperties(BROKER_URL, subscription("foreign-profile")))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              awaitSubscribed(ctx);
              final DivisionSignal foreign = newSignal();
              final DivisionSignal own = newSignal();

              send(BROKER_URL, "anderes", foreign);

              verify(processor(ctx), after(1000).never()).process(any(), anyInt());
              // Positivkontrolle: der Listener lebt, das fremde Ereignis kommt nicht wieder.
              send(BROKER_URL, PROFILE, own);
              verify(processor(ctx), timeout(5000)).process(eq(own), eq(1));
              verify(processor(ctx), never()).process(eq(foreign), anyInt());
            });
  }

  @Test
  @DisplayName("dauerhafte Subscription behaelt ein Signal, das ohne laufenden Listener kommt")
  void i190_durable_subscription_keeps_messages_while_stopped() throws Exception {
    final String[] properties = completeProperties(BROKER_URL, subscription("durable"));
    // Erster Start legt die Subscription an; das Schliessen des Kontexts stoppt den Container.
    runner
        .withPropertyValues(properties)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              awaitSubscribed(ctx);
            });
    final DivisionSignal signal = newSignal();

    send(BROKER_URL, PROFILE, signal);

    runner
        .withPropertyValues(properties)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              verify(processor(ctx), timeout(5000)).process(eq(signal), eq(1));
            });
  }

  @Test
  @DisplayName("Ausnahme im Processor: Rollback, erneute Zustellung mit Zustellversuch 2")
  void i190_processor_exception_redelivers() {
    runner
        .withPropertyValues(completeProperties(BROKER_URL, subscription("redelivery")))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              final DivisionSignalProcessor processor = processor(ctx);
              doThrow(new DivisionFetchException("Abruf fehlgeschlagen: HTTP 503"))
                  .doNothing()
                  .when(processor)
                  .process(any(), anyInt());
              awaitSubscribed(ctx);
              final DivisionSignal signal = newSignal();

              send(BROKER_URL, PROFILE, signal);

              verify(processor, timeout(5000)).process(eq(signal), eq(1));
              verify(processor, timeout(5000)).process(eq(signal), eq(2));
            });
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private static DivisionSignalProcessor processor(final ApplicationContext ctx) {
    return ctx.getBean(DivisionSignalProcessor.class);
  }

  private static DivisionSignal newSignal() {
    return new DivisionSignal(UUID.randomUUID(), TEN, DIV, REV);
  }

  private static String subscription(final String test) {
    return "tm-broker-test." + test;
  }
}
