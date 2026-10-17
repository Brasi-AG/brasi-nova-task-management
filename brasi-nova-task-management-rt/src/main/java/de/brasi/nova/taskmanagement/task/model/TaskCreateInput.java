package de.brasi.nova.taskmanagement.task.model;

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
    String title,
    String description,
    String priorityCode,
    LocalDate startDate,
    LocalDate dueDate) {}
