package de.brasi.nova.taskmanagement.comment.model;

import io.keel.framework.api.validation.EntityValidationException;
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
public record CommentCreateInput(String text, UUID parentCommentId) {

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
