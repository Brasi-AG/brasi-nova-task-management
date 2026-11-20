package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.attachment.model.AttachmentLinksModel;
import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bildet einen gespeicherten {@link TaskAttachment} auf das API-Modell {@link AttachmentModel} ab
 * (Intake #152).
 *
 * <p>Die Links sind gateway-relativ: sie beginnen mit {@code brasi.openapi.public-base} (Karte
 * {@code overview}: nie die direkte Adresse des Dienstes nach aussen geben) und zeigen immer auf
 * den Aufgaben-Pfad, auch fuer Kommentar-Anhaenge. {@code hasThumbnail} folgt dem Dateityp (PDF,
 * PNG, JPEG), nicht dem Kennzeichen {@code HAT_VORSCHAU}: fehlt ein Vorschaubild, erzeugt der Abruf
 * es neu.
 */
@Component
public class AttachmentModelMapper {

  private static final String TASKS_PATH = "/api/v1/tasks/";
  private static final String ATTACHMENTS_SEGMENT = "/attachments/";

  private final AuditTimeConverter auditTimeConverter;
  private final String publicBase;

  /**
   * Konstruktor-Injektion.
   *
   * @param auditTimeConverter wandelt {@code ANGELEGT_AM} in UTC um
   * @param publicBase oeffentliche Basis der Links ({@code brasi.openapi.public-base}), ohne Wert
   *     leer
   */
  public AttachmentModelMapper(
      final AuditTimeConverter auditTimeConverter,
      @Value("${brasi.openapi.public-base:}") final String publicBase) {
    this.auditTimeConverter = auditTimeConverter;
    this.publicBase = publicBase == null ? "" : publicBase;
  }

  /**
   * Bildet einen Anhang ab.
   *
   * @param attachment gespeicherter Anhang
   * @param taskExternalId externe UUID der Aufgabe (Pfad der Links)
   * @param removable ob der Anfrager den Anhang entfernen darf
   * @return API-Modell mit Links
   */
  public AttachmentModel toModel(
      final TaskAttachment attachment, final UUID taskExternalId, final boolean removable) {
    final boolean previewable =
        AttachmentFileType.fromExtension(attachment.getFileExtension())
            .map(AttachmentFileType::previewable)
            .orElse(false);
    final String base =
        publicBase + TASKS_PATH + taskExternalId + ATTACHMENTS_SEGMENT + attachment.getUuid();
    final AttachmentLinksModel links =
        new AttachmentLinksModel(
            base + "/content", base + "/download", previewable ? base + "/thumbnail" : null);
    return new AttachmentModel(
        attachment.getUuid(),
        attachment.getFileName(),
        attachment.getMimeType(),
        attachment.getSizeBytes(),
        previewable,
        attachment.getUploadedByRef(),
        auditTimeConverter.toInstant(attachment.getCreatedAt()),
        removable,
        links);
  }
}
