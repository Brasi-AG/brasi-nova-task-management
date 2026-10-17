package de.brasi.nova.taskmanagement.task.model;

import io.keel.framework.api.service.model.PatchValue;
import java.time.LocalDate;

/**
 * Patch-DTO fuer Aufgaben (title, dueDate, statusCode).
 *
 * <p>{@link PatchValue#absent()} bedeutet: Feld nicht aendern.
 * {@link PatchValue#of(Object)} bedeutet: Feld auf den angegebenen Wert setzen.
 * {@link PatchValue#ofNull()} bedeutet: Feld explizit auf null setzen (nur wenn nullable).
 *
 * <p>{@code tagIds} ist bewusst nicht enthalten - das ist Task 13.
 *
 * @param title      neuer Titel (oder absent)
 * @param dueDate    neues Faelligkeitsdatum (oder absent/null)
 * @param statusCode neuer STATUS-CODE (z.B. "DONE"; oder absent)
 */
public record TaskPatchInput(
    PatchValue<String> title,
    PatchValue<LocalDate> dueDate,
    PatchValue<String> statusCode) {

  /** Erzeugt eine leere Patch-Eingabe (alle Felder absent). */
  public static TaskPatchInput empty() {
    return new TaskPatchInput(
        PatchValue.absent(), PatchValue.absent(), PatchValue.absent());
  }
}
