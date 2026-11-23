package de.brasi.nova.taskmanagement.division;

import jakarta.jms.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;

/**
 * JMS-Verdrahtung des Abteilungs-Consumers (Intake #190), nur bei {@code
 * task-management.division-sync.enabled=true}.
 *
 * <p>Die Factory {@code divisionSyncListenerFactory} baut Container fuer eine geteilte, dauerhafte
 * Subscription auf dem Topic (JMS 2.0, ohne Client-ID; mehrere Instanzen teilen sich die
 * Zustellung), mit transaktionaler Session (Rollback bei Ausnahme, dann Redelivery durch den
 * Broker) und einem Consumer je Instanz. Sie startet immer ({@code autoStartup=true}) und liest das
 * globale {@code spring.jms.listener.auto-startup} bewusst nicht: auf dev-ui steht es auf {@code
 * false} (Ruling 2026-11-22), der Abteilungs-Listener haengt allein an {@code enabled}.
 *
 * <p>Beim Aufbau prueft {@link DivisionSyncProperties#requireCompleteWhenEnabled(String)} die
 * Pflichtwerte samt {@code ksf.event.profile}; fehlt einer, bricht der Start ab.
 */
@Configuration(proxyBeanMethods = false)
@EnableJms
@ConditionalOnProperty(
    prefix = DivisionSyncProperties.PREFIX,
    name = "enabled",
    havingValue = "true")
public class DivisionSyncJmsConfig {

  /**
   * Listener-Factory des Abteilungs-Consumers.
   *
   * @param connectionFactory JMS-Verbindung der Anwendung
   * @param properties Konfiguration des Abteilungs-Abgleichs
   * @param eventProfile Wert von {@code ksf.event.profile}
   * @return Factory fuer geteilte, dauerhafte Topic-Subscriptions
   * @throws IllegalStateException wenn ein Pflichtwert fehlt
   */
  @Bean
  public DefaultJmsListenerContainerFactory divisionSyncListenerFactory(
      final ConnectionFactory connectionFactory,
      final DivisionSyncProperties properties,
      @Value("${ksf.event.profile:}") final String eventProfile) {
    properties.requireCompleteWhenEnabled(eventProfile);
    final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setPubSubDomain(true);
    factory.setSubscriptionDurable(true);
    factory.setSubscriptionShared(true);
    factory.setSessionTransacted(true);
    factory.setConcurrency("1");
    factory.setAutoStartup(true);
    return factory;
  }
}
