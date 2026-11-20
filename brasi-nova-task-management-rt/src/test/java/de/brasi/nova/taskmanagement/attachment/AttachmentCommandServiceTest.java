package de.brasi.nova.taskmanagement.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.security.permission.PermissionDeniedException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.util.unit.DataSize;

/**
 * Unit-Tests fuer {@link AttachmentCommandService} (Intake #152, Tasks A7 und A9): gemockte
 * Repositories, Writer und Inbox-Dienste, echter {@link AttachmentTaskAccess} (ueber gemocktem
 * Aufgaben-Repository und Guard) und echter {@link AttachmentModelMapper}.
 *
 * <p>Vertrag (Karte {@code participants-actions}): alle Reads vor der ersten Schreibung. Beim
 * Hochladen ist die erste Schreibung {@link AttachmentWriter#store}, danach folgen der Seam {@code
 * onAfterAttachmentsStoredForTest}, der versionierte TASK-save (Versionskonflikt ergibt 409 ohne
 * Bump), die Historie je Datei, Bump und Signal fuer alle Leser und den Akteur. Beim Entfernen
 * (A9): Guard (404), Anhang der Aufgabe ohne Kommentar (404), Rolle Hochladende oder Ersteller
 * (403), Leser, erst dann die erste Schreibung (Anhang DEACTIVE), TASK-save, Historie, Bump und
 * Signal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttachmentCommandServiceTest {

  private static final long TENANT = 560L;
  private static final UUID TENANT_UUID = UUID.fromString("5f0a3c1e-1d2b-4c3a-9e8f-0a1b2c3d4e50");
  private static final Long TASK_ID = 4711L;
  private static final UUID TASK_UUID = UUID.fromString("1c9e6f0a-7b3d-4e2a-8f51-2d4c6b8a0e11");
  private static final UUID ACTOR = UUID.fromString("a0000000-0000-4000-8000-000000000001");
  private static final UUID READER_1 = UUID.fromString("b0000000-0000-4000-8000-000000000002");
  private static final UUID ARCHIVE_READER =
      UUID.fromString("c0000000-0000-4000-8000-000000000003");
  private static final UUID UPLOADER = UUID.fromString("e0000000-0000-4000-8000-000000000005");
  private static final UUID ATT_UUID = UUID.fromString("d0000000-0000-4000-8000-000000000004");
  private static final byte[] PDF_BYTES =
      "%PDF-1.7\n% Testinhalt\n".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] TXT_BYTES = "Notiz".getBytes(StandardCharsets.UTF_8);
  private static final byte[] THUMBNAIL = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
  private static final String SHA = "ab".repeat(32);

  @Mock private UserAccessor userAccessor;
  @Mock private BaseTaskRepository taskRepo;
  @Mock private TaskMembershipGuard guard;
  @Mock private TaskAttachmentQueryRepository attachmentRepo;
  @Mock private TaskUserStateQueryRepository userStateRepo;
  @Mock private TaskParticipantQueryRepository participantRepo;
  @Mock private AttachmentWriter writer;
  @Mock private TaskHistoryService history;
  @Mock private InboxGenerationService generation;
  @Mock private InboxSignalService signal;

  private Task task;
  private LocalDateTime before;
  private AttachmentCommandService service;

  @BeforeEach
  void setUp() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT));
    when(userAccessor.requireCurrentUser()).thenReturn(new AuthUser(ACTOR, "actor@brasi.de"));

    before = LocalDateTime.now().minusMinutes(10);
    task = new Task();
    task.setId(TASK_ID);
    task.setUuid(TASK_UUID);
    task.setTenantId(TENANT);
    task.setEntityState(EntityState.ACTIVE);
    task.setTitle("Testaufgabe");
    task.setLastActivityAt(before);

    when(taskRepo.findByUuid(TASK_UUID.toString())).thenReturn(Optional.of(task));
    when(taskRepo.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(attachmentRepo.countActiveTaskAttachments(TASK_ID)).thenReturn(0L);
    // Der Akteur hat selbst einen aktiven State: die Leser-Menge muss ihn nur einmal enthalten.
    when(userStateRepo.findActiveUserRefsByTaskId(TASK_ID))
        .thenReturn(List.of(state(READER_1), state(ACTOR)));
    when(participantRepo.findArchiveReadersByTaskId(TASK_ID))
        .thenReturn(List.of(participant(ARCHIVE_READER, false)));
    when(writer.store(eq(task), isNull(), eq(ACTOR), anyList()))
        .thenAnswer(inv -> stored(inv.getArgument(3)));

    when(attachmentRepo.save(any(TaskAttachment.class))).thenAnswer(inv -> inv.getArgument(0));

    service = newService();
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void i152_upload_reads_before_first_write() {
    final List<PreparedAttachment> prepared = List.of(prepared("a.pdf"));

    service.storeTaskAttachments(TASK_UUID, prepared);

    // Zwei Ketten, damit die Reihenfolge der beiden Leser-Lesungen untereinander frei bleibt.
    final InOrder o =
        inOrder(guard, attachmentRepo, userStateRepo, participantRepo, writer, taskRepo);
    o.verify(guard).requireWriteMember(TASK_ID, ACTOR, TENANT);
    o.verify(attachmentRepo).countActiveTaskAttachments(TASK_ID);
    o.verify(participantRepo).findArchiveReadersByTaskId(TASK_ID);
    o.verify(writer).store(task, null, ACTOR, prepared);
    o.verify(taskRepo).save(task);

    final InOrder states = inOrder(guard, attachmentRepo, userStateRepo, writer, taskRepo);
    states.verify(guard).requireWriteMember(TASK_ID, ACTOR, TENANT);
    states.verify(attachmentRepo).countActiveTaskAttachments(TASK_ID);
    states.verify(userStateRepo).findActiveUserRefsByTaskId(TASK_ID);
    states.verify(writer).store(task, null, ACTOR, prepared);
    states.verify(taskRepo).save(task);
  }

  @Test
  void i152_upload_writes_activity_history_and_bumps_readers_and_actor() {
    final List<PreparedAttachment> prepared = List.of(prepared("a.pdf"), prepared("b.txt"));

    final List<AttachmentModel> models = service.storeTaskAttachments(TASK_UUID, prepared);

    assertThat(task.getLastActivityAt()).isAfter(before);
    verify(taskRepo, times(1)).save(task);
    verify(history).record(TASK_ID, TENANT, ACTOR, "Datei angehaengt", "a.pdf");
    verify(history).record(TASK_ID, TENANT, ACTOR, "Datei angehaengt", "b.txt");
    assertThat(bumpedRefs()).containsExactlyInAnyOrder(READER_1, ARCHIVE_READER, ACTOR);
    assertThat(signalledRefs()).containsExactlyInAnyOrder(READER_1, ARCHIVE_READER, ACTOR);

    final InOrder o = inOrder(taskRepo, history, generation, signal);
    o.verify(taskRepo).save(task);
    o.verify(history, times(2)).record(any(), any(), any(), any(), any());
    o.verify(generation).bumpSorted(eq(TENANT), any());
    o.verify(signal).invalidate(eq(TENANT), any());

    assertThat(models).hasSize(2);
    assertThat(models).extracting(AttachmentModel::fileName).containsExactly("a.pdf", "b.txt");
    assertThat(models).allSatisfy(m -> assertThat(m.removable()).isTrue());
    assertThat(models).allSatisfy(m -> assertThat(m.uploadedById()).isEqualTo(ACTOR));
    assertThat(models.get(0).hasThumbnail()).isTrue();
    assertThat(models.get(1).hasThumbnail()).isFalse();
    assertThat(models.get(0).links().content())
        .isEqualTo(
            "/task-management/api/v1/tasks/"
                + TASK_UUID
                + "/attachments/"
                + models.get(0).id()
                + "/content");
    assertThat(models.get(1).links().thumbnail()).isNull();
  }

  @Test
  void i152_count_recheck_in_transaction() {
    // count 20 -> EntityValidationException, writer nie gerufen
    when(attachmentRepo.countActiveTaskAttachments(TASK_ID)).thenReturn(20L);

    assertThatThrownBy(() -> service.storeTaskAttachments(TASK_UUID, List.of(prepared("a.pdf"))))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("Höchstens 20 aktive Anhänge je Aufgabe bzw. je Kommentar.");
    verifyNoInteractions(writer);
    verify(taskRepo, never()).save(any(Task.class));
    verifyNoInteractions(history, generation, signal);
  }

  @Test
  void i152_count_at_limit_after_upload_is_accepted() {
    when(attachmentRepo.countActiveTaskAttachments(TASK_ID)).thenReturn(19L);

    assertThat(service.storeTaskAttachments(TASK_UUID, List.of(prepared("a.pdf")))).hasSize(1);
  }

  @Test
  void i152_version_conflict_propagates_without_bump() {
    when(taskRepo.save(task))
        .thenThrow(
            new BusinessRuleException(
                "Versionskonflikt", new OptimisticLockingFailureException("v")));

    assertThatThrownBy(() -> service.storeTaskAttachments(TASK_UUID, List.of(prepared("a.pdf"))))
        .isInstanceOf(BusinessRuleException.class);
    verify(history, never()).record(any(), any(), any(), any(), any());
    verifyNoInteractions(generation, signal);
  }

  @Test
  void i152_seam_runs_after_files_stored_and_before_task_save() {
    final AttachmentCommandService failing = seamFailingService();
    final List<PreparedAttachment> prepared = List.of(prepared("a.pdf"));

    assertThatThrownBy(() -> failing.storeTaskAttachments(TASK_UUID, prepared))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Seam");
    verify(writer).store(task, null, ACTOR, prepared);
    verify(taskRepo, never()).save(any(Task.class));
    verifyNoInteractions(history, generation, signal);
  }

  @Test
  void i152_upload_by_non_member_is_404_before_any_read_or_write() {
    doThrow(new EntityNotFoundException("Aufgabe nicht gefunden."))
        .when(guard)
        .requireWriteMember(TASK_ID, ACTOR, TENANT);

    assertThatThrownBy(() -> service.storeTaskAttachments(TASK_UUID, List.of(prepared("a.pdf"))))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
    verifyNoInteractions(attachmentRepo, userStateRepo, participantRepo, writer);
    verify(taskRepo, never()).save(any(Task.class));
  }

  @Test
  void i152_task_access_masks_unknown_foreign_and_deactivated_task() {
    // Unbekannte Aufgabe
    when(taskRepo.findByUuid(TASK_UUID.toString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.storeTaskAttachments(TASK_UUID, List.of(prepared("a.pdf"))))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");

    // Fremder Mandant
    task.setTenantId(TENANT + 1);
    when(taskRepo.findByUuid(TASK_UUID.toString())).thenReturn(Optional.of(task));
    assertThatThrownBy(() -> service.storeTaskAttachments(TASK_UUID, List.of(prepared("a.pdf"))))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");

    // Deaktivierte Aufgabe
    task.setTenantId(TENANT);
    task.setEntityState(EntityState.DEACTIVE);
    assertThatThrownBy(() -> service.storeTaskAttachments(TASK_UUID, List.of(prepared("a.pdf"))))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");

    verifyNoInteractions(guard, attachmentRepo, writer);
  }

  // ---- A9: Entfernen ---------------------------------------------------------------------------

  @Test
  void i152_remove_role_check_before_any_write() {
    // anderes Mitglied (weder Hochladende noch Ersteller) -> PermissionDeniedException
    final TaskAttachment attachment = existing(UPLOADER, null);
    when(attachmentRepo.findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID))
        .thenReturn(List.of(attachment));
    when(participantRepo.findByTaskIdAndParticipantRef(TASK_ID, ACTOR))
        .thenReturn(List.of(participant(ACTOR, false)));

    assertThatThrownBy(() -> service.remove(TASK_UUID, ATT_UUID))
        .isInstanceOf(PermissionDeniedException.class);
    verify(attachmentRepo, never()).save(any());
    verify(taskRepo, never()).save(any());
    verifyNoInteractions(history, generation, signal);
    assertThat(attachment.getEntityState()).isEqualTo(EntityState.ACTIVE);
  }

  @Test
  void i152_remove_reads_before_first_write() {
    // Ersteller entfernt den Anhang einer anderen Person
    final TaskAttachment attachment = existing(UPLOADER, null);
    when(attachmentRepo.findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID))
        .thenReturn(List.of(attachment));
    when(participantRepo.findByTaskIdAndParticipantRef(TASK_ID, ACTOR))
        .thenReturn(List.of(participant(ACTOR, true)));

    service.remove(TASK_UUID, ATT_UUID);

    final InOrder o = inOrder(guard, attachmentRepo, participantRepo, taskRepo);
    o.verify(guard).requireWriteMember(TASK_ID, ACTOR, TENANT);
    o.verify(attachmentRepo).findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID);
    o.verify(participantRepo).findByTaskIdAndParticipantRef(TASK_ID, ACTOR);
    o.verify(participantRepo).findArchiveReadersByTaskId(TASK_ID);
    o.verify(attachmentRepo).save(attachment);
    o.verify(taskRepo).save(task);

    final InOrder states = inOrder(guard, userStateRepo, attachmentRepo, taskRepo);
    states.verify(guard).requireWriteMember(TASK_ID, ACTOR, TENANT);
    states.verify(userStateRepo).findActiveUserRefsByTaskId(TASK_ID);
    states.verify(attachmentRepo).save(attachment);
    states.verify(taskRepo).save(task);

    final InOrder after = inOrder(taskRepo, history, generation, signal);
    after.verify(taskRepo).save(task);
    after.verify(history).record(TASK_ID, TENANT, ACTOR, "Datei entfernt", "a.pdf");
    after.verify(generation).bumpSorted(eq(TENANT), any());
    after.verify(signal).invalidate(eq(TENANT), any());

    assertThat(attachment.getEntityState()).isEqualTo(EntityState.DEACTIVE);
    assertThat(task.getLastActivityAt()).isAfter(before);
    assertThat(bumpedRefs()).containsExactlyInAnyOrder(READER_1, ARCHIVE_READER, ACTOR);
    assertThat(signalledRefs()).containsExactlyInAnyOrder(READER_1, ARCHIVE_READER, ACTOR);
  }

  @Test
  void i152_remove_by_uploader_without_creator_role() {
    final TaskAttachment attachment = existing(ACTOR, null);
    when(attachmentRepo.findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID))
        .thenReturn(List.of(attachment));
    when(participantRepo.findByTaskIdAndParticipantRef(TASK_ID, ACTOR))
        .thenReturn(List.of(participant(ACTOR, false)));

    service.remove(TASK_UUID, ATT_UUID);

    assertThat(attachment.getEntityState()).isEqualTo(EntityState.DEACTIVE);
    verify(attachmentRepo).save(attachment);
    verify(taskRepo).save(task);
    verify(history).record(TASK_ID, TENANT, ACTOR, "Datei entfernt", "a.pdf");
  }

  @Test
  void i152_remove_comment_or_unknown_attachment_is_404_without_write() {
    // Kommentar-Anhaenge sind unveraenderlich: ueber die Aufgabe nicht entfernbar
    when(attachmentRepo.findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID))
        .thenReturn(List.of(existing(ACTOR, 77L)));
    assertThatThrownBy(() -> service.remove(TASK_UUID, ATT_UUID))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Anhang nicht gefunden.");

    when(attachmentRepo.findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID)).thenReturn(List.of());
    assertThatThrownBy(() -> service.remove(TASK_UUID, ATT_UUID))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Anhang nicht gefunden.");

    verify(attachmentRepo, never()).save(any());
    verify(taskRepo, never()).save(any());
    verifyNoInteractions(history, generation, signal);
  }

  @Test
  void i152_remove_by_non_member_is_404_before_attachment_lookup() {
    doThrow(new EntityNotFoundException("Aufgabe nicht gefunden."))
        .when(guard)
        .requireWriteMember(TASK_ID, ACTOR, TENANT);

    assertThatThrownBy(() -> service.remove(TASK_UUID, ATT_UUID))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
    verifyNoInteractions(attachmentRepo, participantRepo, userStateRepo);
    verify(taskRepo, never()).save(any());
  }

  @Test
  void i152_remove_version_conflict_propagates_without_bump() {
    when(attachmentRepo.findActiveByTaskIdAndUuid(TASK_ID, ATT_UUID))
        .thenReturn(List.of(existing(ACTOR, null)));
    when(taskRepo.save(task))
        .thenThrow(
            new BusinessRuleException(
                "Versionskonflikt", new OptimisticLockingFailureException("v")));

    assertThatThrownBy(() -> service.remove(TASK_UUID, ATT_UUID))
        .isInstanceOf(BusinessRuleException.class);
    verify(history, never()).record(any(), any(), any(), any(), any());
    verifyNoInteractions(generation, signal);
  }

  // ---- Hilfen ------------------------------------------------------------------------------

  private AttachmentCommandService newService() {
    return new AttachmentCommandService(
        userAccessor,
        new AttachmentTaskAccess(taskRepo, guard),
        attachmentRepo,
        userStateRepo,
        participantRepo,
        writer,
        taskRepo,
        history,
        generation,
        signal,
        new AttachmentModelMapper(new AuditTimeConverter("Europe/Berlin"), "/task-management"),
        properties());
  }

  /** Test-Unterklasse: der Seam nach dem Schreiben der Dateien bricht ab. */
  private AttachmentCommandService seamFailingService() {
    return new AttachmentCommandService(
        userAccessor,
        new AttachmentTaskAccess(taskRepo, guard),
        attachmentRepo,
        userStateRepo,
        participantRepo,
        writer,
        taskRepo,
        history,
        generation,
        signal,
        new AttachmentModelMapper(new AuditTimeConverter("Europe/Berlin"), "/task-management"),
        properties()) {
      @Override
      protected void onAfterAttachmentsStoredForTest(final Long taskId) {
        throw new IllegalStateException("Seam");
      }
    };
  }

  @SuppressWarnings("unchecked")
  private Collection<UUID> bumpedRefs() {
    final ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(generation, times(1)).bumpSorted(eq(TENANT), captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private Collection<UUID> signalledRefs() {
    final ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(signal, times(1)).invalidate(eq(TENANT), captor.capture());
    return captor.getValue();
  }

  private static PreparedAttachment prepared(final String name) {
    final String extension = name.substring(name.lastIndexOf('.') + 1);
    final AttachmentFileType type = AttachmentFileType.fromExtension(extension).orElseThrow();
    final byte[] content = type == AttachmentFileType.PDF ? PDF_BYTES : TXT_BYTES;
    return new PreparedAttachment(
        name,
        type,
        extension,
        content.length,
        SHA,
        new ByteArrayResource(content),
        type.previewable() ? THUMBNAIL : null);
  }

  /** Was der Writer nach Insert, Dateischreiben und Update mit Schluessel zurueckgibt. */
  private static List<TaskAttachment> stored(final List<PreparedAttachment> prepared) {
    final List<TaskAttachment> result = new ArrayList<>();
    long id = 10_001L;
    for (final PreparedAttachment p : prepared) {
      final TaskAttachment a = new TaskAttachment();
      a.setId(id);
      a.setUuid(UUID.randomUUID());
      a.setTenantId(TENANT);
      a.setEntityState(EntityState.ACTIVE);
      a.setCreatedAt(LocalDateTime.now());
      a.setTaskId(TASK_ID);
      a.setFileName(p.fileName());
      a.setMimeType(p.type().mimeType());
      a.setFileExtension(p.extension());
      a.setSizeBytes(p.sizeBytes());
      a.setSha256(p.sha256Hex());
      a.setStorageKey(AttachmentStorageKeys.contentKey(TENANT, id, p.extension()));
      a.setThumbnailPresent(p.hasThumbnail());
      a.setUploadedByRef(ACTOR);
      result.add(a);
      id++;
    }
    return result;
  }

  /** Ein gespeicherter, aktiver Anhang {@code a.pdf} der Aufgabe (oder eines Kommentars). */
  private static TaskAttachment existing(final UUID uploadedBy, final Long commentId) {
    final TaskAttachment a = new TaskAttachment();
    a.setId(10_095L);
    a.setUuid(ATT_UUID);
    a.setTenantId(TENANT);
    a.setEntityState(EntityState.ACTIVE);
    a.setCreatedAt(LocalDateTime.now().minusDays(1));
    a.setTaskId(TASK_ID);
    a.setCommentId(commentId);
    a.setFileName("a.pdf");
    a.setMimeType(AttachmentFileType.PDF.mimeType());
    a.setFileExtension("pdf");
    a.setSizeBytes(PDF_BYTES.length);
    a.setSha256(SHA);
    a.setStorageKey(AttachmentStorageKeys.contentKey(TENANT, 10_095L, "pdf"));
    a.setThumbnailPresent(true);
    a.setUploadedByRef(uploadedBy);
    return a;
  }

  private static TaskUserState state(final UUID userRef) {
    final TaskUserState state = new TaskUserState();
    state.setTenantId(TENANT);
    state.setTaskId(TASK_ID);
    state.setUserRef(userRef);
    return state;
  }

  private static TaskParticipant participant(final UUID ref, final boolean creator) {
    final TaskParticipant participant = new TaskParticipant();
    participant.setTenantId(TENANT);
    participant.setTaskId(TASK_ID);
    participant.setParticipantRef(ref);
    participant.setCreatorFlag(creator);
    return participant;
  }

  /** Properties mit den Vorgaben aus application.properties (hoechstens 20 je Ziel). */
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
