/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.records.summary.TaskCommentSummary;
import de.brasi.nova.taskmanagement.repository.provider.BaseTaskCommentQueryProviders;
import io.keel.framework.api.annotation.db.NovaCriteriaQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.InfoRepository;
import io.keel.framework.api.repository.NovaRepository;
import java.util.List;
import org.springframework.data.repository.query.Param;

public interface BaseTaskCommentRepository extends NovaRepository<TaskComment>, InfoRepository {

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_COMMENT WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_COMMENT\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId")
    List<TaskCommentSummary> findAllInfoSummaries();

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_COMMENT WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId AND ID = :id",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_COMMENT\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId AND \"ID\" = :id")
    TaskCommentSummary findInfoSummary(@Param("id") Long id);

    // --- AUTO-GENERATED FK-FINDER METHODS ---
}
