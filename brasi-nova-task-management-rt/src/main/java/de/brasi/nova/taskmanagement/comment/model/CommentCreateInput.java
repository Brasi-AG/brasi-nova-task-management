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
 *
 * <ul>
 *   <li>{@code text} darf nicht null oder leer sein.
 *   <li>{@code parentCommentId} ist optional (null = Top-Level-Kommentar).
 *   <li>{@code mentions} ist optional (null/absent = keine Mentions) und darf keine null-Elemente
 *       enthalten.
 * </ul>
 *
 * @param text Kommentartext (Markdown, reine Anzeige), nicht null oder leer
 * @param parentCommentId externe UUID des Eltern-Kommentars (null = kein Eltern-Kommentar)
 * @param mentions explizite Ziel-GUIDs der erwaehnten Benutzer (PROD_BENUTZER.EXTERNALID), vom
 *     Client aus der Benutzerauswahl geliefert; NICHT aus dem Text geparst. Erwaehnte aktive
 *     Mandanten-Nutzer, die noch kein Mitglied der Aufgabe sind, werden als Beteiligte aufgenommen,
 *     wenn der Kommentierende dazu berechtigt ist (Intake #156), sonst still verworfen
 */
public record CommentCreateInput(
    @Schema(
            description =
                "Kommentartext (Markdown, reine Anzeige - Mentions werden NICHT aus dem Text geparst).",
            example = "Bitte bis Ende der Woche erledigen.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String text,
    @Schema(
            description = "Externe UUID des Eltern-Kommentars (null = Top-Level-Kommentar).",
            example = "660e8400-e29b-41d4-a716-446655440001",
            nullable = true)
        UUID parentCommentId,
    @ArraySchema(
            maxItems = 100,
            arraySchema =
                @Schema(
                    description =
                        "UUIDs der erwaehnten Benutzer (hoechstens 100, keine null-Elemente; null oder"
                            + " fehlend = keine). Mitglieder der Aufgabe erhalten eine MENTION. Aktive"
                            + " Benutzer des eigenen Mandanten, die noch kein Mitglied sind (auch"
                            + " Ausgetretene und Personen mit deaktivierter Teilnehmerzeile; der Benutzer"
                            + " selbst ist aktiv), werden als Beteiligte hinzugefuegt und erhalten nur die"
                            + " MENTION, wenn die aufrufende Person Beteiligte hinzufuegen darf"
                            + " (Berechtigung TASK_WRITE wie bei POST /participants); sonst werden sie"
                            + " still verworfen. Unbekannte, inaktive und mandantenfremde UUIDs werden"
                            + " ebenfalls still verworfen (kein 400).",
                    nullable = true),
            schema =
                @Schema(
                    type = "string",
                    format = "uuid",
                    description = "GUID eines erwaehnten Benutzers (PROD_BENUTZER.EXTERNALID)."))
        List<UUID> mentions) {

  /**
   * Obergrenze fuer die Anzahl Mentions je Kommentar. Begrenzt die nachgelagerte {@code IN
   * (:uuids)}-Aufloesung, damit eine (absurd) grosse Menge nicht das SQL-Server-Parameterlimit
   * (2100) sprengt und den Kommentar scheitern laesst. 100 ist konsistent zur
   * Keyset-Seitengroessen-Clamp-Konvention [1,100] im Modul.
   */
  private static final int MAX_MENTIONS = 100;

  /**
   * Kompakter Konstruktor: Validiert {@code text} (nicht null/leer) und normalisiert {@code
   * mentions} (null -&gt; leere Liste; &gt; {@link #MAX_MENTIONS} -&gt; 400; null-Elemente -&gt;
   * 400; sonst immutable Kopie).
   */
  public CommentCreateInput {
    if (text == null || text.isBlank()) {
      throw new EntityValidationException("Das Feld 'text' darf nicht null oder leer sein.");
    }
    if (mentions == null) {
      mentions = List.of();
    } else {
      if (mentions.size() > MAX_MENTIONS) {
        throw new EntityValidationException(
            "mentions darf hoechstens " + MAX_MENTIONS + " Eintraege enthalten.");
      }
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
