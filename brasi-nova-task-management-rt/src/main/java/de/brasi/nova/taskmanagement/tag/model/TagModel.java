package de.brasi.nova.taskmanagement.tag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Modell-DTO fuer einen Tag im Katalog.
 *
 * @param id           externe UUID des Tags (EXTERNALID)
 * @param name         Tag-Name (BEZEICHNUNG / entryValue)
 * @param severityCode Anzeigeschwere (CODE aus DIC_TAG_SEVERITY) oder {@code null}
 * @param color        UI-Farbe als Hex-String ({@code #RRGGBB}/{@code #RGB}) oder {@code null}
 */
public record TagModel(
    @Schema(description = "Externe UUID des Tags.", example = "aa0e8400-e29b-41d4-a716-446655440010")
    UUID id,
    @Schema(description = "Tag-Name.", example = "Dringend")
    String name,
    @Schema(description = "Schweregrad-CODE des Tags (z.B. PRIMARY, DANGER, WARNING); null bei color-only/neutralem Tag.",
        example = "DANGER", nullable = true)
    String severityCode,
    @Schema(
        description = "UI-Farbe des Tags als Hex-String (#RRGGBB oder #RGB, normalisiert Grossbuchstaben); null = keine Farbe.",
        example = "#F87171", nullable = true)
    String color) {}
