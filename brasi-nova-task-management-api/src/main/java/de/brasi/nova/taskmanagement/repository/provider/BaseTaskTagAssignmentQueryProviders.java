/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository.provider;

import io.keel.framework.api.query.NovaCriteria;
import io.keel.framework.api.query.NovaCriteriaProvider;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.query.NovaCondition;

import de.brasi.nova.taskmanagement.entity.TaskTagAssignment;
import de.brasi.nova.taskmanagement.query.QTaskTagAssignment;

import lombok.NoArgsConstructor;

public class BaseTaskTagAssignmentQueryProviders<T extends TaskTagAssignment>  {

    public BaseTaskTagAssignmentQueryProviders() {}

    // -------------------------------------------------------------------------
    // AUTO-GENERATED CRITERIA PROVIDERS (FK-basiert, id-only)
    // -------------------------------------------------------------------------
    /**
     * FK-Provider ohne Sortierung: tagId → ByTagId
     * Erwartet als erstes Methodenargument: java.lang.Long tagId
     */
    @NoArgsConstructor 
    public static final class ByTagId
            implements NovaCriteriaProvider<TaskTagAssignment> {

        @Override
        public NovaCriteria<TaskTagAssignment> getCriteria(Object[] args) {
            java.lang.Long tagId = (java.lang.Long) args[0];
            return (root, qb) -> qb.build(
                QTaskTagAssignment.taskTagAssignment.tagId
                    .isEqualTo(tagId)
            );
        }
    }

    /**
     * FK-Provider mit Sortierung (ASC): tagId → ByTagIdSorted
     * Erwartet als erstes Methodenargument: java.lang.Long tagId
     *
     * Standard-Order: taskTagAssignment.id ASC
     * (wenn du stattdessen nach dem FK sortieren willst, setze im ModelBuilder
     *  orderByPropertyName = "tagId")
     */
    @NoArgsConstructor
    public static final class ByTagIdSorted
            implements NovaCriteriaProvider<TaskTagAssignment> {

        @Override
        public NovaCriteria<TaskTagAssignment> getCriteria(Object[] args) {
            java.lang.Long tagId = (java.lang.Long) args[0];
            return (root, qb) -> qb
                .orderBy(
                    qb.asc(QTaskTagAssignment.taskTagAssignment.id)
                )
                .build(
                    QTaskTagAssignment.taskTagAssignment.tagId
                        .isEqualTo(tagId)
                );
        }
    }

    // <NOVA_CLI_CUSTOM_CODE_START SECTION="Methods">
    
    // <NOVA_CLI_CUSTOM_CODE_END SECTION="Methods">
}
