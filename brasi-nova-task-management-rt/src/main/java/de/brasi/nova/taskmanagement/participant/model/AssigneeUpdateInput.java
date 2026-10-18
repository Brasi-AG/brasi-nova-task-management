package de.brasi.nova.taskmanagement.participant.model;

import io.keel.framework.api.validation.EntityValidationException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import java.util.UUID;

/**
 * Eingabe-DTO fuer das Aktualisieren der Assignee-Menge einer Aufgabe.
 *
 * <p>Enthaelt die GEWUENSCHTE Ziel-Menge von Assignees (PROD_BENUTZER.EXTERNALID).
 * Vergleich mit dem aktuellen Stand erfolgt im Service:
 * neue UUIDs werden zugewiesen, nicht mehr enthaltene werden abgewaehlt (EXIT_KIND=UNSUBSCRIBED).
 *
 * <p>Validierungsregeln:
 * <ul>
 *   <li>{@code assignees} darf NICHT {@code null} sein (fehlendes Feld / explizit null im JSON
 *       wird als 400 abgelehnt). Nur ein explizites leeres Array {@code []} ist erlaubt und
 *       bewirkt, dass alle Nicht-Creator-Assignees abgewaehlt werden.
 *   <li>Kein Element innerhalb der Menge darf {@code null} sein.
 * </ul>
 *
 * @param assignees Ziel-Menge der Assignee-UUIDs (darf leer sein [{@code []}], aber nicht null;
 *                  kein null-Element)
 * @throws EntityValidationException wenn {@code assignees} null ist oder ein null-Element enthaelt
 */
public record AssigneeUpdateInput(
    @ArraySchema(schema = @Schema(type = "string", format = "uuid",
        description = "UUID eines Assignee-Benutzers (PROD_BENUTZER.EXTERNALID)."))
    @Schema(description = "Ziel-Menge der Assignee-UUIDs (PUT-Set-Semantik). Leeres Array = alle abwaehlen.",
        requiredMode = Schema.RequiredMode.REQUIRED)
    Set<UUID> assignees) {

  /**
   * Kompakter Konstruktor: Validiert, dass {@code assignees} nicht null ist und keine
   * null-Elemente enthaelt. Nur explizites leeres Array ist zulaessig.
   */
  public AssigneeUpdateInput {
    if (assignees == null) {
      throw new EntityValidationException(
          "Das Feld 'assignees' darf nicht null sein."
              + " Verwende ein leeres Array [], um alle Assignees abzuwaehlen.");
    }
    if (assignees.stream().anyMatch(java.util.Objects::isNull)) {
      throw new EntityValidationException(
          "Das Feld 'assignees' darf keine null-Elemente enthalten.");
    }
  }
}
