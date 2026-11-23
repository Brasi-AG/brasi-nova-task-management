package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.keel.framework.integration.event.KsfEventProperties;
import io.keel.framework.integration.event.consume.NovaEventProfileGuard;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;

/**
 * Gemeinsame Test-Hilfen fuer den Abteilungs-Consumer (Intake #190, Tasks 4 und 6): Header und
 * Body eines {@code DivisionMembershipChanged}-Ereignisses im KSF-Format, ein eingebetteter
 * Artemis-Broker (in-VM, ohne Persistenz, ohne Sicherheit) und die Beans des kleinen
 * Listener-Kontexts.
 *
 * <p>Header nach {@code NovaEventStandardHeaders} (camelCase, als String-Properties): {@code
 * eventName}, {@code novaEventId}, {@code novaTenantId}, {@code aggregateId}, {@code
 * brasiProfile}. Der Body ist das Envelope als JSON; die revision steht als letztes Pfadsegment im
 * Link {@code links.snapshot}.
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

  private static final String APPLICATION_BASE = "https://application.example.test";
  private static final ObjectMapper JSON = new ObjectMapper();

  private DivisionSignalTestSupport() {}

  // ---- Ereignis ---------------------------------------------------------------------------------

  /**
   * Header eines gueltigen Ereignisses ohne {@code brasiProfile}.
   *
   * @param eventId novaEventId
   * @param tenantUuid novaTenantId
   * @param divisionRef aggregateId
   * @return veraenderbare Header-Map
   */
  static Map<String, String> headers(
      final UUID eventId, final UUID tenantUuid, final UUID divisionRef) {
    final Map<String, String> headers = new LinkedHashMap<>();
    headers.put("eventName", EVENT_NAME);
    headers.put("novaEventId", eventId.toString());
    headers.put("novaTenantId", tenantUuid.toString());
    headers.put("aggregateId", divisionRef.toString());
    return headers;
  }

  /**
   * Snapshot-Link, wie application ihn ins Ereignis schreibt.
   *
   * @param tenantUuid Mandant
   * @param divisionRef Abteilung
   * @param revision letztes Pfadsegment
   * @return absoluter Link
   */
  static String snapshotLink(final UUID tenantUuid, final UUID divisionRef, final String revision) {
    return selfLink(tenantUuid, divisionRef) + "/snapshots/" + revision;
  }

  private static String selfLink(final UUID tenantUuid, final UUID divisionRef) {
    return APPLICATION_BASE
        + "/api/v1/application/"
        + tenantUuid
        + "/masterdata/divisions/"
        + divisionRef
        + "/employees";
  }

  /**
   * Envelope als JSON; {@code null}-Werte werden weggelassen.
   *
   * @param eventId eventId oder {@code null}
   * @param tenantUuid tenantId oder {@code null}
   * @param divisionRef aggregateId oder {@code null}
   * @param snapshotLink Link {@code links.snapshot} oder {@code null}
   * @return JSON-Text
   */
  static String body(
      final UUID eventId, final UUID tenantUuid, final UUID divisionRef, final String snapshotLink) {
    final ObjectNode root = JSON.createObjectNode();
    root.put("schemaVersion", 1);
    root.put("eventName", EVENT_NAME);
    if (tenantUuid != null) {
      root.put("tenantId", tenantUuid.toString());
    }
    root.put("aggregateType", "Division");
    if (divisionRef != null) {
      root.put("aggregateId", divisionRef.toString());
    }
    root.put("aggregateVersion", 3);
    root.put("occurredAt", "2026-11-20T09:00:00Z");
    root.put("semantics", "FACT");
    root.put("deliveryMode", "SNAPSHOT_LINK_PREFERRED");
    final ObjectNode links = root.putObject("links");
    if (tenantUuid != null && divisionRef != null) {
      links.put("self", selfLink(tenantUuid, divisionRef));
    }
    if (snapshotLink != null) {
      links.put("snapshot", snapshotLink);
    }
    if (eventId != null) {
      root.put("eventId", eventId.toString());
    }
    return root.toString();
  }

  /**
   * Body eines gueltigen Ereignisses zum Signal.
   *
   * @param signal erwartetes Signal
   * @return JSON-Text
   */
  static String body(final DivisionSignal signal) {
    return body(
        signal.eventId(),
        signal.tenantUuid(),
        signal.divisionRef(),
        snapshotLink(signal.tenantUuid(), signal.divisionRef(), signal.revision()));
  }

  /**
   * Mockito-{@link TextMessage}: Header ueber {@code getStringProperty}, {@code getObjectProperty}
   * und {@code propertyExists}, Body ueber {@code getText}.
   *
   * @param headers Header (fehlender Schluessel = Header fehlt)
   * @param body JSON-Text
   * @return gemockte Nachricht
   * @throws JMSException nie (Signatur der JMS-API)
   */
  static TextMessage textMessage(final Map<String, String> headers, final String body)
      throws JMSException {
    final TextMessage message = mock(TextMessage.class);
    stubHeaders(message, headers);
    when(message.getText()).thenReturn(body);
    return message;
  }

  /**
   * Haengt die Header an eine gemockte Nachricht beliebigen Typs.
   *
   * @param message gemockte Nachricht
   * @param headers Header
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
   * @param brokerUrl in-VM-URL, etwa {@code vm://1901}
   * @return laufender Broker
   * @throws Exception wenn der Start scheitert
   */
  static EmbeddedActiveMQ startBroker(final String brokerUrl) throws Exception {
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
   * Sendet ein Ereignis zum Signal an {@link #DESTINATION}.
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
      final TextMessage message = context.createTextMessage(body(signal));
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
      "task-management.division-sync.application-base-url=" + APPLICATION_BASE,
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
    final JmsListenerEndpointRegistry registry =
        context.getBean(JmsListenerEndpointRegistry.class);
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
   * Beans des kleinen Listener-Kontexts: Verbindung zum eingebetteten Broker, KSF-Profil-Guard mit
   * {@link #PROFILE}, echter {@link DivisionSignalGuard} und gemockter {@link
   * DivisionSignalProcessor}.
   */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(DivisionSyncProperties.class)
  static class ListenerBeans {

    @Bean
    ConnectionFactory connectionFactory(@Value("${" + BROKER_URL_PROPERTY + "}") final String url) {
      return new ActiveMQConnectionFactory(url);
    }

    @Bean
    NovaEventProfileGuard novaEventProfileGuard() {
      final KsfEventProperties properties = new KsfEventProperties();
      properties.setProfile(PROFILE);
      return new NovaEventProfileGuard(properties);
    }

    @Bean
    DivisionSignalGuard divisionSignalGuard() {
      return new DivisionSignalGuard(new ObjectMapper());
    }

    @Bean
    DivisionSignalProcessor divisionSignalProcessor() {
      return mock(DivisionSignalProcessor.class);
    }
  }
}
