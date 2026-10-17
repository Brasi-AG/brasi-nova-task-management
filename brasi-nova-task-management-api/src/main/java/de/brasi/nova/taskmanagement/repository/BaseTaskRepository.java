/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.records.summary.TaskSummary;
import de.brasi.nova.taskmanagement.repository.provider.BaseTaskQueryProviders;
import io.keel.framework.api.annotation.db.NovaCriteriaQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.InfoRepository;
import io.keel.framework.api.repository.NovaRepository;
import java.util.List;
import org.springframework.data.repository.query.Param;

public interface BaseTaskRepository extends NovaRepository<Task>, InfoRepository {

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId")
    List<TaskSummary> findAllInfoSummaries();

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId AND ID = :id",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId AND \"ID\" = :id")
    TaskSummary findInfoSummary(@Param("id") Long id);

    // --- AUTO-GENERATED FK-FINDER METHODS ---
    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskQueryProviders
                .ByTaskStatusId.class)
    List<Task>
        findTasksByTaskStatusId(Long taskStatusId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskQueryProviders
                .ByTaskStatusIdSorted.class)
    List<Task>
        findTasksByTaskStatusIdSorted(Long taskStatusId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskQueryProviders
                .ByPriorityId.class)
    List<Task>
        findTasksByPriorityId(Long priorityId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskQueryProviders
                .ByPriorityIdSorted.class)
    List<Task>
        findTasksByPriorityIdSorted(Long priorityId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskQueryProviders
                .ByDirectionId.class)
    List<Task>
        findTasksByDirectionId(Long directionId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskQueryProviders
                .ByDirectionIdSorted.class)
    List<Task>
        findTasksByDirectionIdSorted(Long directionId);

}
