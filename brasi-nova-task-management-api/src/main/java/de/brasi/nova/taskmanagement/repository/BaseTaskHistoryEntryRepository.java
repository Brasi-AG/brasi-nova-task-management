/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskHistoryEntry;
import de.brasi.nova.taskmanagement.records.summary.TaskHistoryEntrySummary;
import de.brasi.nova.taskmanagement.repository.provider.BaseTaskHistoryEntryQueryProviders;
import io.keel.framework.api.annotation.db.NovaCriteriaQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.InfoRepository;
import io.keel.framework.api.repository.NovaRepository;
import java.util.List;
import org.springframework.data.repository.query.Param;

public interface BaseTaskHistoryEntryRepository extends NovaRepository<TaskHistoryEntry>, InfoRepository {

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_HISTORY_ENTRY WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_HISTORY_ENTRY\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId")
    List<TaskHistoryEntrySummary> findAllInfoSummaries();

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_HISTORY_ENTRY WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId AND ID = :id",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_HISTORY_ENTRY\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId AND \"ID\" = :id")
    TaskHistoryEntrySummary findInfoSummary(@Param("id") Long id);

    // --- AUTO-GENERATED FK-FINDER METHODS ---
}
