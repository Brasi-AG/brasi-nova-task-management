package de.brasi.nova.taskmanagement.division;

import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.BROKER_URL_PROPERTY;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.DESTINATION;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.PROFILE;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.completeProperties;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;

/**
 * Prueft {@link DivisionSyncJmsConfig} und {@link DivisionMembershipListener} (Intake #190, Task 6)
 * mit dem {@link ApplicationContextRunner}: Gate ueber {@code task-management.division-sync.enabled},
 * Startabbruch bei unvollstaendiger Konfiguration und die Eigenschaften des Containers der
 * Division-Factory (geteilte, dauerhafte Topic-Subscription, transaktionale Session, ein Consumer).
 *
 * <p>Ruling 2026-11-22: Die Factory liest das globale {@code spring.jms.listener.auto-startup}
 * nicht; auf dev-ui steht es auf {@code false}, der Abteilungs-Listener startet trotzdem. Deshalb
 * laeuft {@link JmsAutoConfiguration} mit (sie wertet die Property fuer ihre Standard-Factory aus).
 *
 * <p>Die Container starten beim Refresh; ein eingebetteter Artemis (in-VM, {@code vm://1901})
 * stellt die Verbindung. KSF-Profil-Guard, Signal-Guard und gemockter Processor kommen aus {@link
 * DivisionSignalTestSupport.ListenerBeans}.
 *
 * <p>Angenommene Signaturen: {@code DivisionSyncJmsConfig} ({@code @EnableJms}, bedingt auf {@code
 * enabled=true}) mit Bean {@code divisionSyncListenerFactory(ConnectionFactory)} vom Typ {@code
 * DefaultJmsListenerContainerFactory}, ohne externen Transaktionsmanager; Pflichtpruefung ueber
 * {@code DivisionSyncProperties#requireCompleteWhenEnabled(ksf.event.profile)}. {@code
 * DivisionMembershipListener} (gleiche Bedingung) mit Abhaengigkeiten nur aus {@code
 * NovaEventProfileGuard}, {@code DivisionSignalGuard}, {@code DivisionSignalProcessor} (und
 * gegebenenfalls {@code DivisionSyncProperties}).
 */
class DivisionSyncJmsConfigTest {

  private static final String BROKER_URL = "vm://1901";
  private static final String SUBSCRIPTION = "tm-config-test.division-membership";

  private static EmbeddedActiveMQ broker;

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JmsAutoConfiguration.class))
          .withUserConfiguration(
              DivisionSignalTestSupport.ListenerBeans.class,
              DivisionSyncJmsConfig.class,
              DivisionMembershipListener.class)
          .withPropertyValues(BROKER_URL_PROPERTY + "=" + BROKER_URL);

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
  @DisplayName("enabled=false: weder Listener noch Division-Factory")
  void i190_disabled_creates_no_listener_beans() {
    runner
        .withPropertyValues(
            "task-management.division-sync.enabled=false",
            "task-management.division-sync.destination=" + DESTINATION,
            "task-management.division-sync.subscription-name=" + SUBSCRIPTION)
        .run(
            ctx ->
                assertThat(ctx)
                    .hasNotFailed()
                    .doesNotHaveBean(DivisionMembershipListener.class)
                    .doesNotHaveBean("divisionSyncListenerFactory"));
  }

  @Test
  @DisplayName("enabled=true ohne Basis-URL und OAuth-Werte: Start bricht ab, Meldung nennt Schluessel")
  void i190_enabled_incomplete_config_fails_start() {
    runner
        .withPropertyValues(
            "task-management.division-sync.enabled=true",
            "task-management.division-sync.destination=" + DESTINATION,
            "task-management.division-sync.subscription-name=" + SUBSCRIPTION,
            "ksf.event.profile=" + PROFILE)
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("task-management.division-sync.application-base-url")
                  .hasMessageContaining("task-management.division-sync.oauth2.token-url")
                  .hasMessageContaining("task-management.division-sync.oauth2.client-id")
                  .hasMessageContaining("task-management.division-sync.oauth2.client-secret");
            });
  }

  @Test
  @DisplayName("Container der Division-Factory: Topic, shared durable, transaktional, ein Consumer")
  void i190_factory_is_shared_durable_topic() {
    runner
        .withPropertyValues(completeProperties(BROKER_URL, SUBSCRIPTION))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasBean("divisionSyncListenerFactory");
              final DefaultMessageListenerContainer container = divisionContainer(ctx);
              assertThat(container.isPubSubDomain()).as("Topic").isTrue();
              assertThat(container.isSubscriptionDurable()).as("dauerhaft").isTrue();
              assertThat(container.isSubscriptionShared()).as("geteilt").isTrue();
              assertThat(container.isSessionTransacted()).as("transaktionale Session").isTrue();
              assertThat(container.getConcurrentConsumers()).isEqualTo(1);
              assertThat(container.getMaxConcurrentConsumers()).isEqualTo(1);
              assertThat(container.getDestinationName()).isEqualTo(DESTINATION);
              assertThat(container.getSubscriptionName()).isEqualTo(SUBSCRIPTION);
            });
  }

  @Test
  @DisplayName("spring.jms.listener.auto-startup=false: der Abteilungs-Listener startet trotzdem")
  void i190_listener_starts_despite_global_auto_startup_false() {
    runner
        .withPropertyValues(completeProperties(BROKER_URL, SUBSCRIPTION))
        .withPropertyValues("spring.jms.listener.auto-startup=false")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              final DefaultMessageListenerContainer container = divisionContainer(ctx);
              assertThat(container.isAutoStartup()).as("autoStartup der Division-Factory").isTrue();
              assertThat(container.isRunning()).as("nach dem Refresh gestartet").isTrue();
            });
  }

  /** Der einzige Listener-Container des Kontexts gehoert zum {@link DivisionMembershipListener}. */
  private static DefaultMessageListenerContainer divisionContainer(
      final AssertableApplicationContext ctx) {
    final Collection<MessageListenerContainer> containers =
        ctx.getBean(JmsListenerEndpointRegistry.class).getListenerContainers();
    assertThat(containers).hasSize(1);
    final MessageListenerContainer container = containers.iterator().next();
    assertThat(container).isInstanceOf(DefaultMessageListenerContainer.class);
    return (DefaultMessageListenerContainer) container;
  }
}
