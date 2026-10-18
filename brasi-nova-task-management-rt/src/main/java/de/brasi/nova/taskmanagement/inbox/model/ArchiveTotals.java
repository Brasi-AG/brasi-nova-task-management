package de.brasi.nova.taskmanagement.inbox.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Archiv-Gesamtzahlen je Filter zum Snapshot-Wasserzeichen (arch-v2 §2.1).
 *
 * <p>Alle drei Zahlen sind benutzer-scoped und {@code <= asOf} gebunden (Snapshot-Konsistenz):
 * {@code all} = Vereinigung aus erledigt ODER verlassen (keine Doppelzaehlung, eine Query),
 * {@code done} = erledigt (DONE/CANCELLED mit aktivem {@code TASK_USER_STATE}),
 * {@code left} = verlassen ({@code UNSUBSCRIBED_AT} ODER {@code EXIT_KIND}).
 *
 * @param all  Gesamtzahl archivierter Aufgaben (erledigt ODER verlassen)
 * @param done Anzahl erledigter Aufgaben
 * @param left Anzahl verlassener Aufgaben
 */
public record ArchiveTotals(
    @Schema(description = "Gesamtzahl archivierter Aufgaben (erledigt ODER verlassen).", example = "12")
    long all,
    @Schema(description = "Anzahl erledigter Aufgaben (DONE/CANCELLED).", example = "10")
    long done,
    @Schema(description = "Anzahl verlassener Aufgaben (EXIT_KIND gesetzt oder UNSUBSCRIBED_AT gesetzt).", example = "2")
    long left) {}
