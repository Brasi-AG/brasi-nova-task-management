package de.brasi.nova.taskmanagement.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.brasi.nova.taskmanagement.push.InboxSignalPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit-Tests fuer {@link InboxSignalService}.
 *
 * <p>Testet den After-Commit-Seam mit echtem {@link InboxSignalService} und Mock-{@link InboxSignalPort}:
 * <ul>
 *   <li>(a) Innerhalb aktiver Tx VOR Commit: kein Port-Send
 *   <li>(b) Nach Commit: genau EIN Send
 *   <li>(c) Nach Rollback: KEIN Send
 *   <li>(d) Ohne aktive Tx: direktes Send
 * </ul>
 */
class InboxSignalServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER_REF = UUID.randomUUID();

  private InboxSignalPort port;
  private InboxSignalService service;

  @BeforeEach
  void setUp() {
    port = mock(InboxSignalPort.class);
    service = new InboxSignalService(port);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // ---- (a) Innerhalb aktiver Tx VOR Commit: kein Send ------------------------------------------

  @Test
  @DisplayName("vor_commit_kein_send: innerhalb aktiver Tx wird Port NICHT sofort aufgerufen")
  void vor_commit_kein_send() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.invalidate(TENANT_ID, List.of(USER_REF));

      // Port noch nicht aufgerufen (Synchronization registriert, aber afterCommit noch nicht gefeuert)
      verify(port, never()).send(eq(TENANT_ID), any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // ---- (b) Nach Commit: genau EIN Send ---------------------------------------------------------

  @Test
  @DisplayName("nach_commit_ein_send: afterCommit-Hook sendet genau einmal an den Port")
  void nach_commit_ein_send() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.invalidate(TENANT_ID, List.of(USER_REF));

      // afterCommit manuell ausfuehren (simuliert Commit-Abschluss)
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(s -> s.afterCommit());

      verify(port, times(1)).send(eq(TENANT_ID), any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // ---- (c) Nach Rollback: KEIN Send ------------------------------------------------------------

  @Test
  @DisplayName("nach_rollback_kein_send: bei Rollback wird Port nicht aufgerufen")
  void nach_rollback_kein_send() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.invalidate(TENANT_ID, List.of(USER_REF));

      // afterCompletion mit STATUS_ROLLED_BACK ausfuehren (kein afterCommit)
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(s -> s.afterCompletion(
              org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));

      // Port darf NICHT aufgerufen worden sein
      verify(port, never()).send(eq(TENANT_ID), any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // ---- (d) Ohne aktive Tx: direktes Send -------------------------------------------------------

  @Test
  @DisplayName("ohne_tx_direktes_send: kein Tx-Kontext -> Port wird sofort aufgerufen")
  void ohne_tx_direktes_send() {
    // Keine Synchronization aktiv
    service.invalidate(TENANT_ID, List.of(USER_REF));

    verify(port, times(1)).send(eq(TENANT_ID), any());
  }
}
