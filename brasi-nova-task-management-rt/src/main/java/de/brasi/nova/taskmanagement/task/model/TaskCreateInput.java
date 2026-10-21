package de.brasi.nova.taskmanagement.task.model;

import io.keel.framework.api.validation.EntityValidationException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Eingabe-DTO fuer die Anlage einer neuen Aufgabe.
 *
 * <p>Direction wird serverseitig auf {@code CUSTOMER_INTERNAL} erzwungen (kein Input-Feld).
 * Prioritaet ist optional; fehlt sie, wird {@code NORMAL} verwendet.
 *
 * <p>BE-4: Zusaetzlich koennen {@code assignees}, {@code tagIds}, {@code isPinned} und
 * {@code isFlagged} als Initialwerte angegeben werden (alle optional; fehlen -&gt; keine
 * Zusatzwirkung). Die ID-Listen werden im kompakten Konstruktor validiert; die fachliche
 * Deduplizierung uebernimmt der Service (Set-Semantik beim Anlegen).
 *
 * @param title        Pflicht-Titel der Aufgabe
 * @param description  optionale Beschreibung
 * @param priorityCode optionaler Prioritaets-CODE (z.B. "HIGH"); fehlt -> Default "NORMAL"
 * @param startDate    optionales Startdatum
 * @param dueDate      optionales Faelligkeitsdatum
 * @param assignees    optionale Initial-Assignees (UUIDs aktiver Benutzer DES Mandanten); null -> leer
 * @param tagIds       optionale Initial-Tags (UUIDs aktiver globaler Katalog-Tags); null -> leer
 * @param isPinned     optionaler Pin-Marker des Erstellers (null = Default false)
 * @param isFlagged    optionaler Flag-Marker des Erstellers (null = Default false)
 */
public record TaskCreateInput(
    @Schema(description = "Titel der Aufgabe.", example = "Jahresabschluss vorbereiten",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String title,
    @Schema(description = "Optionale Beschreibung der Aufgabe.", example = "Bitte Unterlagen bis Montag bereitstellen.")
    String description,
    @Schema(description = "Prioritaets-CODE (z.B. HIGH, NORMAL, LOW). Fehlt der Wert, wird NORMAL verwendet.",
        example = "NORMAL")
    String priorityCode,
    @Schema(description = "Optionales Startdatum (ISO 8601).", example = "2026-11-01")
    LocalDate startDate,
    @Schema(description = "Optionales Faelligkeitsdatum (ISO 8601).", example = "2026-12-31")
    LocalDate dueDate,
    @Schema(description = "Optionale Initial-Assignees (UUIDs aktiver Benutzer des Mandanten)."
        + " Der Ersteller ist bereits Assignee und wird ignoriert. Max. 100 Eintraege.")
    List<UUID> assignees,
    @Schema(description = "Optionale Initial-Tags (UUIDs aktiver globaler Katalog-Tags). Max. 100 Eintraege.")
    List<UUID> tagIds,
    @Schema(description = "Optionaler Pin-Marker des Erstellers (null = nicht angepinnt).")
    Boolean isPinned,
    @Schema(description = "Optionaler Flag-Marker des Erstellers (null = nicht markiert).")
    Boolean isFlagged) {

  /** Obergrenze je ID-Liste (schuetzt das SQL-Parameterlimit bei {@code IN (:uuids)}). */
  public static final int MAX_IDS = 100;

  /**
   * Kompakter Konstruktor: validiert und normalisiert die optionalen ID-Listen (D6).
   *
   * <p>{@code null} -&gt; leere Liste; mehr als {@link #MAX_IDS} Eintraege -&gt;
   * {@link EntityValidationException} (400) auf der ROHEN Array-Laenge (vor jeder Deduplizierung,
   * damit auch viele doppelte UUIDs die Grenze nicht umgehen und das SQL-Parameterlimit bei
   * {@code IN (:uuids)} geschuetzt bleibt); {@code null}-Elemente -&gt; 400 statt NPE.
   * {@code isPinned}/{@code isFlagged} bleiben nullable (null = Default false beim Anlegen).
   * Die fachliche Deduplizierung passiert im Service (Set-Semantik beim Anlegen).
   */
  public TaskCreateInput {
    assignees = normalizeIdList(assignees, "assignees");
    tagIds = normalizeIdList(tagIds, "tagIds");
  }

  /**
   * Validiert und kopiert eine optionale ID-Liste in eine unveraenderliche Liste: null/leer -&gt;
   * leere Liste; die ROHE Laenge (inkl. Duplikate) gegen {@link #MAX_IDS} und null-Elemente werden
   * als {@link EntityValidationException} (400) abgewiesen, bevor der Service irgendetwas anlegt.
   *
   * @param raw   die rohe Eingabe-Liste (nullable, darf null-Elemente enthalten)
   * @param field Feldname fuer die Fehlermeldung
   * @return eine unveraenderliche, null-freie Liste (mit evtl. noch vorhandenen Duplikaten)
   */
  private static List<UUID> normalizeIdList(final List<UUID> raw, final String field) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    if (raw.size() > MAX_IDS) {
      throw new EntityValidationException(
          field + " darf hoechstens " + MAX_IDS + " Eintraege enthalten.");
    }
    for (final UUID id : raw) {
      if (id == null) {
        throw new EntityValidationException(field + " darf keine null-Elemente enthalten.");
      }
    }
    return List.copyOf(raw);
  }
}
