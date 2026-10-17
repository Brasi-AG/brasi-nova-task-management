package de.brasi.nova.taskmanagement.task.model;

import io.keel.framework.api.service.model.PatchValue;
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
    PatchValue<String> title,
    PatchValue<LocalDate> dueDate,
    PatchValue<String> statusCode,
    PatchValue<Set<UUID>> tagIds) {

  /** Erzeugt eine leere Patch-Eingabe (alle Felder absent). */
  public static TaskPatchInput empty() {
    return new TaskPatchInput(
        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(), PatchValue.absent());
  }
}
