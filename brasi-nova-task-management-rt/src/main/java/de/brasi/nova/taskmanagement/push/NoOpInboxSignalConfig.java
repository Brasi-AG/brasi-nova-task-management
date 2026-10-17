package de.brasi.nova.taskmanagement.push;

import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registriert eine No-Op-Implementierung von {@link InboxSignalPort}, wenn keine echte
 * WebSocket-Implementierung vorhanden ist.
 *
 * <p>Die reale Implementierung wird in Task 15 als {@code @Primary}-Bean registriert und
 * verdraengt diese No-Op-Bean automatisch.
 */
@Configuration
public class NoOpInboxSignalConfig {

  private static final Logger log = LoggerFactory.getLogger(NoOpInboxSignalConfig.class);

  /**
   * No-Op-{@link InboxSignalPort}: loggt den Aufruf und tut nichts weiter.
   *
   * @return die No-Op-Implementierung
   */
  @Bean
  @ConditionalOnMissingBean(InboxSignalPort.class)
  public InboxSignalPort noOpInboxSignalPort() {
    return new NoOpInboxSignalPort();
  }

  /** Interne No-Op-Implementierung. */
  static class NoOpInboxSignalPort implements InboxSignalPort {

    @Override
    public void send(final long tenantId, final Collection<UUID> userRefs) {
      log.debug(
          "NoOp-InboxSignalPort: snapshot-invalidated fuer Mandant {} und Benutzer {}.",
          tenantId,
          userRefs);
    }
  }
}
