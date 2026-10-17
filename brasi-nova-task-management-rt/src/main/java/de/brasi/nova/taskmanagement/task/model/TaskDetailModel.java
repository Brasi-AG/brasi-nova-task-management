package de.brasi.nova.taskmanagement.task.model;

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
    UUID id,
    String title,
    String description,
    String statusCode,
    String priorityCode,
    String directionCode,
    LocalDate startDate,
    LocalDate dueDate,
    Instant lastActivityAt,
    int commentCount,
    List<HistoryModel> history) {}
