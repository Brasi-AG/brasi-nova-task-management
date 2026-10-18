package de.brasi.nova.taskmanagement.tag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Modell-DTO fuer einen Tag im Katalog.
 *
 * @param id           externe UUID des Tags (EXTERNALID)
 * @param name         Tag-Name (BEZEICHNUNG / entryValue)
 * @param severityCode Anzeigeschwere (CODE aus DIC_TAG_SEVERITY)
 */
public record TagModel(
    @Schema(description = "Externe UUID des Tags.", example = "aa0e8400-e29b-41d4-a716-446655440010")
    UUID id,
    @Schema(description = "Tag-Name.", example = "Dringend")
    String name,
    @Schema(description = "Schweregrad-CODE des Tags (z.B. PRIMARY, DANGER, WARNING).", example = "DANGER")
    String severityCode) {}
