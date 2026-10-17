/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskTagAssignment;
import de.brasi.nova.taskmanagement.records.summary.TaskTagAssignmentSummary;
import de.brasi.nova.taskmanagement.repository.provider.BaseTaskTagAssignmentQueryProviders;
import io.keel.framework.api.annotation.db.NovaCriteriaQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.InfoRepository;
import io.keel.framework.api.repository.NovaRepository;
import java.util.List;
import org.springframework.data.repository.query.Param;

public interface BaseTaskTagAssignmentRepository extends NovaRepository<TaskTagAssignment>, InfoRepository {

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_TAG_ASSIGNMENT WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_TAG_ASSIGNMENT\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId")
    List<TaskTagAssignmentSummary> findAllInfoSummaries();

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_TAG_ASSIGNMENT WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId AND ID = :id",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_TAG_ASSIGNMENT\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId AND \"ID\" = :id")
    TaskTagAssignmentSummary findInfoSummary(@Param("id") Long id);

    // --- AUTO-GENERATED FK-FINDER METHODS ---
    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskTagAssignmentQueryProviders
                .ByTagId.class)
    List<TaskTagAssignment>
        findTaskTagAssignmentsByTagId(Long tagId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskTagAssignmentQueryProviders
                .ByTagIdSorted.class)
    List<TaskTagAssignment>
        findTaskTagAssignmentsByTagIdSorted(Long tagId);

}
