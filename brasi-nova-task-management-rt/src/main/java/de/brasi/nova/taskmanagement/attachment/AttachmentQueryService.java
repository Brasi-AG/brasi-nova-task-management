package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.user.UserAccessor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

/**
 * Lesen und Ausliefern von Anhaengen an Aufgaben (Intake #152): Liste, Inhalt, Vorschaubild und die
 * Anhaenge im Aufgaben-Detail.
 *
 * <p>Jeder Abruf prueft zuerst die READ-Mitgliedschaft ({@code
 * TaskMembershipGuard#requireReadMember} ueber {@link AttachmentTaskAccess#requireReadMember});
 * ausgetretene Mitglieder lesen weiter, alle anderen erhalten die neutrale 404. Ein Anhang wird nur
 * innerhalb seiner Aufgabe gefunden (Aufgaben- und Kommentar-Anhaenge, nur ACTIVE); sonst {@code
 * Anhang nicht gefunden.}.
 *
 * <p>Auslieferung: Erst wird geprueft, dass die Datei da ist, dann der ETag verglichen; ein
 * passender {@code If-None-Match} ergibt 304 nur fuer eine vorhandene Datei. Der Strom des Inhalts
 * wird hier geoeffnet, damit eine fehlende Datei synchron als neutrale 404 entsteht (mit
 * Fehler-Log, Anhang-UUID ohne Pfad) und nicht erst beim Schreiben der Antwort; bei 304 wird er
 * sofort wieder geschlossen. Das Vorschaubild wird vor dem Vergleich gelesen bzw. erzeugt, auch
 * weil sein ETag die Breite des ausgelieferten Bilds traegt. Fehlt das Vorschaubild eines
 * vorschaufaehigen Typs, wird es aus dem Inhalt erzeugt und abgelegt (Selbstheilung). Ein
 * gespeichertes Vorschaubild wird vollstaendig gelesen und mit bekannter Laenge ausgeliefert.
 * Dieser Service schreibt nie in die Datenbank.
 */
@Service
public class AttachmentQueryService {

  private static final Logger log = LoggerFactory.getLogger(AttachmentQueryService.class);

  private static final String ATTACHMENT_NOT_FOUND = "Anhang nicht gefunden.";
  private static final String TASK_NOT_FOUND = "Aufgabe nicht gefunden.";
  private static final String THUMBNAIL_MIME_TYPE = "image/jpeg";
  private static final String JPEG_FORMAT = "jpeg";
  private static final int FIRST_IMAGE = 0;

  private final UserAccessor userAccessor;
  private final AttachmentTaskAccess taskAccess;
  private final TaskAttachmentQueryRepository attachmentRepository;
  private final TaskParticipantQueryRepository participantQueryRepository;
  private final AttachmentContentProvider contentProvider;
  private final AttachmentThumbnailGenerator thumbnailGenerator;
  private final AttachmentModelMapper modelMapper;
  private final AttachmentProperties properties;

  /**
   * Konstruktor-Injektion.
   *
   * @param userAccessor liefert den angemeldeten Benutzer
   * @param taskAccess laedt die Aufgabe mit Mitgliedschaftspruefung
   * @param attachmentRepository Lesezugriff auf die Anhaenge
   * @param participantQueryRepository Teilnehmerzeile des Anfragers (Entfernen-Recht)
   * @param contentProvider Zugriff auf die Dateien
   * @param thumbnailGenerator erzeugt fehlende Vorschaubilder
   * @param modelMapper bildet Anhaenge auf das API-Modell ab
   * @param properties Cache-Dauer
   */
  public AttachmentQueryService(
      final UserAccessor userAccessor,
      final AttachmentTaskAccess taskAccess,
      final TaskAttachmentQueryRepository attachmentRepository,
      final TaskParticipantQueryRepository participantQueryRepository,
      final AttachmentContentProvider contentProvider,
      final AttachmentThumbnailGenerator thumbnailGenerator,
      final AttachmentModelMapper modelMapper,
      final AttachmentProperties properties) {
    this.userAccessor = userAccessor;
    this.taskAccess = taskAccess;
    this.attachmentRepository = attachmentRepository;
    this.participantQueryRepository = participantQueryRepository;
    this.contentProvider = contentProvider;
    this.thumbnailGenerator = thumbnailGenerator;
    this.modelMapper = modelMapper;
    this.properties = properties;
  }

  /**
   * Liefert die aktiven Anhaenge der Aufgabe (ohne Kommentar-Anhaenge) in Hochlade-Reihenfolge.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @return Anhaenge mit Abruf-Links und {@code removable} fuer den Anfrager
   * @throws EntityNotFoundException wenn die Aufgabe nicht sichtbar oder der Anfrager kein
   *     READ-Mitglied ist (HTTP 404)
   */
  public List<AttachmentModel> listTaskAttachments(final UUID taskExternalId) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final Task task = taskAccess.requireReadMember(taskExternalId, actor);
    return toModels(task, actor.user().id());
  }

  /**
   * Liefert die Anhaenge der Aufgabe fuer das Aufgaben-Detail, ohne eigene Mitgliedschaftspruefung:
   * der Aufrufer ({@code TaskDetailService#loadDetail}) hat Aufgabe, Mandant und
   * READ-Mitgliedschaft bereits geprueft.
   *
   * <p>{@code removable} kommt aus genau einer Lesung der Teilnehmerzeile des Anfragers: aktiv und
   * nicht ausgetreten (WRITE-Mitglied) und zugleich hochladende Person oder Ersteller der Aufgabe.
   * Bewusst kein {@code TaskMembershipGuard#isWriteMember}: das Detail prueft die Schreib-
   * Mitgliedschaft genau einmal (Link-Bedingung).
   *
   * @param task bereits gepruefte Aufgabe
   * @param actorRef UUID des Anfragers
   * @param tenantId Mandant des Anfragers (muss der Mandant der Aufgabe sein)
   * @return Anhaenge in Hochlade-Reihenfolge
   * @throws EntityNotFoundException wenn die Aufgabe nicht zum Mandanten gehoert (HTTP 404)
   */
  public List<AttachmentModel> taskAttachmentsForDetail(
      final Task task, final UUID actorRef, final long tenantId) {
    if (!Objects.equals(tenantId, task.getTenantId())) {
      throw new EntityNotFoundException(TASK_NOT_FOUND);
    }
    return toModels(task, actorRef);
  }

  /**
   * Liefert den Inhalt eines Anhangs der Aufgabe (auch eines Kommentar-Anhangs).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param attachmentId externe UUID des Anhangs
   * <p>Die Datei wird vor dem ETag-Vergleich geoeffnet: ein passender {@code If-None-Match}
   * bestaetigt nur eine vorhandene Datei als unveraendert (304, der Strom wird sofort geschlossen);
   * fehlt sie, bleibt es bei der neutralen 404.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param attachmentId externe UUID des Anhangs
   * @param ifNoneMatch Wert des Headers {@code If-None-Match} oder {@code null}
   * @return 304-Ergebnis ohne Strom oder der geoeffnete Inhalt mit Art, Name und Groesse
   * @throws EntityNotFoundException wenn Aufgabe oder Anhang nicht sichtbar sind oder die Datei
   *     fehlt, auch bei passendem {@code If-None-Match} (HTTP 404)
   */
  public AttachmentDelivery serveContent(
      final UUID taskExternalId, final UUID attachmentId, final String ifNoneMatch) {
    final TaskAttachment attachment = requireAttachment(taskExternalId, attachmentId);
    final String etag = AttachmentEtags.contentEtag(attachment.getSha256());
    final Duration maxAge = properties.cache().contentMaxAge();
    final InputStream stream = openContent(attachment);
    if (AttachmentEtags.matches(ifNoneMatch, etag)) {
      closeQuietly(stream, attachment);
      return AttachmentDelivery.unchanged(etag, maxAge);
    }
    return new AttachmentDelivery(
        false,
        etag,
        attachment.getMimeType(),
        attachment.getSizeBytes(),
        attachment.getFileName(),
        stream,
        maxAge);
  }

  /**
   * Liefert das Vorschaubild (JPEG) eines Anhangs der Aufgabe. Fehlt die Datei des Vorschaubilds,
   * wird es aus dem Inhalt erzeugt und abgelegt; die Datenbank bleibt unveraendert.
   *
   * <p>Der ETag traegt die tatsaechliche Breite des ausgelieferten Bilds (aus dem JPEG-Kopf, ohne
   * Dekodieren), nicht die konfigurierte: ein vor einer Aenderung der Vorschaubreite gespeichertes
   * Vorschaubild behaelt so seinen ETag. Deshalb wird das Bild vor dem ETag-Vergleich gelesen bzw.
   * erzeugt.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param attachmentId externe UUID des Anhangs
   * @param ifNoneMatch Wert des Headers {@code If-None-Match} oder {@code null}
   * @return 304-Ergebnis ohne Strom oder das Vorschaubild
   * @throws EntityNotFoundException wenn Aufgabe oder Anhang nicht sichtbar sind, der Typ kein
   *     Vorschaubild hat, die Inhaltsdatei fehlt, die Erzeugung scheitert oder das Vorschaubild
   *     kein lesbares JPEG ist (HTTP 404)
   */
  public AttachmentDelivery serveThumbnail(
      final UUID taskExternalId, final UUID attachmentId, final String ifNoneMatch) {
    final TaskAttachment attachment = requireAttachment(taskExternalId, attachmentId);
    final AttachmentFileType type =
        AttachmentFileType.fromExtension(attachment.getFileExtension())
            .filter(AttachmentFileType::previewable)
            .orElseThrow(() -> new EntityNotFoundException(ATTACHMENT_NOT_FOUND));
    final String storageKey = requireStorageKey(attachment);
    final Optional<InputStream> stored = contentProvider.openThumbnail(storageKey);
    final byte[] jpeg =
        stored.isPresent()
            ? readStoredThumbnail(stored.get())
            : regenerateThumbnail(attachment, type, storageKey);
    final String etag =
        AttachmentEtags.thumbnailEtag(attachment.getSha256(), requireJpegWidth(attachment, jpeg));
    final Duration maxAge = properties.cache().thumbnailMaxAge();
    if (AttachmentEtags.matches(ifNoneMatch, etag)) {
      return AttachmentDelivery.unchanged(etag, maxAge);
    }
    return thumbnailDelivery(etag, new ByteArrayInputStream(jpeg), jpeg.length, maxAge);
  }

  /**
   * Liest die Breite eines JPEG aus dem Bildkopf ({@link ImageReader#getWidth}, ohne Dekodieren).
   *
   * @param jpeg Bytes des Bilds
   * @return Breite in Pixeln; leer, wenn die Bytes kein lesbares JPEG sind
   */
  static OptionalInt jpegWidth(final byte[] jpeg) {
    final Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(JPEG_FORMAT);
    if (!readers.hasNext()) {
      return OptionalInt.empty();
    }
    final ImageReader reader = readers.next();
    try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
      reader.setInput(input, true, true);
      final int width = reader.getWidth(FIRST_IMAGE);
      return width > 0 ? OptionalInt.of(width) : OptionalInt.empty();
    } catch (IOException | RuntimeException e) {
      return OptionalInt.empty();
    } finally {
      reader.dispose();
    }
  }

  /** Breite des Vorschaubilds fuer den ETag; kein lesbares JPEG ergibt die neutrale 404 (WARN). */
  private static int requireJpegWidth(final TaskAttachment attachment, final byte[] jpeg) {
    final OptionalInt width = jpegWidth(jpeg);
    if (width.isEmpty()) {
      log.warn(
          "Vorschaubild fuer Anhang {} ist kein lesbares JPEG; Abruf mit 404 beantwortet.",
          attachment.getUuid());
      throw new EntityNotFoundException(ATTACHMENT_NOT_FOUND);
    }
    return width.getAsInt();
  }

  /** Laedt die Anhaenge der Aufgabe und berechnet {@code removable} aus einer Teilnehmerzeile. */
  private List<AttachmentModel> toModels(final Task task, final UUID actorRef) {
    final List<TaskAttachment> attachments =
        attachmentRepository.findActiveTaskAttachments(task.getId());
    if (attachments.isEmpty()) {
      return List.of();
    }
    final Optional<TaskParticipant> actorRow =
        participantQueryRepository.findByTaskIdAndParticipantRef(task.getId(), actorRef).stream()
            .findFirst();
    final boolean writeMember = actorRow.map(row -> row.getExitKind() == null).orElse(false);
    final boolean creator = writeMember && actorRow.get().isCreatorFlag();
    return attachments.stream()
        .map(
            attachment ->
                modelMapper.toModel(
                    attachment,
                    task.getUuid(),
                    writeMember
                        && (creator || Objects.equals(actorRef, attachment.getUploadedByRef()))))
        .toList();
  }

  /** READ-Mitgliedschaft pruefen, dann den aktiven Anhang innerhalb der Aufgabe laden. */
  private TaskAttachment requireAttachment(final UUID taskExternalId, final UUID attachmentId) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final Task task = taskAccess.requireReadMember(taskExternalId, actor);
    final List<TaskAttachment> found =
        attachmentRepository.findActiveByTaskIdAndUuid(task.getId(), attachmentId);
    if (found.isEmpty()) {
      throw new EntityNotFoundException(ATTACHMENT_NOT_FOUND);
    }
    return found.get(0);
  }

  /** Oeffnet die Inhaltsdatei; fehlt sie (oder der Schluessel), neutrale 404 mit Fehler-Log. */
  private InputStream openContent(final TaskAttachment attachment) {
    final String storageKey = requireStorageKey(attachment);
    final Optional<InputStream> stream = contentProvider.openContent(storageKey);
    if (stream.isEmpty()) {
      log.error(
          "Datei des aktiven Anhangs {} fehlt im Speicher; Abruf mit 404 beantwortet.",
          attachment.getUuid());
      throw new EntityNotFoundException(ATTACHMENT_NOT_FOUND);
    }
    return stream.get();
  }

  /**
   * Schliesst den bei 304 nicht benoetigten Strom; ein Fehler beim Schliessen aendert die Antwort
   * nicht (Warnung ohne Pfad).
   */
  private static void closeQuietly(final InputStream stream, final TaskAttachment attachment) {
    try {
      stream.close();
    } catch (IOException e) {
      log.warn(
          "Strom des Anhangs {} liess sich nach 304 nicht schliessen ({}).",
          attachment.getUuid(),
          e.getClass().getSimpleName());
    }
  }

  /** Speicherschluessel des Anhangs; fehlt er, neutrale 404 mit Fehler-Log. */
  private String requireStorageKey(final TaskAttachment attachment) {
    final String storageKey = attachment.getStorageKey();
    if (storageKey == null || storageKey.isBlank()) {
      log.error(
          "Aktiver Anhang {} hat keinen Speicherschluessel; Abruf mit 404 beantwortet.",
          attachment.getUuid());
      throw new EntityNotFoundException(ATTACHMENT_NOT_FOUND);
    }
    return storageKey;
  }

  /**
   * Erzeugt das fehlende Vorschaubild aus dem Inhalt und legt es ab. Scheitert das Ablegen, wird
   * das erzeugte Bild trotzdem ausgeliefert (Warnung); scheitert die Erzeugung (auch nach Ablauf
   * des Zeitlimits oder bei vollem Pool des Generators), neutrale 404.
   */
  private byte[] regenerateThumbnail(
      final TaskAttachment attachment, final AttachmentFileType type, final String storageKey) {
    final Optional<byte[]> generated;
    try (InputStream content = openContent(attachment)) {
      // Einmal lesbare Quelle: der Generator liest den Inhalt genau einmal.
      generated =
          thumbnailGenerator.generate(
              type, new InputStreamResource(content, "Anhang " + attachment.getUuid()));
    } catch (IOException e) {
      throw new UncheckedIOException("Inhaltsdatei eines Anhangs nicht lesbar.", e);
    }
    if (generated.isEmpty()) {
      log.warn(
          "Vorschaubild fuer Anhang {} konnte nicht erzeugt werden; Abruf mit 404 beantwortet.",
          attachment.getUuid());
      throw new EntityNotFoundException(ATTACHMENT_NOT_FOUND);
    }
    final byte[] jpeg = generated.get();
    try {
      contentProvider.writeThumbnail(storageKey, jpeg);
      log.info("Vorschaubild fuer Anhang {} nachtraeglich erzeugt.", attachment.getUuid());
    } catch (RuntimeException e) {
      log.warn(
          "Nachtraeglich erzeugtes Vorschaubild fuer Anhang {} konnte nicht abgelegt werden ({});"
              + " es wird nur ausgeliefert.",
          attachment.getUuid(),
          e.getClass().getSimpleName());
    }
    return jpeg;
  }

  /**
   * Liest ein gespeichertes Vorschaubild vollstaendig und schliesst den Strom. Vorschaubilder sind
   * klein; so ist die Laenge bekannt ({@code Content-Length}) und kein Dateistrom bleibt ueber die
   * asynchrone Grenze der Auslieferung offen.
   */
  private static byte[] readStoredThumbnail(final InputStream stream) {
    try (InputStream in = stream) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Vorschaubild eines Anhangs nicht lesbar.", e);
    }
  }

  private static AttachmentDelivery thumbnailDelivery(
      final String etag, final InputStream stream, final long length, final Duration maxAge) {
    return new AttachmentDelivery(false, etag, THUMBNAIL_MIME_TYPE, length, null, stream, maxAge);
  }
}
