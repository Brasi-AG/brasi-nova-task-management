package de.brasi.nova.taskmanagement.division;

/** Ergebnis von {@link DivisionProjectionService#apply}. */
public enum DivisionApplyOutcome {
  /** Kopf angelegt oder aktualisiert, Mitglieder ersetzt. */
  UPDATED,
  /** Der gespeicherte Stand stammt aus einem gleich alten oder spaeteren Abruf; nichts geaendert. */
  STALE,
  /** Abteilung bei application nicht gefunden: Kopf deaktiviert, Mitglieder geloescht. */
  DEACTIVATED,
  /** Abteilung bei application nicht gefunden und lokal nie gesehen: nichts angelegt. */
  IGNORED
}
