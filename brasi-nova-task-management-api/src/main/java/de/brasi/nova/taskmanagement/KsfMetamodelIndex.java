/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement;

import java.util.List;

/*
 * Auto-generierter Metamodell-Index (SPEC-KSF §2.7).
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jedem Scaffold-Lauf überschrieben.
 */

/**
 * Statische Registrierung aller gescannten Entity-Klassen dieses Moduls.
 * Die ksf-Runtime kann damit auf einen Classpath-Scan verzichten.
 */
public final class KsfMetamodelIndex {

    private KsfMetamodelIndex() {}

    private static final List<Class<?>> ENTITIES = List.of(
        de.brasi.nova.taskmanagement.entity.Task.class,
        de.brasi.nova.taskmanagement.entity.TaskComment.class,
        de.brasi.nova.taskmanagement.entity.TaskHandover.class,
        de.brasi.nova.taskmanagement.entity.TaskHandoverTarget.class,
        de.brasi.nova.taskmanagement.entity.TaskHistoryEntry.class,
        de.brasi.nova.taskmanagement.entity.TaskInboxGeneration.class,
        de.brasi.nova.taskmanagement.entity.TaskNotification.class,
        de.brasi.nova.taskmanagement.entity.TaskParticipant.class,
        de.brasi.nova.taskmanagement.entity.TaskReminderReceipt.class,
        de.brasi.nova.taskmanagement.entity.TaskTag.class,
        de.brasi.nova.taskmanagement.entity.TaskTagAssignment.class,
        de.brasi.nova.taskmanagement.entity.TaskUserState.class,
        de.brasi.nova.taskmanagement.entity.dictionary.NotificationSeverity.class,
        de.brasi.nova.taskmanagement.entity.dictionary.NotificationType.class,
        de.brasi.nova.taskmanagement.entity.dictionary.TagSeverity.class,
        de.brasi.nova.taskmanagement.entity.dictionary.TaskDirection.class,
        de.brasi.nova.taskmanagement.entity.dictionary.TaskPriority.class,
        de.brasi.nova.taskmanagement.entity.dictionary.TaskStatus.class
    );

    /**
     * Liefert alle vom Scaffolder erfassten Entity-Klassen.
     *
     * @return unveränderliche Liste aller Entity-Klassen
     */
    public static List<Class<?>> entities() {
        return ENTITIES;
    }
}
