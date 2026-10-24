package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.sql.SQLException;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Validates the request and handles only the receipt-scope unique race outside the create TX. */
@Service
public class TaskSourceCreateService {

  private static final String RECEIPT_SCOPE_CONSTRAINT = "UQ_TASK_SOURCE_CREATE_RECEIPT_SCOPE";

  private final TaskSourceCreateTransaction transaction;
  private final UserAccessor userAccessor;

  public TaskSourceCreateService(
      final TaskSourceCreateTransaction transaction, final UserAccessor userAccessor) {
    this.transaction = transaction;
    this.userAccessor = userAccessor;
  }

  public TaskSourceCreateResult create(final TaskCreateInput input, final String key) {
    if (input.source() == null) {
      throw new EntityValidationException("Quellkontext ist erforderlich.");
    }
    validateKey(key);
    final CurrentActor actor = CurrentActor.require(userAccessor);
    try {
      return transaction.create(input, key, actor);
    } catch (final BusinessRuleException | DataIntegrityViolationException conflict) {
      if (!isReceiptScopeUniqueConflict(conflict)) {
        throw conflict;
      }
      // The proxy has ended the failed transaction before this call enters a fresh one.
      return transaction.replayAfterConflict(input, key, actor);
    }
  }

  private static void validateKey(final String key) {
    if (key == null || key.isEmpty() || key.length() > 128) {
      throw new EntityValidationException("Idempotency-Key muss 1 bis 128 ASCII-Zeichen haben.");
    }
    for (int i = 0; i < key.length(); i++) {
      if (key.charAt(i) < 0x21 || key.charAt(i) > 0x7e) {
        throw new EntityValidationException("Idempotency-Key muss sichtbares ASCII enthalten.");
      }
    }
  }

  static boolean isReceiptScopeUniqueConflict(final Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql
          && (sql.getErrorCode() == 2601 || sql.getErrorCode() == 2627)
          && Objects.toString(sql.getMessage(), "").contains(RECEIPT_SCOPE_CONSTRAINT)) {
        return true;
      }
    }
    return false;
  }
}
