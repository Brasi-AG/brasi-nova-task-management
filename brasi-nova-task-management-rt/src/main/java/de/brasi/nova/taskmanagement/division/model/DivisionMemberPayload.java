package de.brasi.nova.taskmanagement.division.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Ein Mitglied in der Antwort von application. Unbekannte Felder werden ignoriert.
 *
 * @param employeeId Benutzer-UUID des Mitglieds
 * @param displayName Anzeigename
 * @param firstName Vorname
 * @param lastName Nachname
 * @param loginName Anmeldename
 * @param coreMembership Kennzeichen Kernmitglied; bewusst {@link Boolean}: ein fehlendes Feld
 *     bleibt {@code null} und wird nicht zu {@code false}. Der {@code DivisionMembersClient} weist
 *     ein Mitglied ohne Wert als unbrauchbare Antwort ab
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DivisionMemberPayload(
    UUID employeeId,
    String displayName,
    String firstName,
    String lastName,
    String loginName,
    Boolean coreMembership) {}
