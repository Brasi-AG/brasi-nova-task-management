package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PDF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.util.unit.DataSize;

/**
 * Unit-Tests fuer {@link AttachmentQueryService} (Intake #152, Task A8): gemockter Speicher,
 * Generator und Repositories, echter {@link AttachmentTaskAccess} und {@link
 * AttachmentModelMapper}.
 *
 * <p>Vertrag: jeder Abruf prueft zuerst die READ-Mitgliedschaft; ein passender {@code
 * If-None-Match} liefert 304, ohne den Inhalt zu oeffnen; ein fehlendes Vorschaubild eines
 * vorschaufaehigen Typs wird erzeugt und abgelegt, ohne in die Datenbank zu schreiben; nicht
 * vorschaufaehige Typen und fehlende Dateien ergeben die neutrale 404 {@code Anhang nicht
 * gefunden.}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttachmentQueryServiceTest {

  private static final long TENANT = 560L;
  private static final UUID TENANT_UUID = UUID.fromString("5f0a3c1e-1d2b-4c3a-9e8f-0a1b2c3d4e50");
  private static final Long TASK_ID = 4711L;
  private static final UUID TASK_UUID = UUID.fromString("1c9e6f0a-7b3d-4e2a-8f51-2d4c6b8a0e11");
  private static final UUID ACTOR = UUID.fromString("a0000000-0000-4000-8000-000000000001");
  private static final UUID OTHER = UUID.fromString("b0000000-0000-4000-8000-000000000002");
  private static final UUID ATT_UUID = UUID.fromString("d0000000-0000-4000-8000-000000000004");
  private static final long ATT_ID = 10_000_095L;
  private static final String SHA = "0123456789abcdef".repeat(4);
  private static final String KEY = AttachmentStorageKeys.contentKey(TENANT, ATT_ID, "pdf");
  private static final byte[] PDF_BYTES =
      "%PDF-1.7\n% Testinhalt\n".getBytes(StandardCharsets.ISO_8859_1);
  /** Echtes JPEG in der konfigurierten Breite: der Vorschau-ETag liest die Breite aus dem Kopf. */
  private static final byte[] JPEG = jpeg(600, 400);

  /** Echtes JPEG schmaler als konfiguriert (z. B. vor einer Aenderung der Vorschaubreite). */
  private static final byte[] NARROW_JPEG = jpeg(300, 200);

  @Mock private UserAccessor userAccessor;
  @Mock private BaseTaskRepository taskRepo;
  @Mock private TaskMembershipGuard guard;
  @Mock private TaskAttachmentQueryRepository attachmentRepo;
  @Mock private TaskParticipantQueryRepository participantRepo;
  @Mock private AttachmentContentProvider provider;
  @Mock private AttachmentThumbnailGenerator generator;

  private Task task;
  private TaskAttachment attachment;
  private AttachmentQueryService service;

  @BeforeEach
  void setUp() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT));
    when(userAccessor.requireCurrentUser()).thenReturn(new AuthUser(ACTOR, "actor@brasi.de"));

    task = new Task();
    task.setId(TASK_ID);
    task.setUuid(TASK_UUID);
    task.setTenantId(TENANT);
    task.setEntityState(EntityState.ACTIVE);
    when(taskRepo.findByUuid(TASK_UUID.toString())).thenReturn(Optional.of(task));

    attachment = attachment(ATT_ID, ATT_UUID, "Bericht.pdf", "pdf", ACTOR);
    attachment.setStorageKey(KEY);
    when(attachmentRepo.findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID))
        .thenReturn(List.of(attachment));
    when(provider.openContent(KEY)).thenAnswer(inv -> Optional.of(pdfStream()));

    service =
        new AttachmentQueryService(
            userAccessor,
            new AttachmentTaskAccess(taskRepo, guard),
            attachmentRepo,
            participantRepo,
            provider,
            generator,
            new AttachmentModelMapper(new AuditTimeConverter("Europe/Berlin"), "/task-management"),
            properties());
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void i152_thumbnail_self_heal_writes_file_and_no_db_write() throws Exception {
    when(provider.openThumbnail(KEY))
        .thenReturn(Optional.empty())
        .thenAnswer(inv -> Optional.of(jpegStream()));
    when(generator.generate(eq(PDF), any())).thenReturn(Optional.of(JPEG));

    final AttachmentDelivery d = service.serveThumbnail(TASK_UUID, ATT_UUID, null);

    assertThat(d.notModified()).isFalse();
    assertThat(d.mimeType()).isEqualTo("image/jpeg");
    assertThat(d.etag()).isEqualTo(AttachmentEtags.thumbnailEtag(SHA, 600));
    assertThat(d.maxAge()).isEqualTo(Duration.ofDays(10));
    try (InputStream in = d.stream()) {
      assertThat(in.readAllBytes()).isEqualTo(JPEG);
    }
    verify(guard).requireReadMember(TASK_ID, ACTOR, TENANT);
    verify(provider).writeThumbnail(KEY, JPEG);
    verify(attachmentRepo, never()).save(any());
  }

  @Test
  void i152_thumbnail_served_from_store_without_generation() throws Exception {
    when(provider.openThumbnail(KEY)).thenAnswer(inv -> Optional.of(jpegStream()));

    final AttachmentDelivery d = service.serveThumbnail(TASK_UUID, ATT_UUID, null);

    assertThat(d.mimeType()).isEqualTo("image/jpeg");
    assertThat(d.contentLength()).isEqualTo(JPEG.length);
    try (InputStream in = d.stream()) {
      assertThat(in.readAllBytes()).isEqualTo(JPEG);
    }
    verifyNoInteractions(generator);
    verify(provider, never()).writeThumbnail(any(), any());
  }

  @Test
  void i152_thumbnail_etag_carries_width_of_delivered_image() throws Exception {
    // Gespeichertes Vorschaubild mit 300 px bei konfigurierter Breite 600
    when(provider.openThumbnail(KEY)).thenAnswer(inv -> Optional.of(stream(NARROW_JPEG)));

    final AttachmentDelivery d = service.serveThumbnail(TASK_UUID, ATT_UUID, null);

    assertThat(d.etag()).isEqualTo("W/\"" + SHA + "-t300\"");
    try (InputStream in = d.stream()) {
      assertThat(in.readAllBytes()).isEqualTo(NARROW_JPEG);
    }

    // Ein ETag mit der konfigurierten Breite bestaetigt das alte Bild nicht
    final AttachmentDelivery configured =
        service.serveThumbnail(TASK_UUID, ATT_UUID, AttachmentEtags.thumbnailEtag(SHA, 600));
    assertThat(configured.notModified()).isFalse();
    assertThat(configured.etag()).endsWith("-t300\"");
    configured.stream().close();

    final AttachmentDelivery unchanged =
        service.serveThumbnail(TASK_UUID, ATT_UUID, AttachmentEtags.thumbnailEtag(SHA, 300));
    assertThat(unchanged.notModified()).isTrue();
    assertThat(unchanged.stream()).isNull();
    verifyNoInteractions(generator);
  }

  @Test
  void i152_regenerated_thumbnail_etag_carries_generated_width() throws Exception {
    when(provider.openThumbnail(KEY)).thenReturn(Optional.empty());
    when(generator.generate(eq(PDF), any())).thenReturn(Optional.of(NARROW_JPEG));

    final AttachmentDelivery d = service.serveThumbnail(TASK_UUID, ATT_UUID, null);

    assertThat(d.etag()).isEqualTo(AttachmentEtags.thumbnailEtag(SHA, 300));
    d.stream().close();
    verify(provider).writeThumbnail(KEY, NARROW_JPEG);
  }

  @Test
  void i152_unreadable_stored_thumbnail_is_404() {
    final byte[] broken = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 7, 7};
    when(provider.openThumbnail(KEY)).thenAnswer(inv -> Optional.of(stream(broken)));

    assertThatThrownBy(() -> service.serveThumbnail(TASK_UUID, ATT_UUID, null))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Anhang nicht gefunden.");
    assertThat(AttachmentQueryService.jpegWidth(broken)).isEmpty();
    assertThat(AttachmentQueryService.jpegWidth(JPEG)).hasValue(600);
  }

  @Test
  void i152_thumbnail_generation_failure_is_404() {
    when(provider.openThumbnail(KEY)).thenReturn(Optional.empty());
    when(generator.generate(eq(PDF), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.serveThumbnail(TASK_UUID, ATT_UUID, null))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Anhang nicht gefunden.");
    verify(provider, never()).writeThumbnail(any(), any());
    verify(attachmentRepo, never()).save(any());
  }

  @Test
  void i152_thumbnail_for_docx_is_404() {
    final TaskAttachment docx = attachment(ATT_ID, ATT_UUID, "Vertrag.docx", "docx", ACTOR);
    docx.setStorageKey(AttachmentStorageKeys.contentKey(TENANT, ATT_ID, "docx"));
    when(attachmentRepo.findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID)).thenReturn(List.of(docx));

    assertThatThrownBy(() -> service.serveThumbnail(TASK_UUID, ATT_UUID, null))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Anhang nicht gefunden.");
    verifyNoInteractions(generator);
  }

  @Test
  void i152_not_modified_does_not_open_stream() {
    final AttachmentDelivery d = service.serveContent(TASK_UUID, ATT_UUID, "W/\"" + SHA + "\"");

    assertThat(d.notModified()).isTrue();
    assertThat(d.etag()).isEqualTo("W/\"" + SHA + "\"");
    assertThat(d.maxAge()).isEqualTo(Duration.ofDays(1));
    assertThat(d.stream()).isNull();
    verify(provider, never()).openContent(any());
  }

  @Test
  void i152_content_delivery_carries_type_name_size_and_stream() throws Exception {
    final AttachmentDelivery d = service.serveContent(TASK_UUID, ATT_UUID, "W/\"anders\"");

    assertThat(d.notModified()).isFalse();
    assertThat(d.etag()).isEqualTo(AttachmentEtags.contentEtag(SHA));
    assertThat(d.mimeType()).isEqualTo("application/pdf");
    assertThat(d.fileName()).isEqualTo("Bericht.pdf");
    assertThat(d.contentLength()).isEqualTo(PDF_BYTES.length);
    assertThat(d.maxAge()).isEqualTo(Duration.ofDays(1));
    try (InputStream in = d.stream()) {
      assertThat(in.readAllBytes()).isEqualTo(PDF_BYTES);
    }
    verify(guard).requireReadMember(TASK_ID, ACTOR, TENANT);
  }

  @Test
  void i152_missing_content_file_or_key_is_neutral_404() {
    when(provider.openContent(KEY)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.serveContent(TASK_UUID, ATT_UUID, null))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Anhang nicht gefunden.");

    attachment.setStorageKey(null);
    assertThatThrownBy(() -> service.serveContent(TASK_UUID, ATT_UUID, null))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Anhang nicht gefunden.");
  }

  @Test
  void i152_unknown_attachment_is_404_and_non_member_is_404_before_lookup() {
    when(attachmentRepo.findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID)).thenReturn(List.of());
    assertThatThrownBy(() -> service.serveContent(TASK_UUID, ATT_UUID, null))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Anhang nicht gefunden.");

    org.mockito.Mockito.reset(attachmentRepo);
    org.mockito.Mockito.doThrow(new EntityNotFoundException("Aufgabe nicht gefunden."))
        .when(guard)
        .requireReadMember(TASK_ID, ACTOR, TENANT);
    assertThatThrownBy(() -> service.serveContent(TASK_UUID, ATT_UUID, "W/\"" + SHA + "\""))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
    verifyNoInteractions(attachmentRepo, provider);
  }

  @Test
  void i152_detail_removable_for_write_member_who_uploaded_or_created() {
    final TaskAttachment own = attachment(1L, UUID.randomUUID(), "eigen.pdf", "pdf", ACTOR);
    final TaskAttachment foreign = attachment(2L, UUID.randomUUID(), "fremd.txt", "txt", OTHER);
    when(attachmentRepo.findActiveTaskAttachments(TASK_ID)).thenReturn(List.of(own, foreign));

    // WRITE-Mitglied (Zeile ohne Austritt), nicht Ersteller: nur der eigene Anhang
    when(participantRepo.findByTaskIdAndParticipantRef(TASK_ID, ACTOR))
        .thenReturn(List.of(participant(false, null)));
    assertThat(service.taskAttachmentsForDetail(task, ACTOR, TENANT))
        .extracting(AttachmentModel::removable)
        .containsExactly(true, false);

    // WRITE-Mitglied und Ersteller: beide, in der Reihenfolge des Repositorys
    when(participantRepo.findByTaskIdAndParticipantRef(TASK_ID, ACTOR))
        .thenReturn(List.of(participant(true, null)));
    final List<AttachmentModel> asCreator = service.taskAttachmentsForDetail(task, ACTOR, TENANT);
    assertThat(asCreator).extracting(AttachmentModel::removable).containsExactly(true, true);
    assertThat(asCreator)
        .extracting(AttachmentModel::fileName)
        .containsExactly("eigen.pdf", "fremd.txt");

    // Ausgetreten (READ-, kein WRITE-Mitglied): nichts entfernbar, auch der eigene nicht
    when(participantRepo.findByTaskIdAndParticipantRef(TASK_ID, ACTOR))
        .thenReturn(List.of(participant(true, ExitKind.UNSUBSCRIBED)));
    assertThat(service.taskAttachmentsForDetail(task, ACTOR, TENANT))
        .extracting(AttachmentModel::removable)
        .containsExactly(false, false);

    // Keine Teilnehmerzeile: nichts entfernbar
    when(participantRepo.findByTaskIdAndParticipantRef(TASK_ID, ACTOR)).thenReturn(List.of());
    assertThat(service.taskAttachmentsForDetail(task, ACTOR, TENANT))
        .extracting(AttachmentModel::removable)
        .containsExactly(false, false);

    // Die Rolle kommt aus der einen Teilnehmerzeile: kein zusaetzlicher isWriteMember-Aufruf
    // (TaskDetailAffordanceIT pinnt genau eine Write-Membership-Pruefung je Detail-Request).
    verify(guard, never()).isWriteMember(any(), any(), anyLong());
    verify(guard, never()).requireReadMember(any(), any(), anyLong());
  }

  // ---- Hilfen ------------------------------------------------------------------------------

  private static TaskAttachment attachment(
      final long id,
      final UUID uuid,
      final String fileName,
      final String extension,
      final UUID uploadedBy) {
    final AttachmentFileType type = AttachmentFileType.fromExtension(extension).orElseThrow();
    final TaskAttachment a = new TaskAttachment();
    a.setId(id);
    a.setUuid(uuid);
    a.setTenantId(TENANT);
    a.setEntityState(EntityState.ACTIVE);
    a.setCreatedAt(LocalDateTime.now().minusHours(1));
    a.setTaskId(TASK_ID);
    a.setFileName(fileName);
    a.setMimeType(type.mimeType());
    a.setFileExtension(extension);
    a.setSizeBytes(PDF_BYTES.length);
    a.setSha256(SHA);
    a.setStorageKey(AttachmentStorageKeys.contentKey(TENANT, id, extension));
    a.setThumbnailPresent(type.previewable());
    a.setUploadedByRef(uploadedBy);
    return a;
  }

  private static TaskParticipant participant(final boolean creator, final ExitKind exitKind) {
    final TaskParticipant participant = new TaskParticipant();
    participant.setTenantId(TENANT);
    participant.setTaskId(TASK_ID);
    participant.setParticipantRef(ACTOR);
    participant.setCreatorFlag(creator);
    participant.setExitKind(exitKind);
    return participant;
  }

  private static InputStream pdfStream() {
    return new ByteArrayInputStream(PDF_BYTES);
  }

  private static InputStream jpegStream() {
    return new ByteArrayInputStream(JPEG);
  }

  private static InputStream stream(final byte[] bytes) {
    return new ByteArrayInputStream(bytes);
  }

  /** Einfarbiges RGB-JPEG der angegebenen Groesse. */
  private static byte[] jpeg(final int width, final int height) {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      if (!ImageIO.write(image, "jpg", out)) {
        throw new IllegalStateException("Kein JPEG-Schreiber verfuegbar.");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** Properties mit den Vorgaben aus application.properties (Vorschau 600 px, P1D und P10D). */
  private static AttachmentProperties properties() {
    return new AttachmentProperties(
        new AttachmentProperties.Storage(null),
        List.of(),
        20,
        new AttachmentProperties.Limits(
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(15),
            DataSize.ofMegabytes(15)),
        new AttachmentProperties.Thumbnail(600, 0.85f, 50_000_000L),
        new AttachmentProperties.Cache(Duration.ofDays(1), Duration.ofDays(10)));
  }
}
