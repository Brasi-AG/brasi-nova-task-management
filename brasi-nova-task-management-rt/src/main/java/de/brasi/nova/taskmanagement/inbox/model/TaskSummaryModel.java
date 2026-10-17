package de.brasi.nova.taskmanagement.inbox.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Kompakte Listenzeilen-Repraesentation einer Aufgabe fuer Snapshot/Inbox/Archiv (arch-v2 §2.1,
 * FE-Proof §B.4).
 *
 * <p>Status/Prioritaet werden als CODE-Strings (nie interne ID/Enum) nach aussen gegeben. Bewusst
 * NICHT enthalten (1a-Scope eingefroren): {@code directionCode} (in 1a fix CUSTOMER_INTERNAL, nicht
 * FE-sichtbar), {@code description}, {@code history}, persoenliche Marker (pin/flag/mute),
 * Assignees und Tags pro Zeile (FE-Proof §B.4, Phase-2/Folgetrack).
 *
 * @param id             externe UUID der Aufgabe (EXTERNALID)
 * @param title          Titel der Aufgabe
 * @param statusCode     aktueller Status-CODE (z.B. "OPEN")
 * @param priorityCode   Prioritaets-CODE (z.B. "NORMAL")
 * @param dueDate        optionales Faelligkeitsdatum
 * @param lastActivityAt Zeitpunkt der letzten Aktivitaet (AuditTimeConverter-Instant)
 * @param commentCount   Anzahl der Kommentare (denormalisiert)
 */
public record TaskSummaryModel(
    UUID id,
    String title,
    String statusCode,
    String priorityCode,
    LocalDate dueDate,
    Instant lastActivityAt,
    int commentCount) {}
