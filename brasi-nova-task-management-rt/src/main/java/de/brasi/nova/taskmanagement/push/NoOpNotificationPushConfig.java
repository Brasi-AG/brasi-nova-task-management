package de.brasi.nova.taskmanagement.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registriert eine No-Op-Implementierung von {@link NotificationPushPort}, wenn keine echte
 * WebSocket-Implementierung vorhanden ist.
 *
 * <p>Die reale Implementierung wird in Task 15 als {@code @Primary}-Bean registriert und
 * verdraengt diese No-Op-Bean automatisch.
 */
@Configuration
public class NoOpNotificationPushConfig {

  private static final Logger log = LoggerFactory.getLogger(NoOpNotificationPushConfig.class);

  /**
   * No-Op-{@link NotificationPushPort}: loggt den Aufruf und tut nichts weiter.
   *
   * @return die No-Op-Implementierung
   */
  @Bean
  @ConditionalOnMissingBean(NotificationPushPort.class)
  public NotificationPushPort noOpNotificationPushPort() {
    return new NoOpNotificationPushPort();
  }

  /** Interne No-Op-Implementierung. */
  static class NoOpNotificationPushPort implements NotificationPushPort {

    @Override
    public void push(
        final long tenantId,
        final java.util.UUID recipientRef,
        final NotificationPushEnvelope envelope) {
      log.debug(
          "NoOp-NotificationPushPort: Benachrichtigung {} (Kategorie={}, Schwere={}) an Empfaenger"
              + " {}/Mandant {} nicht gesendet (kein WS-Adapter aktiv).",
          envelope.notificationId(),
          envelope.category(),
          envelope.severity(),
          recipientRef,
          tenantId);
    }
  }
}
