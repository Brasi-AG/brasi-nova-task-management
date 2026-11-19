package de.brasi.nova.taskmanagement.attachment.model;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Reine OpenAPI-Beschreibung des Multipart-Requests von {@code POST
 * /api/v1/tasks/{id}/attachments} (Intake #152). Zur Laufzeit liest der Endpunkt die Teile {@code
 * files} als {@code MultipartFile}; dieser Record beschreibt nur die Wire-Form und wird nie
 * instanziiert (Muster {@code ArchiveSearchPageSchema}).
 *
 * @param files hochzuladende Dateien
 */
@Schema(
    name = "AttachmentUpload",
    description = "Multipart-Formular mit einer oder mehreren Dateien im Teil files.",
    requiredProperties = {"files"})
public record AttachmentUploadSchema(
    @ArraySchema(
            arraySchema =
                @Schema(
                    description =
                        "Hochzuladende Dateien, je Datei ein Teil mit dem Namen files (mindestens"
                            + " eine). Erlaubt sind pdf, xls, xlsx, doc, docx, ppt, pptx, txt,"
                            + " png, jpg und jpeg."),
            schema =
                @Schema(
                    type = "string",
                    format = "binary",
                    description = "Binaerinhalt einer Datei mit ihrem Originalnamen."))
        List<String> files) {}
