package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskSourceCreateReceipt;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskPriorityCodes;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskSourceCreateReceiptQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.resolver.DictionaryResolverException;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import de.brasi.nova.taskmanagement.task.model.TaskSourceInput;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Each call enters through a Spring transaction proxy; KSF repositories perform all writes. */
@Service
public class TaskSourceCreateTransaction {

  private final TaskSourceCreateReceiptQueryRepository receiptRepository;
  private final BaseTaskRepository taskRepository;
  private final TaskCommandService commandService;
  private final TaskSourceRegistry sourceRegistry;
  private final TaskMembershipGuard membershipGuard;
  private final DictionaryCodeResolver resolver;

  public TaskSourceCreateTransaction(
      final TaskSourceCreateReceiptQueryRepository receiptRepository,
      final BaseTaskRepository taskRepository,
      final TaskCommandService commandService,
      final TaskSourceRegistry sourceRegistry,
      final TaskMembershipGuard membershipGuard,
      final DictionaryCodeResolver resolver) {
    this.receiptRepository = receiptRepository;
    this.taskRepository = taskRepository;
    this.commandService = commandService;
    this.sourceRegistry = sourceRegistry;
    this.membershipGuard = membershipGuard;
    this.resolver = resolver;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public TaskSourceCreateResult create(
      final TaskCreateInput input, final String key, final CurrentActor actor) {
    sourceRegistry.requireReadable(input.source(), actor);
    final String hash = fingerprint(input, actor);
    final List<TaskSourceCreateReceipt> found =
        receiptRepository.findForClaim(actor.tenant().uuid(), actor.user().id(), key);
    if (!found.isEmpty()) {
      return replay(found.get(0), input, hash, actor);
    }

    final UUID taskUuid = commandService.create(input);
    final TaskSourceCreateReceipt receipt = new TaskSourceCreateReceipt();
    receipt.setTenantId(actor.tenant().id());
    receipt.setTenantUuid(actor.tenant().uuid());
    receipt.setActorRef(actor.user().id());
    receipt.setIdempotencyKey(key);
    receipt.setInputSha256(hash);
    receipt.setTaskUuid(taskUuid);
    receiptRepository.save(receipt);
    return new TaskSourceCreateResult(taskUuid, true);
  }

  /** Invoked only after the failed create transaction has rolled back. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public TaskSourceCreateResult replayAfterConflict(
      final TaskCreateInput input, final String key, final CurrentActor actor) {
    sourceRegistry.requireReadable(input.source(), actor);
    final String hash = fingerprint(input, actor);
    final List<TaskSourceCreateReceipt> found =
        receiptRepository.findForReplay(actor.tenant().uuid(), actor.user().id(), key);
    if (found.isEmpty()) {
      throw new IllegalStateException("Idempotency-Receipt nach Unique-Konflikt nicht sichtbar.");
    }
    return replay(found.get(0), input, hash, actor);
  }

  private TaskSourceCreateResult replay(
      final TaskSourceCreateReceipt receipt,
      final TaskCreateInput input,
      final String hash,
      final CurrentActor actor) {
    // Recheck after a possible wait on the unique-key range lock, directly before any ID is used.
    sourceRegistry.requireReadable(input.source(), actor);
    final Task task =
        taskRepository
            .findByUuid(receipt.getTaskUuid().toString())
            .orElseThrow(() -> new EntityNotFoundException("Aufgabe nicht gefunden."));
    if (!Objects.equals(task.getTenantId(), actor.tenant().id())
        || task.getEntityState() != EntityState.ACTIVE) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    // The existing write-member predicate is exactly active PERSON membership (EXIT_KIND null);
    // it does not check ASSIGNEE_FLAG or any TASK_WRITE authority. Former members cannot replay.
    membershipGuard.requireWriteMember(task.getId(), actor.user().id(), actor.tenant().id());
    requireStoredSourceReadable(task, actor);
    if (!Objects.equals(receipt.getInputSha256(), hash)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Idempotency-Key wurde fuer eine andere Eingabe verwendet.");
    }
    return new TaskSourceCreateResult(task.getUuid(), false);
  }

  private void requireStoredSourceReadable(final Task task, final CurrentActor actor) {
    try {
      sourceRegistry.requireReadable(
          new TaskSourceInput(
              task.getSourceModule(), task.getSourceEntityType(), task.getSourceEntityRef()),
          actor);
    } catch (final EntityValidationException unregisteredOrMalformed) {
      // Stored source metadata is not client input. Do not expose its registry state on replay.
      throw new EntityNotFoundException("Quelle nicht gefunden.");
    }
  }

  private String fingerprint(final TaskCreateInput input, final CurrentActor actor) {
    final String requestedCode =
        input.priorityCode() == null || input.priorityCode().isBlank()
            ? TaskPriorityCodes.NORMAL
            : input.priorityCode();
    final String canonicalCode;
    try {
      canonicalCode = resolver.resolveTaskPriorityCode(resolver.resolveTaskPriority(requestedCode));
    } catch (final DictionaryResolverException unknown) {
      throw new EntityValidationException("Ungueltiger priorityCode: '" + requestedCode + "'.");
    }
    return TaskSourceCreateFingerprint.sha256(input, actor.user().id(), canonicalCode);
  }
}
