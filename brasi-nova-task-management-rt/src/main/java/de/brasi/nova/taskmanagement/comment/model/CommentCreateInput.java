package de.brasi.nova.taskmanagement.comment.model;

import io.keel.framework.api.validation.EntityValidationException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Eingabe-DTO fuer das Erstellen eines Kommentars.
 *
 * <p>Validierungsregeln:
 * <ul>
 *   <li>{@code text} darf nicht null oder leer sein.
 *   <li>{@code parentCommentId} ist optional (null = Top-Level-Kommentar).
 *   <li>{@code mentions} ist optional (null/absent = keine Mentions) und darf keine null-Elemente
 *       enthalten.
 * </ul>
 *
 * @param text            Kommentartext (Markdown, reine Anzeige), nicht null oder leer
 * @param parentCommentId externe UUID des Eltern-Kommentars (null = kein Eltern-Kommentar)
 * @param mentions        explizite Ziel-GUIDs der erwaehnten Benutzer (PROD_BENUTZER.EXTERNALID),
 *                        vom Client aus der Benutzerauswahl geliefert; NICHT aus dem Text geparst
 */
public record CommentCreateInput(
    @Schema(description = "Kommentartext (Markdown, reine Anzeige - Mentions werden NICHT aus dem Text geparst).",
        example = "Bitte bis Ende der Woche erledigen.", requiredMode = Schema.RequiredMode.REQUIRED)
    String text,
    @Schema(description = "Externe UUID des Eltern-Kommentars (null = Top-Level-Kommentar).",
        example = "660e8400-e29b-41d4-a716-446655440001")
    UUID parentCommentId,
    @ArraySchema(schema = @Schema(type = "string", format = "uuid",
        description = "GUID eines erwaehnten Benutzers (PROD_BENUTZER.EXTERNALID)."))
    @Schema(description = "Explizite Ziel-GUIDs der Mentions (null/absent = keine); keine null-Elemente.")
    List<UUID> mentions) {

  /**
   * Kompakter Konstruktor: Validiert {@code text} (nicht null/leer) und normalisiert {@code mentions}
   * (null -&gt; leere Liste; null-Elemente -&gt; 400; sonst immutable Kopie).
   */
  public CommentCreateInput {
    if (text == null || text.isBlank()) {
      throw new EntityValidationException(
          "Das Feld 'text' darf nicht null oder leer sein.");
    }
    if (mentions == null) {
      mentions = List.of();
    } else {
      // null-Element-Pruefung VOR List.copyOf (das sonst NPE wirft) - Muster wie TaskPatchInput.
      for (final UUID mention : mentions) {
        if (mention == null) {
          throw new EntityValidationException("mentions darf keine null-Elemente enthalten.");
        }
      }
      mentions = List.copyOf(mentions);
    }
  }
}
