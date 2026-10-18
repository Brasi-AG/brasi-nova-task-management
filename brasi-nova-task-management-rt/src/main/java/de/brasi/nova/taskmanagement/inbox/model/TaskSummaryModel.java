package de.brasi.nova.taskmanagement.inbox.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
    @Schema(description = "Externe UUID der Aufgabe.", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID id,
    @Schema(description = "Titel der Aufgabe.", example = "Jahresabschluss vorbereiten")
    String title,
    @Schema(description = "Aktueller Status-CODE.", example = "OPEN")
    String statusCode,
    @Schema(description = "Prioritaets-CODE.", example = "NORMAL")
    String priorityCode,
    @Schema(description = "Optionales Faelligkeitsdatum.", example = "2026-12-31")
    LocalDate dueDate,
    @Schema(description = "Zeitpunkt der letzten Aktivitaet (UTC).", example = "2026-10-18T08:00:00Z")
    Instant lastActivityAt,
    @Schema(description = "Anzahl der Kommentare (denormalisiert).", example = "0")
    int commentCount) {}
