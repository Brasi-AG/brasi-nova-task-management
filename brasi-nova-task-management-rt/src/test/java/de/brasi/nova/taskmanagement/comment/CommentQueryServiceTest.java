package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskCommentQueryRepository;
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
  private CommentQueryService service;

  @BeforeEach
  void setUp() {
    taskRepository = mock(BaseTaskRepository.class);
    commentQueryRepository = mock(TaskCommentQueryRepository.class);
    userAccessor = mock(UserAccessor.class);
    auditTimeConverter = new AuditTimeConverter("Europe/Berlin");
    membershipGuard = mock(TaskMembershipGuard.class);
    service =
        new CommentQueryService(
            taskRepository,
            commentQueryRepository,
            userAccessor,
            auditTimeConverter,
            membershipGuard);

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
            List.of(
                comment(1L, C1_UUID, null, "top", T1),
                comment(2L, C2_UUID, 1L, "reply", T2)));

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
    // membershipGuard.requireReadMember wirft nicht (Default-Mock) -> READ-Member (inkl. Ex-Member).
    when(commentQueryRepository.findActiveByTaskIdOrdered(anyLong()))
        .thenReturn(List.of(comment(1L, C1_UUID, null, "top", T1)));

    final List<CommentModel> result = service.getComments(TASK_UUID);

    assertThat(result).extracting(CommentModel::id).containsExactly(C1_UUID);
  }

  // ---- Hilfen --------------------------------------------------------------------------------

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
