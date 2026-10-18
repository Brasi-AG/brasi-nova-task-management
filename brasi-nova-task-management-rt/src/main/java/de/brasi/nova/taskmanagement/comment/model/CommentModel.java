package de.brasi.nova.taskmanagement.comment.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Antwort-DTO fuer einen neu erstellten Kommentar.
 *
 * @param id              externe UUID des neuen Kommentars
 * @param taskId          externe UUID der Aufgabe
 * @param authorRef       UUID des Autors (PROD_BENUTZER.EXTERNALID)
 * @param parentCommentId externe UUID des Eltern-Kommentars (null bei Top-Level)
 * @param text            Kommentartext
 * @param edited          Kennzeichen: wurde der Kommentar nachtraeglich bearbeitet
 * @param createdAt       Erstellungszeitpunkt
 */
public record CommentModel(
    @Schema(description = "Externe UUID des Kommentars.", example = "660e8400-e29b-41d4-a716-446655440001")
    UUID id,
    @Schema(description = "Externe UUID der Aufgabe.", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID taskId,
    @Schema(description = "UUID des Kommentar-Autors.", example = "770e8400-e29b-41d4-a716-446655440002")
    UUID authorRef,
    @Schema(description = "Externe UUID des Eltern-Kommentars (null bei Top-Level-Kommentar).",
        example = "null")
    UUID parentCommentId,
    @Schema(description = "Kommentartext.", example = "Bitte bis Ende der Woche erledigen.")
    String text,
    @Schema(description = "true wenn der Kommentar nachtraeglich bearbeitet wurde.", example = "false")
    boolean edited,
    @Schema(description = "Erstellungszeitpunkt (UTC).", example = "2026-10-18T08:00:00Z")
    Instant createdAt) {}
