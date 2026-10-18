package de.brasi.nova.taskmanagement.task.model;

import io.keel.framework.api.service.model.PatchValue;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Patch-DTO fuer Aufgaben (title, dueDate, statusCode, tagIds).
 *
 * <p>{@link PatchValue#absent()} bedeutet: Feld nicht aendern.
 * {@link PatchValue#of(Object)} bedeutet: Feld auf den angegebenen Wert setzen.
 * {@link PatchValue#ofNull()} bedeutet: Feld explizit auf null setzen (nur wenn nullable).
 *
 * <p>{@code tagIds}: absent = Tag-Menge unberuehrt; present(Set) = PUT-Set-Semantik,
 * die aktuelle Ziel-Menge ersetzt die aktuellen aktiven Assignments exakt (R-PATCH).
 * Unbekannte/inaktive Tag-UUIDs fuehren zu HTTP 400 (keine Teil-Anwendung).
 *
 * @param title      neuer Titel (oder absent)
 * @param dueDate    neues Faelligkeitsdatum (oder absent/null)
 * @param statusCode neuer STATUS-CODE (z.B. "DONE"; oder absent)
 * @param tagIds     neue Tag-UUID-Menge (PUT-Set; oder absent = unberuehrt)
 */
public record TaskPatchInput(
    // Hinweis springdoc 2.8.12: PatchValue<T> wird als Wrapper kollabiert/missinterpretiert.
    // Daher werden die Record-Komponenten mit explizitem @Schema auf den fachlichen Zieltyp annotiert.
    @Schema(type = "string", description = "Neuer Titel der Aufgabe (absent = unveraendert).",
        example = "Jahresabschluss vorbereiten")
    PatchValue<String> title,
    @Schema(type = "string", format = "date",
        description = "Neues Faelligkeitsdatum ISO 8601 (absent = unveraendert, null = Datum loeschen).",
        example = "2026-12-31")
    PatchValue<LocalDate> dueDate,
    @Schema(type = "string", description = "Neuer Status-CODE (z.B. DONE; absent = unveraendert).",
        example = "DONE")
    PatchValue<String> statusCode,
    @ArraySchema(schema = @Schema(type = "string", format = "uuid",
        description = "UUID eines zugewiesenen Tags."))
    @Schema(description = "Neue Tag-UUID-Menge (PUT-Set-Semantik; absent = unberuehrt).")
    PatchValue<Set<UUID>> tagIds) {

  /** Erzeugt eine leere Patch-Eingabe (alle Felder absent). */
  public static TaskPatchInput empty() {
    return new TaskPatchInput(
        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(), PatchValue.absent());
  }
}
