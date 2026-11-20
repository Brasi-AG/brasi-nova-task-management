package de.brasi.nova.taskmanagement.attachment.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Anhang einer Aufgabe oder eines Kommentars in der API (Intake #152). Der Inhalt selbst steht nie
 * im JSON, sondern wird ueber {@link #links()} gestreamt.
 *
 * @param id externe UUID des Anhangs
 * @param fileName bereinigter Originalname
 * @param mimeType beim Hochladen gepruefte Inhaltsart
 * @param sizeBytes Groesse in Bytes
 * @param hasThumbnail ob der Typ ein Vorschaubild hat (PDF, PNG, JPEG)
 * @param uploadedById UUID der hochladenden Person
 * @param createdAt Zeitpunkt des Hochladens (UTC)
 * @param removable ob der Anfrager den Anhang entfernen darf
 * @param links Abruf-Links
 */
@Schema(name = "AttachmentModel", description = "Anhang an einer Aufgabe oder einem Kommentar.")
public record AttachmentModel(
    @Schema(
            description = "Externe UUID des Anhangs.",
            example = "a1520000-0000-4000-8000-000000000001")
        UUID id,
    @Schema(
            description =
                "Originaler Dateiname, bereinigt (nur der letzte Pfadanteil, ohne Steuerzeichen,"
                    + " hoechstens 255 Zeichen).",
            example = "Bericht.pdf")
        String fileName,
    @Schema(
            description = "Beim Hochladen gepruefte Inhaltsart (Content-Type der Auslieferung).",
            example = "application/pdf")
        String mimeType,
    @Schema(description = "Groesse der Datei in Bytes.", example = "184320") long sizeBytes,
    @Schema(
            description =
                "Ob der Dateityp ein Vorschaubild hat (PDF, PNG, JPEG); dann zeigt"
                    + " links.thumbnail darauf.",
            example = "true")
        boolean hasThumbnail,
    @Schema(
            description = "UUID der Person, die die Datei hochgeladen hat.",
            example = "880e8400-e29b-41d4-a716-446655440003")
        UUID uploadedById,
    @Schema(description = "Zeitpunkt des Hochladens (UTC).", example = "2026-11-19T09:15:00Z")
        Instant createdAt,
    @Schema(
            description =
                "Ob der Anfrager den Anhang entfernen darf: aktives Mitglied der Aufgabe und"
                    + " hochladende Person oder Ersteller der Aufgabe; Kommentar-Anhaenge nie.",
            example = "true")
        boolean removable,
    @Schema(description = "Abruf-Links des Anhangs (Inhalt, Download, Vorschaubild).")
        AttachmentLinksModel links) {}
