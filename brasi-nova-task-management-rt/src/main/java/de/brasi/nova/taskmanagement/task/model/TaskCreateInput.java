package de.brasi.nova.taskmanagement.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Eingabe-DTO fuer die Anlage einer neuen Aufgabe.
 *
 * <p>Direction wird serverseitig auf {@code CUSTOMER_INTERNAL} erzwungen (kein Input-Feld).
 * Prioritaet ist optional; fehlt sie, wird {@code NORMAL} verwendet.
 *
 * @param title        Pflicht-Titel der Aufgabe
 * @param description  optionale Beschreibung
 * @param priorityCode optionaler Prioritaets-CODE (z.B. "HIGH"); fehlt -> Default "NORMAL"
 * @param startDate    optionales Startdatum
 * @param dueDate      optionales Faelligkeitsdatum
 */
public record TaskCreateInput(
    @Schema(description = "Titel der Aufgabe.", example = "Jahresabschluss vorbereiten",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String title,
    @Schema(description = "Optionale Beschreibung der Aufgabe.", example = "Bitte Unterlagen bis Montag bereitstellen.")
    String description,
    @Schema(description = "Prioritaets-CODE (z.B. HIGH, NORMAL, LOW). Fehlt der Wert, wird NORMAL verwendet.",
        example = "NORMAL")
    String priorityCode,
    @Schema(description = "Optionales Startdatum (ISO 8601).", example = "2026-11-01")
    LocalDate startDate,
    @Schema(description = "Optionales Faelligkeitsdatum (ISO 8601).", example = "2026-12-31")
    LocalDate dueDate) {}
