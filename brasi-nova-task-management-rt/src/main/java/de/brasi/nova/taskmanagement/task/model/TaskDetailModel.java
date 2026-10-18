package de.brasi.nova.taskmanagement.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Antwort-DTO fuer das Detail einer Aufgabe.
 *
 * <p>Alle Status-/Prioritaets-/Direction-Felder werden als CODE-Strings (nie als interne ID oder
 * Enum) nach aussen gegeben.
 *
 * @param id             externe UUID der Aufgabe (EXTERNALID)
 * @param title          Titel der Aufgabe
 * @param description    optionale Beschreibung
 * @param statusCode     aktueller Status-CODE (z.B. "OPEN")
 * @param priorityCode   Prioritaets-CODE (z.B. "NORMAL")
 * @param directionCode  Direction-CODE (z.B. "CUSTOMER_INTERNAL")
 * @param startDate      optionales Startdatum
 * @param dueDate        optionales Faelligkeitsdatum
 * @param lastActivityAt Zeitpunkt der letzten Aktivitaet (UTC)
 * @param commentCount   Anzahl der Kommentare (denormalisiert)
 * @param history        Liste der Historieneintraege (neueste zuerst)
 */
public record TaskDetailModel(
    @Schema(description = "Externe UUID der Aufgabe.", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID id,
    @Schema(description = "Titel der Aufgabe.", example = "Jahresabschluss vorbereiten")
    String title,
    @Schema(description = "Optionale Beschreibung der Aufgabe.", example = "Bitte Unterlagen bis Montag bereitstellen.")
    String description,
    @Schema(description = "Aktueller Status-CODE der Aufgabe.", example = "OPEN")
    String statusCode,
    @Schema(description = "Prioritaets-CODE der Aufgabe.", example = "NORMAL")
    String priorityCode,
    @Schema(description = "Richtungs-CODE der Aufgabe.", example = "CUSTOMER_INTERNAL")
    String directionCode,
    @Schema(description = "Optionales Startdatum (ISO 8601).", example = "2026-11-01")
    LocalDate startDate,
    @Schema(description = "Optionales Faelligkeitsdatum (ISO 8601).", example = "2026-12-31")
    LocalDate dueDate,
    @Schema(description = "Zeitpunkt der letzten Aktivitaet (UTC).", example = "2026-10-18T08:00:00Z")
    Instant lastActivityAt,
    @Schema(description = "Anzahl der Kommentare (denormalisiert).", example = "3")
    int commentCount,
    @Schema(description = "Liste der Historieneintraege (neueste zuerst).")
    List<HistoryModel> history) {}
