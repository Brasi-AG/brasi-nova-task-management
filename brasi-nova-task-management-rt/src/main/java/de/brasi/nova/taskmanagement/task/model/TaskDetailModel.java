package de.brasi.nova.taskmanagement.task.model;

import de.brasi.nova.taskmanagement.inbox.model.ExitModel;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Antwort-DTO fuer das Detail einer Aufgabe.
 *
 * <p>Alle Status-/Prioritaets-/Direction-Felder werden als CODE-Strings (nie als interne ID oder
 * Enum) nach aussen gegeben. BE-3 ergaenzt additiv die Actor-/Personen-Felder ({@code createdById},
 * persoenliche Marker, {@code participantIds}, {@code exit}); die bestehenden Felder bleiben
 * unveraendert.
 *
 * @param id             externe UUID der Aufgabe (EXTERNALID)
 * @param title          Titel der Aufgabe
 * @param description    optionale Beschreibung
 * @param statusCode     aktueller Status-CODE (z.B. "OPEN")
 * @param priorityCode   Prioritaets-CODE (z.B. "NORMAL")
 * @param directionCode  Direction-CODE (z.B. "CUSTOMER_INTERNAL")
 * @param startDate      optionales Startdatum
 * @param dueDate        optionales Faelligkeitsdatum
 * @param lastActivityAt Zeitpunkt der letzten Aktivitaet (UTC)
 * @param commentCount   Anzahl der Kommentare (denormalisiert)
 * @param history        Liste der Historieneintraege (neueste zuerst)
 * @param assignees      aktuelle Assignee-GUIDs (PROD_BENUTZER.EXTERNALID) dieser Aufgabe, stabil
 *                       sortiert; Read-before-edit fuer {@code PUT /tasks/{id}/assignees}
 * @param tagIds         aktuelle Tag-GUIDs (TASK_TAG.EXTERNALID) dieser Aufgabe, stabil sortiert;
 *                       Read-before-edit fuer {@code PATCH /tasks/{id}} (tagIds)
 * @param createdById    UUID des Erstellers ({@code CREATOR_FLAG}, PERSON) oder {@code null}
 * @param participantIds UUIDs der aktiven PERSON-Teilnehmer ohne Assignees/Ersteller (stabil sortiert)
 * @param isPinned       persoenlicher Marker des Anfragers (Actor)
 * @param isFlagged      persoenlicher Marker des Anfragers (Actor)
 * @param isMuted        persoenlicher Marker des Anfragers (Actor)
 * @param exit           aktueller Austritt des Anfragers (handover/unsubscribed) oder {@code null}
 */
public record TaskDetailModel(
    @Schema(description = "Externe UUID der Aufgabe.", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID id,
    @Schema(description = "Titel der Aufgabe.", example = "Jahresabschluss vorbereiten")
    String title,
    @Schema(description = "Optionale Beschreibung der Aufgabe.", example = "Bitte Unterlagen bis Montag bereitstellen.")
    String description,
    @Schema(description = "Aktueller Status-CODE der Aufgabe.", example = "OPEN")
    String statusCode,
    @Schema(description = "Prioritaets-CODE der Aufgabe.", example = "NORMAL")
    String priorityCode,
    @Schema(description = "Richtungs-CODE der Aufgabe.", example = "CUSTOMER_INTERNAL")
    String directionCode,
    @Schema(description = "Optionales Startdatum (ISO 8601).", example = "2026-11-01")
    LocalDate startDate,
    @Schema(description = "Optionales Faelligkeitsdatum (ISO 8601).", example = "2026-12-31")
    LocalDate dueDate,
    @Schema(description = "Zeitpunkt der letzten Aktivitaet (UTC).", example = "2026-10-18T08:00:00Z")
    Instant lastActivityAt,
    @Schema(description = "Anzahl der Kommentare (denormalisiert).", example = "3")
    int commentCount,
    @Schema(description = "Liste der Historieneintraege (neueste zuerst).")
    List<HistoryModel> history,
    @ArraySchema(schema = @Schema(type = "string", format = "uuid",
        description = "GUID eines aktuellen Assignees (PROD_BENUTZER.EXTERNALID)."))
    @Schema(description = "Aktuelle Assignee-GUIDs (stabil sortiert); Read-before-edit fuer PUT assignees.")
    List<UUID> assignees,
    @ArraySchema(schema = @Schema(type = "string", format = "uuid",
        description = "GUID eines aktuell zugeordneten Tags (TASK_TAG.EXTERNALID)."))
    @Schema(description = "Aktuelle Tag-GUIDs (stabil sortiert); Read-before-edit fuer PATCH tagIds."
        + " Enthaelt auch katalog-inaktive, aber noch zugeordnete Tags (faithful).")
    List<UUID> tagIds,
    @Schema(
        description = "UUID des Erstellers (CREATOR_FLAG, PERSON) oder null.",
        example = "550e8400-e29b-41d4-a716-446655440000")
    UUID createdById,
    @ArraySchema(schema = @Schema(type = "string", format = "uuid",
        description = "GUID eines aktiven PERSON-Teilnehmers (ohne Assignees/Ersteller)."))
    @Schema(description = "Aktive PERSON-Teilnehmer ohne Assignees/Ersteller (stabil sortiert).")
    List<UUID> participantIds,
    @Schema(description = "Persoenlicher Marker des Anfragers: angepinnt.", example = "false")
    boolean isPinned,
    @Schema(description = "Persoenlicher Marker des Anfragers: markiert.", example = "false")
    boolean isFlagged,
    @Schema(description = "Persoenlicher Marker des Anfragers: stummgeschaltet.", example = "false")
    boolean isMuted,
    @Schema(description = "Aktueller Austritt des Anfragers (handover/unsubscribed) oder null.")
    ExitModel exit) {}
