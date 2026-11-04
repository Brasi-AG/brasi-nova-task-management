package de.brasi.nova.taskmanagement.tag.model;

import io.keel.framework.api.service.model.PatchValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Patch-DTO zum Aktualisieren eines Tags.
 *
 * <p>{@link PatchValue#absent()} bedeutet: Feld unveraendert lassen.
 * {@link PatchValue#of(Object)} bedeutet: Feld auf neuen Wert setzen.
 * {@link PatchValue#ofNull()} bedeutet: Feld explizit leeren (nur fuer {@code color} sinnvoll).
 *
 * @param name         neuer Tag-Name (oder absent)
 * @param severityCode neuer CODE des Tag-Schweregrads (oder absent)
 * @param color        neue UI-Farbe als Hex-String (oder absent; present+null/blank = Farbe und
 *                     severity leeren)
 */
public record TagUpdateInput(
    // springdoc 2.8.12: PatchValue<T> explizit mit fachlichem Zieltyp annotiert (Wrapper-Kollaps vermieden).
    @Schema(type = "string", description = "Neuer Tag-Name (absent = unveraendert).", example = "Sehr dringend")
    PatchValue<String> name,
    @Schema(type = "string", description = "Neuer Schweregrad-CODE (absent = unveraendert; z.B. PRIMARY, DANGER).",
        example = "PRIMARY")
    PatchValue<String> severityCode,
    @Schema(type = "string", nullable = true,
        description = "Neue UI-Farbe als Hex-String (#RRGGBB/#RGB); absent = unveraendert, null/leer = Farbe und severity leeren.",
        example = "#F87171")
    PatchValue<String> color) {}
