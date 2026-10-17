package de.brasi.nova.taskmanagement.push;

import java.util.Collection;
import java.util.UUID;

/**
 * Port: sendet ein {@code snapshot-invalidated}-Signal an die betroffenen Benutzer.
 *
 * <p>Die reale WebSocket-Implementierung wird in Task 15 verdrahtet. In Phase 1a ist
 * standardmaessig eine No-Op-Bean aktiv ({@link NoOpInboxSignalConfig}).
 *
 * <p>Der Aufruf erfolgt IMMER nach erfolgtem Commit (After-Commit-Seam via
 * {@link org.springframework.transaction.support.TransactionSynchronizationManager}).
 * Kein Push innerhalb der DB-Transaktion.
 */
public interface InboxSignalPort {

  /**
   * Sendet ein {@code snapshot-invalidated}-Signal an die angegebenen Benutzer.
   *
   * @param tenantId  interner Mandanten-Schluessel
   * @param userRefs  UUIDs der betroffenen Benutzer (PROD_BENUTZER.EXTERNALID)
   */
  void send(long tenantId, Collection<UUID> userRefs);
}
