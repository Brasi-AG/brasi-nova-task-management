package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.attachment.AttachmentContentProvider;
import de.brasi.nova.taskmanagement.attachment.AttachmentModelMapper;
import de.brasi.nova.taskmanagement.attachment.AttachmentProperties;
import de.brasi.nova.taskmanagement.attachment.AttachmentQueryService;
import de.brasi.nova.taskmanagement.attachment.AttachmentTaskAccess;
import de.brasi.nova.taskmanagement.attachment.AttachmentThumbnailGenerator;
import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskCommentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link CommentQueryService} (alle Abhaengigkeiten gemockt, kein DB). Deckt die
 * parentCommentId-Aufloesung (intern -> UUID), Reihenfolge-Erhalt, leere Liste und die drei
 * getrennten 404-Faelle (fehlend / fremder Mandant / DEACTIVE) ab (BE-2-SPEC P2).
 *
 * <p>Intake #152 Teil B1: die Kommentar-Anhaenge kommen ueber einen echten {@link
 * AttachmentQueryService} (gemocktes {@link TaskAttachmentQueryRepository}, echter {@link
 * AttachmentModelMapper}); so ist belegt, dass je Liste genau eine Abfrage laeuft und die Anhaenge
 * je Kommentar gruppiert werden.
 */
class CommentQueryServiceTest {

  private static final long TENANT_ID = 590L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000590");
  private static final UUID TASK_UUID = UUID.fromString("00000059-0601-0059-0000-000000590601");
  private static final UUID AUTHOR = UUID.fromString("00000059-000a-0059-0000-00000000059a");
  private static final UUID C1_UUID = UUID.fromString("00000059-0c01-0059-0000-000000000c01");
  private static final UUID C2_UUID = UUID.fromString("00000059-0c02-0059-0000-000000000c02");
  private static final LocalDateTime T1 = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
  private static final LocalDateTime T2 = LocalDateTime.of(2026, 1, 1, 9, 0, 0);

  private BaseTaskRepository taskRepository;
  private TaskCommentQueryRepository commentQueryRepository;
  private UserAccessor userAccessor;
  private AuditTimeConverter auditTimeConverter;
  private TaskMembershipGuard membershipGuard;
  private TaskUserStateQueryRepository userStateQueryRepository;
  private TaskAttachmentQueryRepository attachmentRepository;
  private CommentQueryService service;

  @BeforeEach
  void setUp() {
    taskRepository = mock(BaseTaskRepository.class);
    commentQueryRepository = mock(TaskCommentQueryRepository.class);
    userAccessor = mock(UserAccessor.class);
    auditTimeConverter = new AuditTimeConverter("Europe/Berlin");
    membershipGuard = mock(TaskMembershipGuard.class);
    userStateQueryRepository = mock(TaskUserStateQueryRepository.class);
    attachmentRepository = mock(TaskAttachmentQueryRepository.class);
    // Intake #152 B1: echter AttachmentQueryService; fuer die Kommentarliste braucht er nur das
    // Repository und den Mapper, die uebrigen Abhaengigkeiten bleiben unbenutzte Mocks.
    final AttachmentQueryService attachmentQueryService =
        new AttachmentQueryService(
            userAccessor,
            new AttachmentTaskAccess(taskRepository, membershipGuard),
            attachmentRepository,
            mock(TaskParticipantQueryRepository.class),
            mock(AttachmentContentProvider.class),
            mock(AttachmentThumbnailGenerator.class),
            new AttachmentModelMapper(auditTimeConverter, "/task-management"),
            new AttachmentProperties(
                new AttachmentProperties.Storage(null), List.of(), 20, null, null, null));
    service =
        new CommentQueryService(
            taskRepository,
            commentQueryRepository,
            userAccessor,
            auditTimeConverter,
            membershipGuard,
            userStateQueryRepository,
            attachmentQueryService);

    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    when(userAccessor.requireCurrentUser()).thenReturn(new AuthUser(AUTHOR, "it-user"));
  }

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
  }

  @Test
  @DisplayName("mappt flach + chronologisch, loest parentCommentId intern->UUID auf")
  void mappt_und_loest_parent_auf() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    when(commentQueryRepository.findActiveByTaskIdOrdered(anyLong()))
        .thenReturn(
            List.of(comment(1L, C1_UUID, null, "top", T1), comment(2L, C2_UUID, 1L, "reply", T2)));

    final List<CommentModel> result = service.getComments(TASK_UUID);

    assertThat(result).extracting(CommentModel::id).containsExactly(C1_UUID, C2_UUID);
    assertThat(result.get(0).parentCommentId()).isNull();
    assertThat(result.get(1).parentCommentId()).as("Reply-Eltern intern->UUID").isEqualTo(C1_UUID);
    assertThat(result.get(0).taskId()).isEqualTo(TASK_UUID);
    assertThat(result.get(0).authorRef()).isEqualTo(AUTHOR);
    assertThat(result.get(0).text()).isEqualTo("top");
    assertThat(result.get(0).createdAt()).isNotNull();
    assertThat(result.get(0).edited()).isFalse();
  }

  // ---- Intake #150: isNew -------------------------------------------------------------------

  private static final UUID OTHER = UUID.fromString("00000059-000b-0059-0000-00000000059b");
  private static final UUID C3_UUID = UUID.fromString("00000059-0c03-0059-0000-000000000c03");

  @Test
  @DisplayName(
      "#150 isNew: fremd und nach SEEN_AT -> true; fremd vor/gleich SEEN_AT -> false; eigen -> false")
  void is_new_nach_seen_at_und_nicht_eigen() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    final TaskUserState state = new TaskUserState();
    state.setSeenAt(T1);
    when(userStateQueryRepository.findByTaskIdAndUserRef(anyLong(), any()))
        .thenReturn(List.of(state));
    final TaskComment before = comment(1L, C1_UUID, null, "vorher", T1);
    before.setAuthorRef(OTHER);
    final TaskComment after = comment(2L, C2_UUID, null, "danach", T2);
    after.setAuthorRef(OTHER);
    final TaskComment own = comment(3L, C3_UUID, null, "eigen", T2);
    when(commentQueryRepository.findActiveByTaskIdOrdered(anyLong()))
        .thenReturn(List.of(before, after, own));

    final List<CommentModel> result = service.getComments(TASK_UUID);

    assertThat(result).extracting(CommentModel::isNew).containsExactly(false, true, false);
    // Lesen veraendert den Gesehen-Zeitpunkt nie.
    org.mockito.Mockito.verify(userStateQueryRepository, org.mockito.Mockito.never()).save(any());
    assertThat(state.getSeenAt()).isEqualTo(T1);
  }

  @Test
  @DisplayName(
      "#150 isNew ohne Marker (erster Besuch, kein State oder SEEN_AT null): alle fremden neu,"
          + " eigene nie")
  void is_new_ohne_marker_alle_fremden_neu() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    final TaskComment other = comment(1L, C1_UUID, null, "fremd", T1);
    other.setAuthorRef(OTHER);
    final TaskComment own = comment(2L, C2_UUID, null, "eigen", T2);
    when(commentQueryRepository.findActiveByTaskIdOrdered(anyLong()))
        .thenReturn(List.of(other, own));

    when(userStateQueryRepository.findByTaskIdAndUserRef(anyLong(), any())).thenReturn(List.of());
    assertThat(service.getComments(TASK_UUID))
        .extracting(CommentModel::isNew)
        .containsExactly(true, false);

    when(userStateQueryRepository.findByTaskIdAndUserRef(anyLong(), any()))
        .thenReturn(List.of(new TaskUserState()));
    assertThat(service.getComments(TASK_UUID))
        .extracting(CommentModel::isNew)
        .containsExactly(true, false);
  }

  @Test
  @DisplayName("Eltern-Kommentar nicht im aktiven Set -> parentCommentId=null")
  void parent_nicht_im_set_null() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    when(commentQueryRepository.findActiveByTaskIdOrdered(anyLong()))
        .thenReturn(List.of(comment(2L, C2_UUID, 99L, "orphan-reply", T1)));

    final List<CommentModel> result = service.getComments(TASK_UUID);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).parentCommentId())
        .as("Eltern 99 nicht im aktiven Set -> null")
        .isNull();
  }

  @Test
  @DisplayName("keine Kommentare -> leere Liste")
  void leere_liste() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    when(commentQueryRepository.findActiveByTaskIdOrdered(anyLong())).thenReturn(List.of());

    assertThat(service.getComments(TASK_UUID)).isEmpty();
  }

  @Test
  @DisplayName("Aufgabe nicht gefunden -> 404")
  void task_fehlt_404() {
    when(taskRepository.findByUuid(TASK_UUID.toString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getComments(TASK_UUID))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  @DisplayName("fremder Mandant -> 404")
  void fremder_mandant_404() {
    taskFound(EntityState.ACTIVE, 999L);
    assertThatThrownBy(() -> service.getComments(TASK_UUID))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  @DisplayName("DEACTIVE Aufgabe -> 404")
  void deactive_task_404() {
    taskFound(EntityState.DEACTIVE, TENANT_ID);
    assertThatThrownBy(() -> service.getComments(TASK_UUID))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // ---- Slice 2/Auflage 3: READ-Member-Guard ---------------------------------------------------

  @Test
  @DisplayName("non_member_404: Guard wirft 404 fuer Non-Member -> kein Kommentar-Read")
  void non_member_404() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    org.mockito.Mockito.doThrow(new EntityNotFoundException("Aufgabe nicht gefunden."))
        .when(membershipGuard)
        .requireReadMember(590601L, AUTHOR, TENANT_ID);

    assertThatThrownBy(() -> service.getComments(TASK_UUID))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");

    // Kein Kommentar-Read bei fehlgeschlagenem Guard.
    org.mockito.Mockito.verify(commentQueryRepository, org.mockito.Mockito.never())
        .findActiveByTaskIdOrdered(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  @DisplayName("read_member_200: Guard passt (auch Ex-Member/Archiv) -> Kommentare werden gelesen")
  void read_member_200() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    // membershipGuard.requireReadMember wirft nicht (Default-Mock) -> READ-Member (inkl.
    // Ex-Member).
    when(commentQueryRepository.findActiveByTaskIdOrdered(anyLong()))
        .thenReturn(List.of(comment(1L, C1_UUID, null, "top", T1)));

    final List<CommentModel> result = service.getComments(TASK_UUID);

    assertThat(result).extracting(CommentModel::id).containsExactly(C1_UUID);
  }

  // ---- Intake #152 B1: Anhaenge in der Kommentarliste -----------------------------------------

  private static final long TASK_DB_ID = 590601L;

  @Test
  @DisplayName(
      "#152 Kommentar-Anhaenge: eine Abfrage je Liste, je Kommentar gruppiert (C1: 2, C2: 1, C3:"
          + " 0), nicht entfernbar, Links auf dem Aufgaben-Pfad")
  void i152_comment_attachments_grouped_in_one_query() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    when(commentQueryRepository.findActiveByTaskIdOrdered(anyLong()))
        .thenReturn(
            List.of(
                comment(1L, C1_UUID, null, "eins", T1),
                comment(2L, C2_UUID, null, "zwei", T1),
                comment(3L, C3_UUID, null, "drei", T2)));
    final UUID a1 = UUID.fromString("00000059-0a01-0059-0000-000000000a01");
    final UUID a2 = UUID.fromString("00000059-0a02-0059-0000-000000000a02");
    final UUID a3 = UUID.fromString("00000059-0a03-0059-0000-000000000a03");
    when(attachmentRepository.findActiveCommentAttachmentsByTaskId(TASK_DB_ID))
        .thenReturn(
            List.of(
                attachment(11L, a1, 1L, "erster.pdf", "pdf"),
                attachment(12L, a2, 1L, "zweiter.txt", "txt"),
                attachment(13L, a3, 2L, "dritter.pdf", "pdf")));

    final List<CommentModel> result = service.getComments(TASK_UUID);

    assertThat(result).extracting(CommentModel::id).containsExactly(C1_UUID, C2_UUID, C3_UUID);
    assertThat(result).extracting(m -> m.attachments().size()).containsExactly(2, 1, 0);
    assertThat(result.get(0).attachments())
        .as("Reihenfolge der Abfrage bleibt je Kommentar erhalten")
        .extracting(AttachmentModel::id)
        .containsExactly(a1, a2);
    assertThat(result.get(0).attachments())
        .extracting(AttachmentModel::fileName)
        .containsExactly("erster.pdf", "zweiter.txt");
    assertThat(result.get(1).attachments())
        .extracting(AttachmentModel::fileName)
        .containsExactly("dritter.pdf");
    assertThat(result.get(2).attachments()).as("ohne Anhang: leere Liste, nie null").isEmpty();
    for (final CommentModel model : result) {
      for (final AttachmentModel a : model.attachments()) {
        assertThat(a.removable()).as("Kommentar-Anhaenge sind nie entfernbar").isFalse();
        final String expected =
            "/task-management/api/v1/tasks/" + TASK_UUID + "/attachments/" + a.id() + "/content";
        assertThat(a.links().content()).isEqualTo(expected);
      }
    }

    // Genau eine Abfrage fuer die ganze Liste (kein N+1, keine Abfrage je Kommentar).
    verify(attachmentRepository, times(1)).findActiveCommentAttachmentsByTaskId(TASK_DB_ID);
    verify(attachmentRepository, never()).findActiveTaskAttachments(anyLong());
  }

  @Test
  @DisplayName(
      "#152 Kommentar-Anhaenge: Anhang eines nicht (mehr) aktiven Kommentars erscheint nirgends")
  void i152_attachment_of_inactive_comment_is_ignored() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    when(commentQueryRepository.findActiveByTaskIdOrdered(anyLong()))
        .thenReturn(List.of(comment(1L, C1_UUID, null, "eins", T1)));
    when(attachmentRepository.findActiveCommentAttachmentsByTaskId(TASK_DB_ID))
        .thenReturn(
            List.of(
                attachment(
                    21L,
                    UUID.fromString("00000059-0a21-0059-0000-000000000a21"),
                    99L,
                    "verwaist.pdf",
                    "pdf")));

    final List<CommentModel> result = service.getComments(TASK_UUID);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).attachments()).isEmpty();
  }

  @Test
  @DisplayName(
      "#152 Kommentar-Anhaenge: READ-Guard scheitert -> keine Anhang-Abfrage (Lesen erst nach dem"
          + " Guard)")
  void i152_comment_attachments_read_only_after_read_guard() {
    taskFound(EntityState.ACTIVE, TENANT_ID);
    org.mockito.Mockito.doThrow(new EntityNotFoundException("Aufgabe nicht gefunden."))
        .when(membershipGuard)
        .requireReadMember(TASK_DB_ID, AUTHOR, TENANT_ID);

    assertThatThrownBy(() -> service.getComments(TASK_UUID))
        .isInstanceOf(EntityNotFoundException.class);

    verify(attachmentRepository, never()).findActiveCommentAttachmentsByTaskId(anyLong());
  }

  // ---- Hilfen --------------------------------------------------------------------------------

  /** Aktiver Anhang am Kommentar mit der internen ID {@code commentId}. */
  private static TaskAttachment attachment(
      final long id,
      final UUID uuid,
      final long commentId,
      final String fileName,
      final String extension) {
    final TaskAttachment a = new TaskAttachment();
    a.setId(id);
    a.setUuid(uuid);
    a.setTenantId(TENANT_ID);
    a.setEntityState(EntityState.ACTIVE);
    a.setCreatedAt(T1);
    a.setTaskId(TASK_DB_ID);
    a.setCommentId(commentId);
    a.setFileName(fileName);
    a.setMimeType("pdf".equals(extension) ? "application/pdf" : "text/plain");
    a.setFileExtension(extension);
    a.setSizeBytes(1234L);
    a.setSha256("0123456789abcdef".repeat(4));
    a.setStorageKey(TENANT_ID + "/0/" + id + "." + extension);
    a.setThumbnailPresent(false);
    a.setUploadedByRef(AUTHOR);
    return a;
  }

  private void taskFound(final EntityState state, final long tenantId) {
    final Task task = new Task();
    task.setId(590601L);
    task.setUuid(TASK_UUID);
    task.setTenantId(tenantId);
    task.setEntityState(state);
    when(taskRepository.findByUuid(TASK_UUID.toString())).thenReturn(Optional.of(task));
  }

  private static TaskComment comment(
      final long id,
      final UUID uuid,
      final Long parentCommentId,
      final String text,
      final LocalDateTime createdAt) {
    final TaskComment c = new TaskComment();
    c.setId(id);
    c.setUuid(uuid);
    c.setTaskId(590601L);
    c.setAuthorRef(AUTHOR);
    c.setParentCommentId(parentCommentId);
    c.setText(text);
    c.setEdited(false);
    c.setCreatedAt(createdAt);
    return c;
  }
}
