package de.brasi.nova.taskmanagement.comment.model;

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
    UUID id,
    UUID taskId,
    UUID authorRef,
    UUID parentCommentId,
    String text,
    boolean edited,
    Instant createdAt) {}
