/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository.dictionary;

import de.brasi.nova.taskmanagement.entity.dictionary.TaskPriority;
import de.brasi.nova.taskmanagement.records.summary.dictionary.TaskPrioritySummary;
import de.brasi.nova.taskmanagement.repository.provider.dictionary.BaseTaskPriorityQueryProviders;
import io.keel.framework.api.annotation.db.NovaCriteriaQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.InfoRepository;
import io.keel.framework.api.repository.NovaDictionaryEntryRepository;
import java.util.List;
import org.springframework.data.repository.query.Param;

public interface BaseTaskPriorityRepository extends NovaDictionaryEntryRepository<TaskPriority>, InfoRepository {

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid , ENTRY_TYPE AS dictionaryEntryType, BEZEICHNUNG AS entry FROM DIC_TASK_PRIORITY WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE' AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE()) AND TENANT_ID = :tenantId",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid , \"ENTRY_TYPE\" AS dictionaryEntryType, \"BEZEICHNUNG\" AS entry FROM \"DIC_TASK_PRIORITY\" WHERE /* NOVA:DICTIONARY_VISIBILITY */ \"ENTITY_STATUS\" = 'ACTIVE' AND (\"GUELTIG_BIS\" IS NULL OR \"GUELTIG_BIS\" >= CURRENT_TIMESTAMP) AND \"TENANT_ID\" = :tenantId")
    List<TaskPrioritySummary> findAllInfoSummaries();

    @Override
    @NovaQuery(
        value    = "SELECT ID AS id, EXTERNALID AS uuid , ENTRY_TYPE AS dictionaryEntryType, BEZEICHNUNG AS entry FROM DIC_TASK_PRIORITY WHERE /* NOVA:DICTIONARY_VISIBILITY */ ENTITY_STATUS = 'ACTIVE' AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= GETDATE()) AND TENANT_ID = :tenantId AND ID = :id",
        postgres = "SELECT \"ID\" AS id, \"EXTERNALID\" AS uuid , \"ENTRY_TYPE\" AS dictionaryEntryType, \"BEZEICHNUNG\" AS entry FROM \"DIC_TASK_PRIORITY\" WHERE /* NOVA:DICTIONARY_VISIBILITY */ \"ENTITY_STATUS\" = 'ACTIVE' AND (\"GUELTIG_BIS\" IS NULL OR \"GUELTIG_BIS\" >= CURRENT_TIMESTAMP) AND \"TENANT_ID\" = :tenantId AND \"ID\" = :id")
    TaskPrioritySummary findInfoSummary(@Param("id") Long id);

    // --- AUTO-GENERATED FK-FINDER METHODS ---
}
