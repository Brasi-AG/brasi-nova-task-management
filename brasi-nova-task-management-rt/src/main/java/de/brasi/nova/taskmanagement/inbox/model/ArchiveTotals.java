package de.brasi.nova.taskmanagement.inbox.model;

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
public record ArchiveTotals(long all, long done, long left) {}
