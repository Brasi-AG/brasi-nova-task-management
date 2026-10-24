package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskSourceCreateReceipt;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/** KSF repository for the durable source-create claim and replay receipt. */
public interface TaskSourceCreateReceiptQueryRepository
    extends BaseTaskSourceCreateReceiptRepository {

  /**
   * Serializes the absent-key case on the exact unique index until the surrounding transaction
   * commits or rolls back. On MSSQL, UPDLOCK + HOLDLOCK takes a key-range lock under READ
   * COMMITTED.
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_SOURCE_CREATE_RECEIPT WHERE TENANT_ID = :tenantId "
              + "AND TENANT_UUID = :tenantUuid AND ACTOR_REF = :actorRef "
              + "AND IDEMPOTENCY_KEY = :idempotencyKey",
      mssql =
          "SELECT * FROM [dbo].[TASK_SOURCE_CREATE_RECEIPT] "
              + "WITH (UPDLOCK, HOLDLOCK, INDEX(UQ_TASK_SOURCE_CREATE_RECEIPT_SCOPE)) "
              + "WHERE TENANT_ID = :tenantId AND TENANT_UUID = :tenantUuid "
              + "AND ACTOR_REF = :actorRef AND IDEMPOTENCY_KEY = :idempotencyKey")
  List<TaskSourceCreateReceipt> findForClaim(
      @Param("tenantUuid") UUID tenantUuid,
      @Param("actorRef") UUID actorRef,
      @Param("idempotencyKey") String idempotencyKey);

  @NovaQuery(
      value =
          "SELECT * FROM TASK_SOURCE_CREATE_RECEIPT WHERE TENANT_ID = :tenantId "
              + "AND TENANT_UUID = :tenantUuid AND ACTOR_REF = :actorRef "
              + "AND IDEMPOTENCY_KEY = :idempotencyKey",
      mssql =
          "SELECT * FROM [dbo].[TASK_SOURCE_CREATE_RECEIPT] WHERE TENANT_ID = :tenantId "
              + "AND TENANT_UUID = :tenantUuid AND ACTOR_REF = :actorRef "
              + "AND IDEMPOTENCY_KEY = :idempotencyKey")
  List<TaskSourceCreateReceipt> findForReplay(
      @Param("tenantUuid") UUID tenantUuid,
      @Param("actorRef") UUID actorRef,
      @Param("idempotencyKey") String idempotencyKey);
}
