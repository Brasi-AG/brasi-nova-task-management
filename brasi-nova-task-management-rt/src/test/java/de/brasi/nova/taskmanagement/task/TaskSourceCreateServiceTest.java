package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.keel.framework.api.exception.BusinessRuleException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class TaskSourceCreateServiceTest {

  @Test
  void onlyReceiptScopeUniqueViolationsAreReplayCandidates() {
    for (final int uniqueCode : new int[] {2601, 2627}) {
      assertThat(
              TaskSourceCreateService.isReceiptScopeUniqueConflict(
                  new BusinessRuleException(
                      "KSF write failed",
                      new SQLException(
                          "Violation of UQ_TASK_SOURCE_CREATE_RECEIPT_SCOPE",
                          "23000",
                          uniqueCode))))
          .isTrue();
      assertThat(
              TaskSourceCreateService.isReceiptScopeUniqueConflict(
                  new BusinessRuleException(
                      "KSF write failed",
                      new SQLException("Violation of UQ_OTHER_CONSTRAINT", "23000", uniqueCode))))
          .isFalse();
    }
    assertThat(
            TaskSourceCreateService.isReceiptScopeUniqueConflict(
                new BusinessRuleException(
                    "KSF write failed",
                    new SQLException(
                        "Deadlock on UQ_TASK_SOURCE_CREATE_RECEIPT_SCOPE", "40001", 1205))))
        .isFalse();
  }
}
