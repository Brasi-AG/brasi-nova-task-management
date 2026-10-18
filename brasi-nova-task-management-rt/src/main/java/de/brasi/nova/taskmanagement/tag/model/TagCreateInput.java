package de.brasi.nova.taskmanagement.tag.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Eingabe-DTO zum Anlegen eines Tags.
 *
 * @param name         Tag-Name (nicht leer)
 * @param severityCode CODE des Tag-Schweregrads (z.B. "PRIMARY", "DANGER")
 */
public record TagCreateInput(
    @Schema(description = "Tag-Name (darf nicht leer sein).", example = "Dringend",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String name,
    @Schema(description = "Schweregrad-CODE des Tags (z.B. PRIMARY, DANGER, WARNING).",
        example = "DANGER", requiredMode = Schema.RequiredMode.REQUIRED)
    String severityCode) {}
