package de.brasi.nova.taskmanagement.division.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * Antwort von application auf den Abruf der Abteilungsmitglieder (aktueller Stand oder Snapshot).
 * Unbekannte Felder werden ignoriert, damit Erweiterungen in application tm nicht brechen.
 *
 * @param divisionId UUID der Abteilung; muss der angefragten entsprechen
 * @param divisionName Anzeigename der Abteilung; fehlt, solange application ihn nicht liefert
 * @param revision Revision des gelieferten Stands
 * @param members alle Mitglieder des Teilbaums; ohne Feld leer
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DivisionMembersPayload(
    UUID divisionId, String divisionName, String revision, List<DivisionMemberPayload> members) {

  /** Normalisiert die Mitgliederliste: ohne Feld eine leere, unveraenderliche Liste. */
  public DivisionMembersPayload {
    members = members == null ? List.of() : List.copyOf(members);
  }
}
