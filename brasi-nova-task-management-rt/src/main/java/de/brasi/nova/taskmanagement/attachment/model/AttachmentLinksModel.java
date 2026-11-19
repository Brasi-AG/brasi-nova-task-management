package de.brasi.nova.taskmanagement.attachment.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Abruf-Links eines Anhangs (Intake #152), gateway-relativ ueber {@code brasi.openapi.public-base}.
 * Jeder Abruf braucht das Bearer-JWT und prueft die Mitgliedschaft; die Links sind keine signierten
 * URLs.
 *
 * @param content Inhalt zur Anzeige im Browser
 * @param download Inhalt als Download
 * @param thumbnail Vorschaubild; {@code null} bei Typen ohne Vorschau
 */
@Schema(name = "AttachmentLinks", description = "Abruf-Links eines Anhangs.")
public record AttachmentLinksModel(
    @Schema(
            description =
                "Pfad des Inhalts zur Anzeige (Content-Disposition inline); Abruf mit Bearer-JWT.",
            example =
                "/task-management/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/attachments/"
                    + "a1520000-0000-4000-8000-000000000001/content")
        String content,
    @Schema(
            description =
                "Pfad des Inhalts als Download (Content-Disposition attachment); Abruf mit"
                    + " Bearer-JWT.",
            example =
                "/task-management/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/attachments/"
                    + "a1520000-0000-4000-8000-000000000001/download")
        String download,
    @Schema(
            description =
                "Pfad des Vorschaubilds (JPEG); null bei Typen ohne Vorschau (Office-Dokumente,"
                    + " Text). Nur PDF, PNG und JPEG haben ein Vorschaubild.",
            example =
                "/task-management/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/attachments/"
                    + "a1520000-0000-4000-8000-000000000001/thumbnail",
            nullable = true)
        String thumbnail) {}
