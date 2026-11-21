package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.division.model.DivisionMembersPayload;

/**
 * Fachliches Ergebnis eines Abrufs der Abteilungsmitglieder bei application. Technische Fehler sind
 * kein Ergebnis, sondern eine {@link DivisionFetchException}.
 */
public sealed interface DivisionFetchResult {

  /**
   * HTTP 200: aktueller Stand bzw. Stand der angefragten Revision.
   *
   * @param payload Antwort von application
   */
  record Members(DivisionMembersPayload payload) implements DivisionFetchResult {}

  /** HTTP 404: Abteilung unbekannt, fremd oder inaktiv. */
  record NotFound() implements DivisionFetchResult {}

  /** HTTP 410 beim Snapshot: Revision nicht mehr vorhanden, aktuellen Stand laden. */
  record Gone() implements DivisionFetchResult {}

  /** HTTP 400 beim Snapshot: Revision ungueltig, aktuellen Stand laden. */
  record BadRevision() implements DivisionFetchResult {}
}
