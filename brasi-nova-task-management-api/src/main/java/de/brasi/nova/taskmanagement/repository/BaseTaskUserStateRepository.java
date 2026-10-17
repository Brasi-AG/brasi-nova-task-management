/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.records.summary.TaskUserStateSummary;
import de.brasi.nova.taskmanagement.repository.provider.BaseTaskUserStateQueryProviders;
import io.keel.framework.api.annotation.db.NovaCriteriaQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.InfoRepository;
import io.keel.framework.api.repository.NovaRepository;
import java.util.List;
import org.springframework.data.repository.query.Param;

public interface BaseTaskUserStateRepository extends NovaRepository<TaskUserState>, InfoRepository {

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_USER_STATE WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_USER_STATE\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId")
    List<TaskUserStateSummary> findAllInfoSummaries();

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_USER_STATE WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId AND ID = :id",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_USER_STATE\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId AND \"ID\" = :id")
    TaskUserStateSummary findInfoSummary(@Param("id") Long id);

    // --- AUTO-GENERATED FK-FINDER METHODS ---
}
