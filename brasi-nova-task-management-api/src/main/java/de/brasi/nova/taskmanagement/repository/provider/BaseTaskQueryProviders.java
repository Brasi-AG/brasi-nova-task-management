/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository.provider;

import io.keel.framework.api.query.NovaCriteria;
import io.keel.framework.api.query.NovaCriteriaProvider;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.query.NovaCondition;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.query.QTask;

import lombok.NoArgsConstructor;

public class BaseTaskQueryProviders<T extends Task>  {

    public BaseTaskQueryProviders() {}

    // -------------------------------------------------------------------------
    // AUTO-GENERATED CRITERIA PROVIDERS (FK-basiert, id-only)
    // -------------------------------------------------------------------------
    /**
     * FK-Provider ohne Sortierung: taskStatusId → ByTaskStatusId
     * Erwartet als erstes Methodenargument: java.lang.Long taskStatusId
     */
    @NoArgsConstructor 
    public static final class ByTaskStatusId
            implements NovaCriteriaProvider<Task> {

        @Override
        public NovaCriteria<Task> getCriteria(Object[] args) {
            java.lang.Long taskStatusId = (java.lang.Long) args[0];
            return (root, qb) -> qb.build(
                QTask.task.taskStatusId
                    .isEqualTo(taskStatusId)
            );
        }
    }

    /**
     * FK-Provider mit Sortierung (ASC): taskStatusId → ByTaskStatusIdSorted
     * Erwartet als erstes Methodenargument: java.lang.Long taskStatusId
     *
     * Standard-Order: task.id ASC
     * (wenn du stattdessen nach dem FK sortieren willst, setze im ModelBuilder
     *  orderByPropertyName = "taskStatusId")
     */
    @NoArgsConstructor
    public static final class ByTaskStatusIdSorted
            implements NovaCriteriaProvider<Task> {

        @Override
        public NovaCriteria<Task> getCriteria(Object[] args) {
            java.lang.Long taskStatusId = (java.lang.Long) args[0];
            return (root, qb) -> qb
                .orderBy(
                    qb.asc(QTask.task.id)
                )
                .build(
                    QTask.task.taskStatusId
                        .isEqualTo(taskStatusId)
                );
        }
    }
    /**
     * FK-Provider ohne Sortierung: priorityId → ByPriorityId
     * Erwartet als erstes Methodenargument: java.lang.Long priorityId
     */
    @NoArgsConstructor 
    public static final class ByPriorityId
            implements NovaCriteriaProvider<Task> {

        @Override
        public NovaCriteria<Task> getCriteria(Object[] args) {
            java.lang.Long priorityId = (java.lang.Long) args[0];
            return (root, qb) -> qb.build(
                QTask.task.priorityId
                    .isEqualTo(priorityId)
            );
        }
    }

    /**
     * FK-Provider mit Sortierung (ASC): priorityId → ByPriorityIdSorted
     * Erwartet als erstes Methodenargument: java.lang.Long priorityId
     *
     * Standard-Order: task.id ASC
     * (wenn du stattdessen nach dem FK sortieren willst, setze im ModelBuilder
     *  orderByPropertyName = "priorityId")
     */
    @NoArgsConstructor
    public static final class ByPriorityIdSorted
            implements NovaCriteriaProvider<Task> {

        @Override
        public NovaCriteria<Task> getCriteria(Object[] args) {
            java.lang.Long priorityId = (java.lang.Long) args[0];
            return (root, qb) -> qb
                .orderBy(
                    qb.asc(QTask.task.id)
                )
                .build(
                    QTask.task.priorityId
                        .isEqualTo(priorityId)
                );
        }
    }
    /**
     * FK-Provider ohne Sortierung: directionId → ByDirectionId
     * Erwartet als erstes Methodenargument: java.lang.Long directionId
     */
    @NoArgsConstructor 
    public static final class ByDirectionId
            implements NovaCriteriaProvider<Task> {

        @Override
        public NovaCriteria<Task> getCriteria(Object[] args) {
            java.lang.Long directionId = (java.lang.Long) args[0];
            return (root, qb) -> qb.build(
                QTask.task.directionId
                    .isEqualTo(directionId)
            );
        }
    }

    /**
     * FK-Provider mit Sortierung (ASC): directionId → ByDirectionIdSorted
     * Erwartet als erstes Methodenargument: java.lang.Long directionId
     *
     * Standard-Order: task.id ASC
     * (wenn du stattdessen nach dem FK sortieren willst, setze im ModelBuilder
     *  orderByPropertyName = "directionId")
     */
    @NoArgsConstructor
    public static final class ByDirectionIdSorted
            implements NovaCriteriaProvider<Task> {

        @Override
        public NovaCriteria<Task> getCriteria(Object[] args) {
            java.lang.Long directionId = (java.lang.Long) args[0];
            return (root, qb) -> qb
                .orderBy(
                    qb.asc(QTask.task.id)
                )
                .build(
                    QTask.task.directionId
                        .isEqualTo(directionId)
                );
        }
    }

    // <NOVA_CLI_CUSTOM_CODE_START SECTION="Methods">
    
    // <NOVA_CLI_CUSTOM_CODE_END SECTION="Methods">
}
