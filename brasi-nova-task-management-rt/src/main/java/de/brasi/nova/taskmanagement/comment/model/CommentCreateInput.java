package de.brasi.nova.taskmanagement.comment.model;

import io.keel.framework.api.validation.EntityValidationException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Eingabe-DTO fuer das Erstellen eines Kommentars.
 *
 * <p>Validierungsregeln:
 * <ul>
 *   <li>{@code text} darf nicht null oder leer sein.
 *   <li>{@code parentCommentId} ist optional (null = Top-Level-Kommentar).
 * </ul>
 *
 * @param text            Kommentartext (Markdown mit @handle-Mentions), nicht null oder leer
 * @param parentCommentId externe UUID des Eltern-Kommentars (null = kein Eltern-Kommentar)
 */
public record CommentCreateInput(
    @Schema(description = "Kommentartext (Markdown; @handle-Mentions werden aufgeloest).",
        example = "Bitte bis Ende der Woche erledigen.", requiredMode = Schema.RequiredMode.REQUIRED)
    String text,
    @Schema(description = "Externe UUID des Eltern-Kommentars (null = Top-Level-Kommentar).",
        example = "660e8400-e29b-41d4-a716-446655440001")
    UUID parentCommentId) {

  /**
   * Kompakter Konstruktor: Validiert, dass {@code text} nicht null oder leer ist.
   */
  public CommentCreateInput {
    if (text == null || text.isBlank()) {
      throw new EntityValidationException(
          "Das Feld 'text' darf nicht null oder leer sein.");
    }
  }
}
