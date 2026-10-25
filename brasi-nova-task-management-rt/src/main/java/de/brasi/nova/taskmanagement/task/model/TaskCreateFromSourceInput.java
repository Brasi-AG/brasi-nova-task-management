package de.brasi.nova.taskmanagement.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * OpenAPI-only request schema for source creation. The controller continues to deserialize {@link
 * TaskCreateInput}; this named schema makes its additional required source visible to generated
 * clients without changing the runtime request type.
 */
public record TaskCreateFromSourceInput(
    @Schema(
            description = "Titel der Aufgabe.",
            example = "Jahresabschluss vorbereiten",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String title,
    @Schema(
            description = "Optionale Beschreibung der Aufgabe.",
            example = "Bitte Unterlagen bis Montag bereitstellen.")
        String description,
    @Schema(
            description =
                "Prioritaets-CODE (z.B. HIGH, NORMAL, LOW). Fehlt der Wert, wird NORMAL verwendet.",
            example = "NORMAL")
        String priorityCode,
    @Schema(description = "Optionales Startdatum (ISO 8601).", example = "2026-11-01")
        LocalDate startDate,
    @Schema(description = "Optionales Faelligkeitsdatum (ISO 8601).", example = "2026-12-31")
        LocalDate dueDate,
    @Schema(
            description =
                "Optionale Initial-Assignees (UUIDs aktiver Benutzer des Mandanten)."
                    + " Der Ersteller ist bereits Assignee und wird ignoriert. Max. 100 Eintraege.")
        List<UUID> assignees,
    @Schema(
            description =
                "Optionale Initial-Tags (UUIDs aktiver globaler Katalog-Tags). Max. 100 Eintraege.")
        List<UUID> tagIds,
    @Schema(description = "Optionaler Pin-Marker des Erstellers (null = nicht angepinnt).")
        Boolean isPinned,
    @Schema(description = "Optionaler Flag-Marker des Erstellers (null = nicht markiert).")
        Boolean isFlagged,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TaskSourceInput source) {}
