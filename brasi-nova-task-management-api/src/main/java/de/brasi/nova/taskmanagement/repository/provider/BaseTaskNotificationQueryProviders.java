/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.repository.provider;

import io.keel.framework.api.query.NovaCriteria;
import io.keel.framework.api.query.NovaCriteriaProvider;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.query.NovaCondition;

import de.brasi.nova.taskmanagement.entity.TaskNotification;
import de.brasi.nova.taskmanagement.query.QTaskNotification;

import lombok.NoArgsConstructor;

public class BaseTaskNotificationQueryProviders<T extends TaskNotification>  {

    public BaseTaskNotificationQueryProviders() {}

    // -------------------------------------------------------------------------
    // AUTO-GENERATED CRITERIA PROVIDERS (FK-basiert, id-only)
    // -------------------------------------------------------------------------
    /**
     * FK-Provider ohne Sortierung: typeId → ByTypeId
     * Erwartet als erstes Methodenargument: java.lang.Long typeId
     */
    @NoArgsConstructor 
    public static final class ByTypeId
            implements NovaCriteriaProvider<TaskNotification> {

        @Override
        public NovaCriteria<TaskNotification> getCriteria(Object[] args) {
            java.lang.Long typeId = (java.lang.Long) args[0];
            return (root, qb) -> qb.build(
                QTaskNotification.taskNotification.typeId
                    .isEqualTo(typeId)
            );
        }
    }

    /**
     * FK-Provider mit Sortierung (ASC): typeId → ByTypeIdSorted
     * Erwartet als erstes Methodenargument: java.lang.Long typeId
     *
     * Standard-Order: taskNotification.id ASC
     * (wenn du stattdessen nach dem FK sortieren willst, setze im ModelBuilder
     *  orderByPropertyName = "typeId")
     */
    @NoArgsConstructor
    public static final class ByTypeIdSorted
            implements NovaCriteriaProvider<TaskNotification> {

        @Override
        public NovaCriteria<TaskNotification> getCriteria(Object[] args) {
            java.lang.Long typeId = (java.lang.Long) args[0];
            return (root, qb) -> qb
                .orderBy(
                    qb.asc(QTaskNotification.taskNotification.id)
                )
                .build(
                    QTaskNotification.taskNotification.typeId
                        .isEqualTo(typeId)
                );
        }
    }
    /**
     * FK-Provider ohne Sortierung: severityId → BySeverityId
     * Erwartet als erstes Methodenargument: java.lang.Long severityId
     */
    @NoArgsConstructor 
    public static final class BySeverityId
            implements NovaCriteriaProvider<TaskNotification> {

        @Override
        public NovaCriteria<TaskNotification> getCriteria(Object[] args) {
            java.lang.Long severityId = (java.lang.Long) args[0];
            return (root, qb) -> qb.build(
                QTaskNotification.taskNotification.severityId
                    .isEqualTo(severityId)
            );
        }
    }

    /**
     * FK-Provider mit Sortierung (ASC): severityId → BySeverityIdSorted
     * Erwartet als erstes Methodenargument: java.lang.Long severityId
     *
     * Standard-Order: taskNotification.id ASC
     * (wenn du stattdessen nach dem FK sortieren willst, setze im ModelBuilder
     *  orderByPropertyName = "severityId")
     */
    @NoArgsConstructor
    public static final class BySeverityIdSorted
            implements NovaCriteriaProvider<TaskNotification> {

        @Override
        public NovaCriteria<TaskNotification> getCriteria(Object[] args) {
            java.lang.Long severityId = (java.lang.Long) args[0];
            return (root, qb) -> qb
                .orderBy(
                    qb.asc(QTaskNotification.taskNotification.id)
                )
                .build(
                    QTaskNotification.taskNotification.severityId
                        .isEqualTo(severityId)
                );
        }
    }

    // <NOVA_CLI_CUSTOM_CODE_START SECTION="Methods">
    
    // <NOVA_CLI_CUSTOM_CODE_END SECTION="Methods">
}
