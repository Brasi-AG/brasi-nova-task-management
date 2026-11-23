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
 *   <li>{@code mentions} ist optional (null/absent = keine Mentions), hoechstens 100 Eintraege und
 *       darf keine null-Elemente enthalten.
 *   <li>{@code divisionMentions} ist optional (null/absent = keine), hoechstens 10 Eintraege und
 *       darf keine null-Elemente enthalten (Intake #190).
 * </ul>
 *
 * <p>Beide Listen werden nach ihrer Rohlaenge geprueft (vor jeder Deduplizierung, Lesson {@code
 * input-dto-patterns}); die fachliche Deduplizierung macht der Service.
 *
 * @param text Kommentartext (Markdown, reine Anzeige), nicht null oder leer
 * @param parentCommentId externe UUID des Eltern-Kommentars (null = kein Eltern-Kommentar)
 * @param mentions explizite Ziel-GUIDs der erwaehnten Benutzer (PROD_BENUTZER.EXTERNALID), vom
 *     Client aus der Benutzerauswahl geliefert; NICHT aus dem Text geparst. Erwaehnte aktive
 *     Mandanten-Nutzer, die noch kein Mitglied der Aufgabe sind, werden als Beteiligte aufgenommen,
 *     wenn der Kommentierende dazu berechtigt ist (Intake #156), sonst still verworfen
 * @param divisionMentions UUIDs erwaehnter Abteilungen (Division in application); jede Abteilung
 *     steht fuer die Personen ihres Teilbaums laut lokaler Projektion zum Zeitpunkt des Kommentars.
 *     Die Personen werden mit {@code mentions} vereinigt und laufen danach denselben Weg (Intake
 *     #190)
 */
@Schema(
    description =
        "Eingabe eines Kommentars: Text (Pflicht), optional Eltern-Kommentar, erwaehnte Benutzer"
            + " und erwaehnte Abteilungen.")
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
        List<UUID> mentions,
    @ArraySchema(
            maxItems = 10,
            arraySchema =
                @Schema(
                    description =
                        "UUIDs erwaehnter Abteilungen (hoechstens 10, keine null-Elemente; null oder"
                            + " fehlend = keine). Eine Abteilung steht fuer alle Personen ihres"
                            + " Teilbaums (mit Unterabteilungen) laut dem Stand, den task-management"
                            + " zum Zeitpunkt des Kommentars kennt (Momentaufnahme: spaetere"
                            + " Aenderungen der Abteilung wirken nicht nach). Die Personen werden mit"
                            + " mentions vereinigt und dedupliziert und dann wie mentions behandelt:"
                            + " Personen ohne aktiven Benutzer im eigenen Mandanten fallen still weg,"
                            + " die aufrufende Person und der Empfaenger einer REPLY erhalten keine"
                            + " MENTION, Mitglieder der Aufgabe erhalten eine MENTION, Nicht-Mitglieder"
                            + " werden mit der Berechtigung TASK_WRITE als Beteiligte hinzugefuegt"
                            + " (sonst still verworfen). Ein Fehler (400, nichts gespeichert) ist:"
                            + " eine unbekannte, nicht aktive oder mandantenfremde Abteilung (ein"
                            + " Text fuer alle Faelle) und mehr als 200 verschiedene Personen in den"
                            + " erwaehnten Abteilungen zusammen (Vorgabe, konfigurierbar; mentions"
                            + " zaehlt nicht mit). Eine aktive Abteilung ohne Mitglieder ist kein"
                            + " Fehler. Vorschlaege liefert GET /api/v1/tasks/divisions.",
                    nullable = true),
            schema =
                @Schema(
                    type = "string",
                    format = "uuid",
                    description =
                        "UUID einer Abteilung (divisionId aus GET /api/v1/tasks/divisions).",
                    example = "5d1c0a3e-2b4f-4c6d-8e9f-0a1b2c3d4e5f"))
        List<UUID> divisionMentions) {

  /**
   * Obergrenze fuer die Anzahl Mentions je Kommentar. Begrenzt die nachgelagerte {@code IN
   * (:uuids)}-Aufloesung, damit eine (absurd) grosse Menge nicht das SQL-Server-Parameterlimit
   * (2100) sprengt und den Kommentar scheitern laesst. 100 ist konsistent zur
   * Keyset-Seitengroessen-Clamp-Konvention [1,100] im Modul.
   */
  private static final int MAX_MENTIONS = 100;

  /** Obergrenze fuer die Anzahl erwaehnter Abteilungen je Kommentar (Intake #190). */
  private static final int MAX_DIVISION_MENTIONS = 10;

  /**
   * Kompakter Konstruktor: Validiert {@code text} (nicht null/leer) und normalisiert {@code
   * mentions} und {@code divisionMentions} (null -&gt; leere Liste; ueber der Obergrenze -&gt; 400;
   * null-Elemente -&gt; 400; sonst immutable Kopie).
   */
  public CommentCreateInput {
    if (text == null || text.isBlank()) {
      throw new EntityValidationException("Das Feld 'text' darf nicht null oder leer sein.");
    }
    mentions = normalize(mentions, "mentions", MAX_MENTIONS);
    divisionMentions = normalize(divisionMentions, "divisionMentions", MAX_DIVISION_MENTIONS);
  }

  /**
   * Normalisiert eine optionale UUID-Liste: {@code null} wird leer, die Rohlaenge darf {@code max}
   * nicht ueberschreiten, null-Elemente sind verboten; das Ergebnis ist eine unveraenderliche Kopie.
   *
   * @param values Eingabeliste (darf null sein)
   * @param field Feldname fuer die Fehlermeldung
   * @param max hoechste erlaubte Rohlaenge
   * @return unveraenderliche Kopie, nie null
   * @throws EntityValidationException bei zu vielen Eintraegen oder einem null-Element (HTTP 400)
   */
  private static List<UUID> normalize(final List<UUID> values, final String field, final int max) {
    if (values == null) {
      return List.of();
    }
    if (values.size() > max) {
      throw new EntityValidationException(
          field + " darf hoechstens " + max + " Eintraege enthalten.");
    }
    // null-Element-Pruefung VOR List.copyOf (das sonst NPE wirft) - Muster wie TaskPatchInput.
    for (final UUID value : values) {
      if (value == null) {
        throw new EntityValidationException(field + " darf keine null-Elemente enthalten.");
      }
    }
    return List.copyOf(values);
  }
}
