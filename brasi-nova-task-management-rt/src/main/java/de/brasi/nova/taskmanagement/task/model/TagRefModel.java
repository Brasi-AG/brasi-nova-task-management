package de.brasi.nova.taskmanagement.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Anzeige-Projektion eines einer Aufgabe zugeordneten Tags (Intake #138, Slice 138-1).
 *
 * <p>Additive Detail-Anreicherung zu {@link TaskDetailModel#tagIds}: waehrend {@code tagIds} nur
 * die rohen GUIDs (Read-before-edit) traegt, liefert {@code TagRefModel} je zugeordnetem Tag auch
 * Name und Farbe, damit das Frontend auch <b>retire-te-aber-noch-zugeordnete</b> Tags korrekt
 * rendern kann. Der zugrundeliegende Katalog-Read ist bewusst status-agnostisch (Tenant 0, {@code
 * TenantInfo.global()}): ein inzwischen {@code DEACTIVE}-Tag erscheint weiterhin fuer eine Aufgabe,
 * der er zugeordnet ist (bewusst gelockerte Invariante, Intake #138-Leitgedanke), taucht aber nicht
 * mehr im aktiven Katalog (GET /tags, snapshot.tags) auf.
 *
 * @param id externe UUID des Tags (TASK_TAG.EXTERNALID)
 * @param name Tag-Name (BEZEICHNUNG / entryValue)
 * @param color UI-Farbe als Hex-String ({@code #RRGGBB}/{@code #RGB}) oder {@code null}
 */
public record TagRefModel(
    @Schema(
            description = "Externe UUID des Tags.",
            example = "aa0e8400-e29b-41d4-a716-446655440010")
        UUID id,
    @Schema(description = "Tag-Name.", example = "Dringend") String name,
    @Schema(
            description =
                "UI-Farbe des Tags als Hex-String (#RRGGBB oder #RGB) oder null bei neutralem Tag.",
            example = "#F87171",
            nullable = true)
        String color) {}
