/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskNotification;
import de.brasi.nova.taskmanagement.records.summary.TaskNotificationSummary;
import de.brasi.nova.taskmanagement.repository.provider.BaseTaskNotificationQueryProviders;
import io.keel.framework.api.annotation.db.NovaCriteriaQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.InfoRepository;
import io.keel.framework.api.repository.NovaRepository;
import java.util.List;
import org.springframework.data.repository.query.Param;

public interface BaseTaskNotificationRepository extends NovaRepository<TaskNotification>, InfoRepository {

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_NOTIFICATION WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_NOTIFICATION\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId")
    List<TaskNotificationSummary> findAllInfoSummaries();

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid  FROM TASK_NOTIFICATION WHERE ENTITY_STATUS = 'ACTIVE' AND TENANT_ID = :tenantId AND ID = :id",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid  FROM \"TASK_NOTIFICATION\" WHERE \"ENTITY_STATUS\" = 'ACTIVE' AND \"TENANT_ID\" = :tenantId AND \"ID\" = :id")
    TaskNotificationSummary findInfoSummary(@Param("id") Long id);

    // --- AUTO-GENERATED FK-FINDER METHODS ---
    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskNotificationQueryProviders
                .ByTypeId.class)
    List<TaskNotification>
        findTaskNotificationsByTypeId(Long typeId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskNotificationQueryProviders
                .ByTypeIdSorted.class)
    List<TaskNotification>
        findTaskNotificationsByTypeIdSorted(Long typeId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskNotificationQueryProviders
                .BySeverityId.class)
    List<TaskNotification>
        findTaskNotificationsBySeverityId(Long severityId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskNotificationQueryProviders
                .BySeverityIdSorted.class)
    List<TaskNotification>
        findTaskNotificationsBySeverityIdSorted(Long severityId);

}
