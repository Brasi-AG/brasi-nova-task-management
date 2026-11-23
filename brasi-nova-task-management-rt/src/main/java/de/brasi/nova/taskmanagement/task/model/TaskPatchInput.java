package de.brasi.nova.taskmanagement.task.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.keel.framework.api.service.model.PatchValue;
import io.keel.framework.api.validation.EntityValidationException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Patch-DTO fuer Aufgaben (title, dueDate, statusCode, tagIds, startDate, description,
 * priorityCode, mentions, divisionMentions).
 *
 * <p>{@link PatchValue#absent()} bedeutet: Feld nicht aendern. {@link PatchValue#of(Object)}
 * bedeutet: Feld auf den angegebenen Wert setzen. {@link PatchValue#ofNull()} bedeutet: Feld
 * explizit auf null setzen (nur wenn nullable).
 *
 * <p>{@code tagIds}: absent = Tag-Menge unberuehrt; present(Set) = PUT-Set-Semantik, die aktuelle
 * Ziel-Menge ersetzt die aktuellen aktiven Assignments exakt (R-PATCH). Unbekannte/inaktive
 * Tag-UUIDs fuehren zu HTTP 400 (keine Teil-Anwendung).
 *
 * <p>{@code startDate} (BE-5): absent = unveraendert; present(date) = setzen; present(null) =
 * Startdatum loeschen (Spalte nullable). Keine Datumsreihenfolge-Pruefung gegen {@code dueDate}
 * (konsistent zu Create und dem bestehenden dueDate-PATCH).
 *
 * <p>{@code description} (#143): absent = unveraendert; present(text) = setzen (wie bei Create
 * unveraendert uebernommen); present(null) = Beschreibung loeschen (Spalte nullable).
 *
 * <p>{@code priorityCode} (#183): absent = unveraendert; present(code) = Prioritaet setzen (Code
 * aus dem Dictionary taskPriority, derzeit LOW, NORMAL, HIGH, URGENT); present(null) = auf NORMAL
 * zuruecksetzen. Unbekannter, leerer oder inaktiver Code fuehrt zu HTTP 400.
 *
 * <p>{@code mentions} und {@code divisionMentions} (Intake #190): Erwaehnungen in der Beschreibung,
 * wie beim Kommentar (keine {@code PatchValue}, sondern einfache Listen; {@code null} und fehlend =
 * leer). Der kompakte Konstruktor prueft in fester Reihenfolge, jeweils mit HTTP 400:
 *
 * <ol>
 *   <li>Rohlaenge (vor jeder Deduplizierung, Lesson {@code input-dto-patterns}): {@code mentions}
 *       hoechstens 100, {@code divisionMentions} hoechstens 10 Eintraege;
 *   <li>keine {@code null}-Elemente (je Liste, {@code mentions} zuerst);
 *   <li>erst dann: eine nicht leere Liste ist nur zusammen mit einem vorhandenen {@code description}
 *       zulaessig ({@link PatchValue#isPresent()}, auch wenn der Wert die Beschreibung leert).
 * </ol>
 *
 * Die fachliche Deduplizierung, die Aufloesung der Abteilungen und die Benachrichtigung macht
 * {@code TaskCommandService#patch}.
 *
 * @param title neuer Titel (oder absent)
 * @param dueDate neues Faelligkeitsdatum (oder absent/null)
 * @param statusCode neuer STATUS-CODE (z.B. "DONE"; oder absent)
 * @param tagIds neue Tag-UUID-Menge (PUT-Set; oder absent = unberuehrt)
 * @param startDate neues Startdatum (oder absent/null)
 * @param description neue Beschreibung (oder absent/null)
 * @param priorityCode neuer Prioritaets-CODE (LOW, NORMAL, HIGH, URGENT; oder absent =
 *     unveraendert, null = auf NORMAL zuruecksetzen)
 * @param mentions UUIDs erwaehnter Benutzer (PROD_BENUTZER.EXTERNALID) in der neuen Beschreibung;
 *     null = keine
 * @param divisionMentions UUIDs erwaehnter Abteilungen in der neuen Beschreibung; null = keine
 */
public record TaskPatchInput(
    // Hinweis springdoc 2.8.12: PatchValue<T> wird als Wrapper kollabiert/missinterpretiert.
    // Daher werden die Record-Komponenten mit explizitem @Schema auf den fachlichen Zieltyp
    // annotiert.
    @Schema(
            type = "string",
            description = "Neuer Titel der Aufgabe (absent = unveraendert).",
            example = "Jahresabschluss vorbereiten")
        PatchValue<String> title,
    @Schema(
            nullable = true,
            description =
                "Neues Faelligkeitsdatum ISO 8601 (absent = unveraendert, null = Datum loeschen).",
            example = "2026-12-31")
        PatchValue<LocalDate> dueDate,
    @Schema(
            type = "string",
            description = "Neuer Status-CODE (z.B. DONE; absent = unveraendert).",
            example = "DONE")
        PatchValue<String> statusCode,
    @ArraySchema(
            schema =
                @Schema(
                    type = "string",
                    format = "uuid",
                    description = "UUID eines zugewiesenen Tags."))
        @Schema(description = "Neue Tag-UUID-Menge (PUT-Set-Semantik; absent = unberuehrt).")
        PatchValue<Set<UUID>> tagIds,
    @Schema(
            nullable = true,
            description =
                "Neues Startdatum ISO 8601 (absent = unveraendert, null = Datum loeschen).",
            example = "2026-11-01")
        PatchValue<LocalDate> startDate,
    @Schema(
            type = "string",
            nullable = true,
            description =
                "Neue Beschreibung der Aufgabe (absent = unveraendert, null = Beschreibung"
                    + " loeschen).",
            example = "Belege bis Monatsende einsammeln")
        PatchValue<String> description,
    @Schema(
            type = "string",
            nullable = true,
            description =
                "Neuer Prioritaets-CODE aus dem Dictionary taskPriority (derzeit LOW, NORMAL,"
                    + " HIGH, URGENT; absent = unveraendert, null = auf NORMAL zuruecksetzen).",
            example = "HIGH")
        PatchValue<String> priorityCode,
    @ArraySchema(
            maxItems = 100,
            arraySchema =
                @Schema(
                    description =
                        "UUIDs der in der neuen Beschreibung erwaehnten Benutzer (hoechstens 100,"
                            + " keine null-Elemente; null oder fehlend = keine). Nur zusammen mit"
                            + " description zulaessig (auch wenn description die Beschreibung"
                            + " leert), sonst 400. Wirkung wie mentions am Kommentar: Mitglieder"
                            + " der Aufgabe erhalten eine MENTION 'Du wurdest in der"
                            + " Aufgabenbeschreibung erwaehnt.' (auch bei stummgeschalteter"
                            + " Aufgabe), aktive Benutzer des eigenen Mandanten, die noch kein"
                            + " Mitglied sind, werden mit der Berechtigung TASK_WRITE als"
                            + " Beteiligte hinzugefuegt und erhalten die MENTION, sonst still"
                            + " verworfen. Die aufrufende Person erhaelt keine MENTION. Unbekannte,"
                            + " inaktive und mandantenfremde UUIDs werden still verworfen.",
                    nullable = true),
            schema =
                @Schema(
                    type = "string",
                    format = "uuid",
                    description = "GUID eines erwaehnten Benutzers (PROD_BENUTZER.EXTERNALID).",
                    example = "8b2f5c1e-4d3a-4e6f-9a7b-1c2d3e4f5a6b"))
        List<UUID> mentions,
    @ArraySchema(
            maxItems = 10,
            arraySchema =
                @Schema(
                    description =
                        "UUIDs der in der neuen Beschreibung erwaehnten Abteilungen (hoechstens"
                            + " 10, keine null-Elemente; null oder fehlend = keine). Nur zusammen"
                            + " mit description zulaessig, sonst 400. Eine Abteilung steht fuer"
                            + " alle Personen ihres Teilbaums laut dem Stand, den task-management"
                            + " zum Zeitpunkt des PATCH kennt (Momentaufnahme); die Personen werden"
                            + " mit mentions vereinigt und wie mentions behandelt. 400 ohne"
                            + " Aenderung (auch die Beschreibung bleibt unveraendert): eine"
                            + " unbekannte, nicht aktive oder mandantenfremde Abteilung und mehr als"
                            + " 200 verschiedene Personen in den erwaehnten Abteilungen zusammen"
                            + " (Vorgabe, konfigurierbar). Vorschlaege liefert GET"
                            + " /api/v1/tasks/divisions.",
                    nullable = true),
            schema =
                @Schema(
                    type = "string",
                    format = "uuid",
                    description =
                        "UUID einer Abteilung (divisionId aus GET /api/v1/tasks/divisions).",
                    example = "5d1c0a3e-2b4f-4c6d-8e9f-0a1b2c3d4e5f"))
        List<UUID> divisionMentions) {

  /** Obergrenze fuer erwaehnte Benutzer je PATCH (wie beim Kommentar, SQL-Parameterlimit). */
  private static final int MAX_MENTIONS = 100;

  /** Obergrenze fuer erwaehnte Abteilungen je PATCH (wie beim Kommentar). */
  private static final int MAX_DIVISION_MENTIONS = 10;

  /** Text der 400, wenn Erwaehnungen ohne Aenderung der Beschreibung gesendet werden. */
  public static final String MENTIONS_WITHOUT_DESCRIPTION_MESSAGE =
      "Erwaehnungen sind nur zusammen mit einer Aenderung der Beschreibung zulaessig.";

  /**
   * Kompakter Konstruktor: normalisiert {@code mentions} und {@code divisionMentions} (null -&gt;
   * leer; Rohlaenge und null-Elemente -&gt; 400) und verlangt danach fuer nicht leere Erwaehnungen
   * ein vorhandenes {@code description} (sonst 400). Reihenfolge siehe Klassenbeschreibung.
   */
  public TaskPatchInput {
    mentions = normalize(mentions, "mentions", MAX_MENTIONS);
    divisionMentions = normalize(divisionMentions, "divisionMentions", MAX_DIVISION_MENTIONS);
    final boolean descriptionPresent = description != null && description.isPresent();
    if ((!mentions.isEmpty() || !divisionMentions.isEmpty()) && !descriptionPresent) {
      throw new EntityValidationException(MENTIONS_WITHOUT_DESCRIPTION_MESSAGE);
    }
  }

  @JsonAnySetter
  public void rejectSourceMutation(final String name, final Object value) {
    final String field = name.toLowerCase(Locale.ROOT);
    if (field.startsWith("source") || field.equals("href") || field.equals("url")) {
      throw new EntityValidationException("Quellkontext ist unveraenderlich.");
    }
  }

  /** Erzeugt eine leere Patch-Eingabe (alle Felder absent). */
  public static TaskPatchInput empty() {
    return new TaskPatchInput(
        PatchValue.absent(),
        PatchValue.absent(),
        PatchValue.absent(),
        PatchValue.absent(),
        PatchValue.absent(),
        PatchValue.absent(),
        PatchValue.absent(),
        List.of(),
        List.of());
  }

  /**
   * Normalisiert eine optionale UUID-Liste wie {@code CommentCreateInput}: {@code null} wird leer,
   * die Rohlaenge darf {@code max} nicht ueberschreiten, null-Elemente sind verboten; das Ergebnis
   * ist eine unveraenderliche Kopie.
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
    // null-Element-Pruefung VOR List.copyOf (das sonst NPE wirft).
    for (final UUID value : values) {
      if (value == null) {
        throw new EntityValidationException(field + " darf keine null-Elemente enthalten.");
      }
    }
    return List.copyOf(values);
  }
}
