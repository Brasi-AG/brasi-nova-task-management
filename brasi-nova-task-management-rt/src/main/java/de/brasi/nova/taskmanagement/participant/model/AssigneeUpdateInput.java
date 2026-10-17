package de.brasi.nova.taskmanagement.participant.model;

import java.util.Set;
import java.util.UUID;

/**
 * Eingabe-DTO fuer das Aktualisieren der Assignee-Menge einer Aufgabe.
 *
 * <p>Enthaelt die GEWUENSCHTE Ziel-Menge von Assignees (PROD_BENUTZER.EXTERNALID).
 * Vergleich mit dem aktuellen Stand erfolgt im Service:
 * neue UUIDs werden zugewiesen, nicht mehr enthaltene werden abgewaehlt (EXIT_KIND=UNSUBSCRIBED).
 *
 * @param assignees Ziel-Menge der Assignee-UUIDs (darf leer sein, aber nicht null)
 */
public record AssigneeUpdateInput(Set<UUID> assignees) {

  /** Kompakter Konstruktor: setzt leere Menge wenn null uebergeben wird. */
  public AssigneeUpdateInput {
    if (assignees == null) {
      assignees = Set.of();
    }
  }
}
