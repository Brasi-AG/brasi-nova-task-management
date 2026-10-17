package de.brasi.nova.taskmanagement.inbox;

import de.brasi.nova.taskmanagement.push.InboxSignalPort;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sendet nach erfolgtem Commit ein {@code snapshot-invalidated}-Signal an betroffene Benutzer.
 *
 * <p>Wenn eine aktive Transaktion vorhanden ist, wird der Port-Aufruf ueber
 * {@link TransactionSynchronizationManager#registerSynchronization(TransactionSynchronization)}
 * fuer den {@code afterCommit}-Hook registriert. Bei keiner aktiven Transaktion
 * wird direkt gesendet (Best-Effort).
 *
 * <p>Kein Push innerhalb der DB-Transaktion; das verhindert Phantom-Signale bei Rollback.
 */
@Service
public class InboxSignalService {

  private static final Logger log = LoggerFactory.getLogger(InboxSignalService.class);

  private final InboxSignalPort port;

  /** Konstruktor-Injektion des {@link InboxSignalPort}. */
  public InboxSignalService(final InboxSignalPort port) {
    this.port = port;
  }

  /**
   * Registriert ein {@code snapshot-invalidated}-Signal fuer die angegebenen Benutzer.
   *
   * <p>Innerhalb einer aktiven Transaktion wird der Port-Aufruf fuer {@code afterCommit}
   * eingereiht. Ausserhalb wird direkt gesendet.
   *
   * @param tenantId interner Mandanten-Schluessel
   * @param userRefs UUIDs der betroffenen Benutzer (PROD_BENUTZER.EXTERNALID)
   */
  public void invalidate(final long tenantId, final Collection<UUID> userRefs) {
    if (userRefs == null || userRefs.isEmpty()) {
      return;
    }
    final List<UUID> snapshot = List.copyOf(userRefs);

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      // After-Commit-Seam: Signal erst nach erfolgreichem Commit senden
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              log.debug(
                  "InboxSignalService.afterCommit: snapshot-invalidated fuer Mandant {}"
                      + " und {} Benutzer.",
                  tenantId,
                  snapshot.size());
              port.send(tenantId, snapshot);
            }
          });
    } else {
      // Kein Transaktionskontext: direkt senden (Best-Effort, z.B. in Tests)
      log.debug(
          "InboxSignalService (kein Tx-Kontext): snapshot-invalidated direkt fuer Mandant {}"
              + " und {} Benutzer.",
          tenantId,
          snapshot.size());
      port.send(tenantId, snapshot);
    }
  }
}
