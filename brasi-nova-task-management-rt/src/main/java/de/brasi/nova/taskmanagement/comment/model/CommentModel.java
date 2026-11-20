package de.brasi.nova.taskmanagement.comment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Antwort-DTO fuer einen Kommentar (Create-Antwort wie auch Lese-Liste BE-2).
 *
 * @param id externe UUID des Kommentars
 * @param taskId externe UUID der Aufgabe
 * @param authorRef UUID des Autors (PROD_BENUTZER.EXTERNALID)
 * @param parentCommentId externe UUID des Eltern-Kommentars; null bei Top-Level ODER wenn der
 *     Eltern-Kommentar nicht mehr aktiv ist (Lese-Projektion BE-2)
 * @param text Kommentartext
 * @param edited Kennzeichen: wurde der Kommentar nachtraeglich bearbeitet
 * @param createdAt Erstellungszeitpunkt
 * @param isNew Kennzeichen fuer den Anfrager (Intake #150): {@code true}, wenn der Kommentar nach
 *     seinem Gesehen-Zeitpunkt ({@code PUT /api/v1/tasks/{id}/seen}) erstellt wurde und nicht von
 *     ihm selbst stammt; ohne Gesehen-Zeitpunkt (erster Besuch) sind alle Kommentare anderer neu.
 *     In der Antwort auf das Anlegen stets {@code false} (eigener Kommentar).
 * @param attachments aktive Anhaenge des Kommentars (Intake #152), chronologisch nach Hochladen;
 *     nie {@code null} (ohne Anhang leer) und unveraenderlich. Kommentar-Anhaenge sind nie
 *     entfernbar ({@code removable = false}); ihre Links zeigen auf den Aufgaben-Pfad.
 */
public record CommentModel(
    @Schema(
            description = "Externe UUID des Kommentars.",
            example = "660e8400-e29b-41d4-a716-446655440001")
        UUID id,
    @Schema(
            description = "Externe UUID der Aufgabe.",
            example = "550e8400-e29b-41d4-a716-446655440000")
        UUID taskId,
    @Schema(
            description = "UUID des Kommentar-Autors.",
            example = "770e8400-e29b-41d4-a716-446655440002")
        UUID authorRef,
    @Schema(
            description =
                "Externe UUID des Eltern-Kommentars; null bei Top-Level ODER wenn der"
                    + " Eltern-Kommentar nicht mehr aktiv ist (Lese-Projektion BE-2).",
            nullable = true)
        UUID parentCommentId,
    @Schema(description = "Kommentartext.", example = "Bitte bis Ende der Woche erledigen.")
        String text,
    @Schema(
            description = "true wenn der Kommentar nachtraeglich bearbeitet wurde.",
            example = "false")
        boolean edited,
    @Schema(description = "Erstellungszeitpunkt (UTC).", example = "2026-10-18T08:00:00Z")
        Instant createdAt,
    @JsonProperty("isNew")
        @Schema(
            description =
                "true, wenn der Kommentar fuer den Anfrager neu ist: erstellt nach dessen"
                    + " Gesehen-Zeitpunkt (PUT /api/v1/tasks/{id}/seen) und nicht vom Anfrager"
                    + " selbst. Ohne Gesehen-Zeitpunkt (erster Besuch) gelten alle Kommentare"
                    + " anderer als neu, eigene nie. In der Antwort auf das Anlegen stets false.",
            example = "true")
        boolean isNew,
    @ArraySchema(
            arraySchema =
                @Schema(description = "Anhänge des Kommentars, chronologisch; unveränderlich"))
        List<AttachmentModel> attachments) {

  /** Normalisiert {@code attachments}: nie {@code null}, als unveraenderliche Kopie. */
  public CommentModel {
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }
}
