package de.brasi.nova.taskmanagement.participant.model;

import io.keel.framework.api.validation.EntityValidationException;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Eingabe-DTO zum expliziten Setzen eines persoenlichen Markers (pin/flag/mute).
 *
 * <p>Der Wert {@code true} aktiviert den Marker, {@code false} deaktiviert ihn.
 * Kein Toggle — der Client gibt den gewuenschten Zielzustand vor.
 *
 * <p>{@code value} ist als nullable {@link Boolean} deklariert, damit ein Body ohne das Feld
 * zur Compile-Zeit erkennbar null ergibt statt still als {@code false} deserialisiert zu werden.
 * Der Kompakt-Konstruktor erzwingt Praesenz (null -> 400).
 *
 * @param value gewuenschter Zielwert des Markers (Pflichtfeld)
 */
public record MarkerInput(
    @Schema(description = "Gewuenschter Zielzustand: true = aktiv, false = inaktiv.",
        example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    Boolean value) {

  public MarkerInput {
    if (value == null) {
      throw new EntityValidationException("value ist Pflichtfeld.");
    }
  }
}
