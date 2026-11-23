package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.keel.framework.api.event.NovaEventDeliveryMode;
import io.keel.framework.api.event.NovaEventEnvelope;
import io.keel.framework.api.event.NovaEventLinks;
import io.keel.framework.api.event.NovaEventSemantics;
import io.keel.framework.integration.event.KsfEventProperties;
import io.keel.framework.integration.event.consume.NovaEventProfileGuard;
import io.keel.framework.integration.event.publish.NovaEventStandardHeaders;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;

/**
 * Gemeinsame Test-Hilfen fuer den Abteilungs-Consumer (Intake #190): Envelope und Header eines
 * {@code DivisionMembershipChanged}-Ereignisses wie in application, ein eingebetteter
 * Artemis-Broker (in-VM, ohne Persistenz, ohne Sicherheit) und die Beans des kleinen
 * Listener-Kontexts.
 *
 * <p>Envelope und Header entstehen wie im {@code DivisionMembershipEventWriter} von application:
 * {@link NovaEventEnvelope#builder()} mit den Feldern des Plans ({@code Division}, {@code FACT},
 * Schema-Version 1) und Header ueber {@link NovaEventStandardHeaders#assemble} (KSF). Geprueft
 * werden beim Empfang nur diese Header; der Body wird nie gelesen.
 */
final class DivisionSignalTestSupport {

  /** Name des Ereignisses. */
  static final String EVENT_NAME = "DivisionMembershipChanged";

  /** {@code ksf.event.profile} der Test-Kontexte. */
  static final String PROFILE = "tm-test";

  /** Topic des Abteilungs-Ereignisses im Test. */
  static final String DESTINATION =
      "jms.topic.brasi-nova." + PROFILE + ".divisions.DivisionMembershipChanged";

  /** Schluessel, unter dem die Test-Kontexte die URL des eingebetteten Brokers lesen. */
  static final String BROKER_URL_PROPERTY = "test.division-sync.broker-url";

  /** Quelle ({@code brasiSourceService}) der Test-Ereignisse. */
  static final String SOURCE_SERVICE = "brasi-nova-application";

  /**
   * Deserialisierungs-Sperre des Listener-Clients: {@code *} verbietet jede Klasse. Ein Zugriff auf
   * den Body einer {@code ObjectMessage} wuerde damit scheitern.
   */
  static final String DENY_ALL_DESERIALIZATION = "*";

  private static final String APPLICATION_BASE = "/api/v1/application/";
  private static final String SNAPSHOT_REVISION = "d".repeat(64);
  private static final Instant OCCURRED_AT = Instant.parse("2026-11-20T09:00:00Z");
  private static final UUID CORRELATION_ID =
      UUID.fromString("00000056-0000-0056-cccc-000000000190");

  /** Audit-Logger des eingebetteten Artemis (siehe {@link #startBroker}). */
  private static final List<String> ARTEMIS_AUDIT_LOGGERS =
      List.of(
          "org.apache.activemq.audit.base",
          "org.apache.activemq.audit.message",
          "org.apache.activemq.audit.resource");

  private DivisionSignalTestSupport() {}

  // ---- Ereignis ---------------------------------------------------------------------------------

  /**
   * KSF-Envelope zum Signal, aufgebaut wie im {@code DivisionMembershipEventWriter} von application.
   *
   * @param signal Inhalt des Ereignisses
   * @return Envelope ({@code Serializable})
   */
  static NovaEventEnvelope envelope(final DivisionSignal signal) {
    final String base =
        APPLICATION_BASE
            + signal.tenantUuid()
            + "/masterdata/divisions/"
            + signal.divisionRef()
            + "/employees";
    return NovaEventEnvelope.builder()
        .schemaVersion(1)
        .eventName(EVENT_NAME)
        .tenantId(signal.tenantUuid())
        .aggregateType("Division")
        .aggregateId(signal.divisionRef())
        .aggregateVersion(3)
        .aggregateRefId(4711L)
        .occurredAt(OCCURRED_AT)
        .correlationId(CORRELATION_ID)
        .semantics(NovaEventSemantics.FACT)
        .deliveryMode(NovaEventDeliveryMode.SNAPSHOT_LINK_PREFERRED)
        .links(
            new NovaEventLinks(
                base + "?includeSubDivisions=true", base + "/snapshots/" + SNAPSHOT_REVISION))
        .eventId(signal.eventId())
        .build();
  }

  /**
   * KSF-Standard-Header zum Envelope ({@link NovaEventStandardHeaders#assemble}).
   *
   * @param envelope Envelope
   * @param profile Wert fuer {@code brasiProfile} oder {@code null} (dann fehlt der Header)
   * @return Header, wie der KSF-Transport sie als JMS-Properties setzt
   */
  static Map<String, Object> ksfHeaders(final NovaEventEnvelope envelope, final String profile) {
    return NovaEventStandardHeaders.assemble(envelope, profile, SOURCE_SERVICE);
  }

  /**
   * KSF-Standard-Header eines gueltigen Ereignisses ohne {@code brasiProfile}, als Texte.
   *
   * @param eventId novaEventId
   * @param tenantUuid novaTenantId
   * @param divisionRef aggregateId
   * @return veraenderbare Header-Map
   */
  static Map<String, String> headers(
      final UUID eventId, final UUID tenantUuid, final UUID divisionRef) {
    final Map<String, String> headers = new LinkedHashMap<>();
    ksfHeaders(envelope(new DivisionSignal(eventId, tenantUuid, divisionRef)), null)
        .forEach((key, value) -> headers.put(key, String.valueOf(value)));
    return headers;
  }

  /**
   * Haengt die Header an eine gemockte Nachricht beliebigen Typs: {@code getStringProperty}, {@code
   * getObjectProperty} und {@code propertyExists}.
   *
   * @param message gemockte Nachricht
   * @param headers Header (fehlender Schluessel = Header fehlt)
   * @throws JMSException nie (Signatur der JMS-API)
   */
  static void stubHeaders(final Message message, final Map<String, String> headers)
      throws JMSException {
    when(message.getStringProperty(anyString()))
        .thenAnswer(invocation -> headers.get(invocation.<String>getArgument(0)));
    when(message.getObjectProperty(anyString()))
        .thenAnswer(invocation -> headers.get(invocation.<String>getArgument(0)));
    when(message.propertyExists(anyString()))
        .thenAnswer(invocation -> headers.containsKey(invocation.<String>getArgument(0)));
  }

  // ---- Broker -----------------------------------------------------------------------------------

  /**
   * Startet einen eingebetteten Artemis mit in-VM-Acceptor.
   *
   * <p>Vor dem Start werden die Audit-Logger von Artemis ausgeschaltet: Steht {@code
   * org.apache.activemq.audit.base} auf INFO, ruft {@code AuditLogger#getCaller} {@code
   * Subject.getSubject} auf, und das wirft unter Java 25 eine {@link
   * UnsupportedOperationException}. Der Broker startet dann nicht (AMQ224000). Das Audit-Log
   * braucht der Test nicht.
   *
   * @param brokerUrl in-VM-URL, etwa {@code vm://1901}
   * @return laufender Broker
   * @throws Exception wenn der Start scheitert
   */
  static EmbeddedActiveMQ startBroker(final String brokerUrl) throws Exception {
    disableArtemisAuditLoggers();
    final Configuration configuration = new ConfigurationImpl();
    configuration.setPersistenceEnabled(false);
    configuration.setSecurityEnabled(false);
    configuration.setJMXManagementEnabled(false);
    configuration.addAcceptorConfiguration("invm", brokerUrl);
    final EmbeddedActiveMQ broker = new EmbeddedActiveMQ();
    broker.setConfiguration(configuration);
    broker.start();
    return broker;
  }

  /**
   * Setzt die drei Audit-Logger von Artemis auf OFF; ohne Logback als Logging-Backend bleibt alles
   * unveraendert.
   */
  private static void disableArtemisAuditLoggers() {
    for (final String name : ARTEMIS_AUDIT_LOGGERS) {
      if (LoggerFactory.getLogger(name) instanceof ch.qos.logback.classic.Logger logger) {
        logger.setLevel(ch.qos.logback.classic.Level.OFF);
      }
    }
  }

  /**
   * Sendet ein Ereignis zum Signal als {@link TextMessage} an {@link #DESTINATION}: KSF-Header als
   * String-Properties, der Text ist kein JSON (der Guard liest ihn nicht).
   *
   * @param brokerUrl in-VM-URL des Brokers
   * @param profile Wert des Headers {@code brasiProfile}
   * @param signal Inhalt des Ereignisses
   * @throws JMSException wenn das Senden scheitert
   */
  static void send(final String brokerUrl, final String profile, final DivisionSignal signal)
      throws JMSException {
    try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        JMSContext context = factory.createContext()) {
      final TextMessage message = context.createTextMessage("kein JSON, wird nicht gelesen");
      for (final Map.Entry<String, String> header :
          headers(signal.eventId(), signal.tenantUuid(), signal.divisionRef()).entrySet()) {
        message.setStringProperty(header.getKey(), header.getValue());
      }
      message.setStringProperty("brasiProfile", profile);
      context.createProducer().send(context.createTopic(DESTINATION), message);
    }
  }

  /**
   * Vollstaendige Konfiguration bei eingeschaltetem Abgleich (nur Testwerte).
   *
   * @param brokerUrl in-VM-URL des Brokers
   * @param subscriptionName Name der geteilten, dauerhaften Subscription
   * @return Property-Paare fuer den {@code ApplicationContextRunner}
   */
  static String[] completeProperties(final String brokerUrl, final String subscriptionName) {
    return new String[] {
      BROKER_URL_PROPERTY + "=" + brokerUrl,
      "task-management.division-sync.enabled=true",
      "task-management.division-sync.destination=" + DESTINATION,
      "task-management.division-sync.subscription-name=" + subscriptionName,
      "task-management.division-sync.application-base-url=https://application.example.test",
      "task-management.division-sync.oauth2.token-url=https://identity.example.test/oauth2/token",
      "task-management.division-sync.oauth2.client-id=tm-test-client",
      "task-management.division-sync.oauth2.client-secret=test-secret-value",
      "task-management.division-sync.oauth2.scope=masterdata.sync.read",
      "task-management.division-sync.max-delivery-attempts=5",
      "ksf.event.profile=" + PROFILE,
    };
  }

  /**
   * Wartet, bis jeder Listener-Container des Kontexts seinen Consumer fest am Topic registriert
   * hat; erst dann geht keine gesendete Nachricht verloren.
   *
   * @param context laufender Kontext
   */
  static void awaitSubscribed(final ApplicationContext context) {
    final JmsListenerEndpointRegistry registry = context.getBean(JmsListenerEndpointRegistry.class);
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (true) {
      final Collection<MessageListenerContainer> containers = registry.getListenerContainers();
      final boolean registered =
          !containers.isEmpty()
              && containers.stream()
                  .allMatch(
                      container ->
                          container instanceof DefaultMessageListenerContainer dmlc
                              && dmlc.isRegisteredWithDestination());
      if (registered) {
        return;
      }
      if (System.nanoTime() > deadline) {
        fail("Listener-Container hat sich nicht innerhalb von 15 s am Topic registriert.");
      }
      try {
        Thread.sleep(25);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("Warten auf die Registrierung unterbrochen.");
      }
    }
  }

  /**
   * Beans des kleinen Listener-Kontexts: Verbindung zum eingebetteten Broker mit
   * Deserialisierungs-Sperre fuer alle Klassen ({@link #DENY_ALL_DESERIALIZATION}), KSF-Profil-Guard
   * mit {@link #PROFILE}, echter {@link DivisionSignalGuard} als Mockito-Spy (zeigt den
   * empfangenen Nachrichtentyp) und gemockter {@link DivisionSignalProcessor}.
   */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(DivisionSyncProperties.class)
  static class ListenerBeans {

    @Bean
    ConnectionFactory connectionFactory(@Value("${" + BROKER_URL_PROPERTY + "}") final String url) {
      final ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
      factory.setDeserializationDenyList(DENY_ALL_DESERIALIZATION);
      return factory;
    }

    @Bean
    NovaEventProfileGuard novaEventProfileGuard() {
      final KsfEventProperties properties = new KsfEventProperties();
      properties.setProfile(PROFILE);
      return new NovaEventProfileGuard(properties);
    }

    @Bean
    DivisionSignalGuard divisionSignalGuard() {
      return spy(new DivisionSignalGuard());
    }

    @Bean
    DivisionSignalProcessor divisionSignalProcessor() {
      return mock(DivisionSignalProcessor.class);
    }
  }
}
