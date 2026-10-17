/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.records.summary.TaskTagSummary;
import de.brasi.nova.taskmanagement.repository.provider.BaseTaskTagQueryProviders;
import io.keel.framework.api.annotation.db.NovaCriteriaQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.InfoRepository;
import io.keel.framework.api.repository.NovaDictionaryEntryRepository;
import java.util.List;
import org.springframework.data.repository.query.Param;

public interface BaseTaskTagRepository extends NovaDictionaryEntryRepository<TaskTag>, InfoRepository {

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid , ENTRY_TYPE AS dictionaryEntryType, BEZEICHNUNG AS entry FROM TASK_TAG WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE' AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE()) AND TENANT_ID = :tenantId",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid , \"ENTRY_TYPE\" AS dictionaryEntryType, \"BEZEICHNUNG\" AS entry FROM \"TASK_TAG\" WHERE /* NOVA:DICTIONARY_VISIBILITY */ \"ENTITY_STATUS\" = 'ACTIVE' AND (\"GUELTIG_BIS\" IS NULL OR \"GUELTIG_BIS\" >= CURRENT_TIMESTAMP) AND \"TENANT_ID\" = :tenantId")
    List<TaskTagSummary> findAllInfoSummaries();

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid , ENTRY_TYPE AS dictionaryEntryType, BEZEICHNUNG AS entry FROM TASK_TAG WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE' AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE()) AND TENANT_ID = :tenantId AND ID = :id",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid , \"ENTRY_TYPE\" AS dictionaryEntryType, \"BEZEICHNUNG\" AS entry FROM \"TASK_TAG\" WHERE /* NOVA:DICTIONARY_VISIBILITY */ \"ENTITY_STATUS\" = 'ACTIVE' AND (\"GUELTIG_BIS\" IS NULL OR \"GUELTIG_BIS\" >= CURRENT_TIMESTAMP) AND \"TENANT_ID\" = :tenantId AND \"ID\" = :id")
    TaskTagSummary findInfoSummary(@Param("id") Long id);

    // --- AUTO-GENERATED FK-FINDER METHODS ---
    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskTagQueryProviders
                .BySeverityId.class)
    List<TaskTag>
        findTaskTagsBySeverityId(Long severityId);

    @NovaCriteriaQuery(
        provider =
            de.brasi.nova.taskmanagement.repository.provider.BaseTaskTagQueryProviders
                .BySeverityIdSorted.class)
    List<TaskTag>
        findTaskTagsBySeverityIdSorted(Long severityId);

}
