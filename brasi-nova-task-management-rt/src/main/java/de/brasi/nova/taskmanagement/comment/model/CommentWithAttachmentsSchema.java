package de.brasi.nova.taskmanagement.comment.model;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Reine OpenAPI-Beschreibung des Multipart-Requests von {@code POST
 * /api/v1/tasks/{id}/comments/with-attachments} (Intake #152 Teil B). Zur Laufzeit liest der
 * Endpunkt den Teil {@code comment} als {@link CommentCreateInput} (JSON) und die Teile {@code
 * files} als {@code MultipartFile}; dieser Record beschreibt nur die Wire-Form und wird nie
 * instanziiert (Muster {@code AttachmentUploadSchema}).
 *
 * @param comment Kommentar als JSON-Teil
 * @param files hochzuladende Dateien
 */
@Schema(
    name = "CommentWithAttachments",
    description =
        "Multipart-Formular mit dem Kommentar im Teil comment (JSON mit Content-Type"
            + " application/json, Felder wie bei POST /api/v1/tasks/{id}/comments) und einer oder"
            + " mehreren Dateien im Teil files.",
    requiredProperties = {"comment", "files"})
public record CommentWithAttachmentsSchema(
    // Ohne eigene Beschreibung: swagger-core schriebe sie in die gemeinsame Komponente
    // CommentCreateInput (auch die der JSON-Variante); beschrieben ist das Feld ueber deren
    // Klassenbeschreibung.
    CommentCreateInput comment,
    @ArraySchema(
            arraySchema =
                @Schema(
                    description =
                        "Anzuhaengende Dateien, je Datei ein Teil mit dem Namen files (mindestens"
                            + " eine, hoechstens 20). Erlaubt sind pdf, xls, xlsx, doc, docx, ppt,"
                            + " pptx, txt, png, jpg und jpeg."),
            schema =
                @Schema(
                    type = "string",
                    format = "binary",
                    description = "Binaerinhalt einer Datei mit ihrem Originalnamen."))
        List<String> files) {}
