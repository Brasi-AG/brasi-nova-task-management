package de.brasi.nova.taskmanagement.division.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * Antwort von application auf den Abruf der aktuellen Abteilungsmitglieder. Unbekannte Felder
 * werden ignoriert, damit Erweiterungen in application tm nicht brechen.
 *
 * <p>Der Record normalisiert nichts: Ein fehlendes oder {@code null} gelesenes {@code members}
 * bleibt {@code null} und eine leere Abteilung ist eine leere Liste. Der {@code
 * DivisionMembersClient} weist eine Antwort ohne {@code members}, mit {@code null}-Eintrag, mit
 * Mitglied ohne {@code employeeId} oder mit doppelter {@code employeeId} als technischen Fehler
 * ab; nur geprueft gelieferte Payloads erreichen die Projektion.
 *
 * @param divisionId UUID der Abteilung; muss der angefragten entsprechen
 * @param divisionName Anzeigename der Abteilung; fehlt, solange application ihn nicht liefert
 * @param revision Revision des gelieferten Stands
 * @param members alle Mitglieder des Teilbaums; leere Liste bei leerer Abteilung, {@code null} nur
 *     bei fehlendem Feld in der Antwort
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DivisionMembersPayload(
    UUID divisionId, String divisionName, String revision, List<DivisionMemberPayload> members) {}
