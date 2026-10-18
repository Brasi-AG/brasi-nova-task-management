package de.brasi.nova.taskmanagement.tag.model;

import io.keel.framework.api.service.model.PatchValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Patch-DTO zum Aktualisieren eines Tags.
 *
 * <p>{@link PatchValue#absent()} bedeutet: Feld unveraendert lassen.
 * {@link PatchValue#of(Object)} bedeutet: Feld auf neuen Wert setzen.
 *
 * @param name         neuer Tag-Name (oder absent)
 * @param severityCode neuer CODE des Tag-Schweregrads (oder absent)
 */
public record TagUpdateInput(
    // springdoc 2.8.12: PatchValue<T> explizit mit fachlichem Zieltyp annotiert (Wrapper-Kollaps vermieden).
    @Schema(type = "string", description = "Neuer Tag-Name (absent = unveraendert).", example = "Sehr dringend")
    PatchValue<String> name,
    @Schema(type = "string", description = "Neuer Schweregrad-CODE (absent = unveraendert; z.B. PRIMARY, DANGER).",
        example = "PRIMARY")
    PatchValue<String> severityCode) {}
