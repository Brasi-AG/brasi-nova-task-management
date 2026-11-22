package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.division.model.DivisionMembersPayload;

/**
 * Fachliches Ergebnis eines Abrufs der aktuellen Abteilungsmitglieder bei application. Technische
 * Fehler sind kein Ergebnis, sondern eine {@link DivisionFetchException}.
 */
public sealed interface DivisionFetchResult {

  /**
   * HTTP 200: aktueller Stand der Abteilung.
   *
   * @param payload Antwort von application
   */
  record Members(DivisionMembersPayload payload) implements DivisionFetchResult {}

  /** HTTP 404: Abteilung unbekannt, fremd oder inaktiv. */
  record NotFound() implements DivisionFetchResult {}
}
