package de.brasi.nova.taskmanagement.security;

import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Internal authorization for task document reads. The caller supplies {@code actorRef} only from
 * the authenticated {@link CurrentActor}, never from request data. No document endpoint is wired to
 * this guard yet.
 *
 * <p>The repository binds {@code :tenantId} from {@link TenantContextHolder}; the explicit tenant
 * argument must match that trusted context before any lookup. All denial reasons share one neutral
 * 404 response.
 */
@Component
public class TaskDocumentAccessGuard {

  private final TaskParticipantQueryRepository repository;

  public TaskDocumentAccessGuard(final TaskParticipantQueryRepository repository) {
    this.repository = repository;
  }

  public void requireRead(final Long taskId, final UUID actorRef, final long tenantId) {
    if (TenantContextHolder.getTenant().map(TenantInfo::id).filter(id -> id == tenantId).isEmpty()
        || !repository.existsDocumentReadAccess(taskId, actorRef)) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
  }
}
