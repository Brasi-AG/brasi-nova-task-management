package de.brasi.nova.taskmanagement.push;

import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Reale WebSocket-Implementierung von {@link NotificationPushPort} (Task 15).
 *
 * <p>{@link Primary} verdraengt die No-Op-Bean ({@link NoOpNotificationPushConfig}); delegiert an
 * {@link PushService}, der an die offenen Sessions des Empfaengers zustellt. Der Aufruf erfolgt
 * bereits nach erfolgtem Commit (After-Commit-Seam in {@code NotificationService.emit}).
 */
@Component
@Primary
public class WebSocketNotificationPushPort implements NotificationPushPort {

  private final PushService pushService;

  /** Konstruktor-Injektion. */
  public WebSocketNotificationPushPort(final PushService pushService) {
    this.pushService = pushService;
  }

  @Override
  public void push(
      final long tenantId, final UUID recipientRef, final NotificationPushEnvelope envelope) {
    pushService.push(tenantId, recipientRef, envelope);
  }
}
