package de.brasi.nova.taskmanagement.push;

/**
 * Port: sendet eine Echtzeit-Benachrichtigung an einen Benutzer via WebSocket.
 *
 * <p>Die reale WebSocket-Implementierung wird in Task 15 verdrahtet. In Phase 1a ist
 * standardmaessig eine No-Op-Bean aktiv ({@link NoOpNotificationPushConfig}).
 *
 * <p>Der Aufruf erfolgt IMMER nach erfolgtem Commit (After-Commit-Seam via
 * {@link org.springframework.transaction.support.TransactionSynchronizationManager}).
 * Kein Push innerhalb der DB-Transaktion.
 *
 * <p>Push-Envelope (arch-v2 §6):
 * {@code {type, version="1", notificationId, timestamp(UTC Z), severity, payload{text, preview}}}.
 */
public interface NotificationPushPort {

  /**
   * Sendet eine Benachrichtigung an den Empfaenger.
   *
   * @param envelope Envelope-Objekt mit allen Pflichtfeldern
   */
  void push(NotificationPushEnvelope envelope);
}
