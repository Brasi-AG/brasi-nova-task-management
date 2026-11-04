package de.brasi.nova.taskmanagement.tag.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Eingabe-DTO zum Anlegen eines Tags.
 *
 * <p>Seit Intake #135 (Phase 1) sind {@code severityCode} und {@code color} beide optional: ein Tag
 * kann color-only, severity-only (Farbe aus severity abgeleitet) oder neutral (beides {@code null})
 * angelegt werden.
 *
 * @param name         Tag-Name (nicht leer)
 * @param severityCode CODE des Tag-Schweregrads (z.B. "PRIMARY", "DANGER") oder {@code null}
 * @param color        UI-Farbe als Hex-String ({@code #RRGGBB}/{@code #RGB}) oder {@code null}
 */
public record TagCreateInput(
    @Schema(description = "Tag-Name (darf nicht leer sein).", example = "Dringend",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String name,
    @Schema(description = "Schweregrad-CODE des Tags (z.B. PRIMARY, DANGER, WARNING); optional.",
        example = "DANGER", nullable = true)
    String severityCode,
    @Schema(
        description = "UI-Farbe des Tags als Hex-String (#RRGGBB oder #RGB); optional. Ohne color wird sie aus severityCode abgeleitet.",
        example = "#F87171", nullable = true)
    String color) {}
