package de.brasi.nova.taskmanagement.push;

import java.util.Collection;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Reale WebSocket-Implementierung von {@link InboxSignalPort} (Task 15).
 *
 * <p>{@link Primary} verdraengt die No-Op-Bean ({@link NoOpInboxSignalConfig}); delegiert an
 * {@link PushService#pushSnapshotInvalidated(long, Collection)}. Der Aufruf erfolgt nach
 * erfolgtem Commit (After-Commit-Seam in {@code InboxSignalService.invalidate}).
 */
@Component
@Primary
public class WebSocketInboxSignalPort implements InboxSignalPort {

  private final PushService pushService;

  /** Konstruktor-Injektion. */
  public WebSocketInboxSignalPort(final PushService pushService) {
    this.pushService = pushService;
  }

  @Override
  public void send(final long tenantId, final Collection<UUID> userRefs) {
    pushService.pushSnapshotInvalidated(tenantId, userRefs);
  }
}
