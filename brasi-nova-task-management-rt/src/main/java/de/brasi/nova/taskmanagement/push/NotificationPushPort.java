package de.brasi.nova.taskmanagement.push;

import java.util.UUID;

/**
 * Port: sendet eine Echtzeit-Benachrichtigung an einen Benutzer via WebSocket.
 *
 * <p>Die reale WebSocket-Implementierung wird in Task 15 verdrahtet. In Phase 1a ist
 * standardmaessig eine No-Op-Bean aktiv ({@link NoOpNotificationPushConfig}).
 *
 * <p>Der Aufruf erfolgt bei aktiver Transaktion erst nach erfolgtem Commit (After-Commit-Seam via
 * {@link org.springframework.transaction.support.TransactionSynchronizationManager}); ohne aktive
 * Transaktion direkt. Kein Push innerhalb der DB-Transaktion.
 *
 * <p>{@code tenantId} und {@code recipientRef} sind Routing-Metadaten (NICHT Teil des serialisierten
 * Envelopes), damit die WS-Impl (T15) zielgerichtet an die Sessions des Empfaengers im Mandanten
 * zustellen kann, ohne die Benachrichtigung erneut aus der DB zu laden.
 */
public interface NotificationPushPort {

  /**
   * Sendet eine Benachrichtigung an den Empfaenger im angegebenen Mandanten.
   *
   * @param tenantId     Mandanten-Schluessel des Empfaengers (Routing)
   * @param recipientRef UUID des Empfaengers (PROD_BENUTZER.EXTERNALID, Routing)
   * @param envelope     serialisierbarer Benachrichtigungs-Envelope (arch-v2 §6)
   */
  void push(long tenantId, UUID recipientRef, NotificationPushEnvelope envelope);
}
