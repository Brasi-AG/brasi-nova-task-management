/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository.provider;

import io.keel.framework.api.query.NovaCriteria;
import io.keel.framework.api.query.NovaCriteriaProvider;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.query.NovaCondition;

import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.query.QTaskTag;

import lombok.NoArgsConstructor;

public class BaseTaskTagQueryProviders<T extends TaskTag>  {

    public BaseTaskTagQueryProviders() {}

    // -------------------------------------------------------------------------
    // AUTO-GENERATED CRITERIA PROVIDERS (FK-basiert, id-only)
    // -------------------------------------------------------------------------
    /**
     * FK-Provider ohne Sortierung: severityId → BySeverityId
     * Erwartet als erstes Methodenargument: java.lang.Long severityId
     */
    @NoArgsConstructor 
    public static final class BySeverityId
            implements NovaCriteriaProvider<TaskTag> {

        @Override
        public NovaCriteria<TaskTag> getCriteria(Object[] args) {
            java.lang.Long severityId = (java.lang.Long) args[0];
            return (root, qb) -> qb.build(
                QTaskTag.taskTag.severityId
                    .isEqualTo(severityId)
            );
        }
    }

    /**
     * FK-Provider mit Sortierung (ASC): severityId → BySeverityIdSorted
     * Erwartet als erstes Methodenargument: java.lang.Long severityId
     *
     * Standard-Order: taskTag.id ASC
     * (wenn du stattdessen nach dem FK sortieren willst, setze im ModelBuilder
     *  orderByPropertyName = "severityId")
     */
    @NoArgsConstructor
    public static final class BySeverityIdSorted
            implements NovaCriteriaProvider<TaskTag> {

        @Override
        public NovaCriteria<TaskTag> getCriteria(Object[] args) {
            java.lang.Long severityId = (java.lang.Long) args[0];
            return (root, qb) -> qb
                .orderBy(
                    qb.asc(QTaskTag.taskTag.id)
                )
                .build(
                    QTaskTag.taskTag.severityId
                        .isEqualTo(severityId)
                );
        }
    }

    // <NOVA_CLI_CUSTOM_CODE_START SECTION="Methods">
    
    // <NOVA_CLI_CUSTOM_CODE_END SECTION="Methods">
}
