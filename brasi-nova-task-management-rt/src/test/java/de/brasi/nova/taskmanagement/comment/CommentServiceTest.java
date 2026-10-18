package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.repository.BaseTaskCommentRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit-Tests fuer {@link CommentService} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale: beweist die Kern-Invarianten fuer Kommentar-Erstellung:
 * <ol>
 *   <li>COMMENT_COUNT++ + LAST_ACTIVITY_AT + save(task) + 1 Kommentar gespeichert + invalidate
 *   <li>Mention-Benachrichtigungen werden korrekt emittiert
 *   <li>REPLY-Benachrichtigung an Eltern-Autor
 *   <li>Deduplizierung: Eltern-Autor erhaelt nur REPLY, nicht zusaetzlich MENTION
 *   <li>Kein Selbst-Notify
 *   <li>404 bei unbekannter Aufgabe
 *   <li>400 bei leerem Text / ungueltigem Eltern-Kommentar
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID TENANT_UUID = UUID.randomUUID();
  private static final UUID ACTOR_REF = UUID.randomUUID();
  private static final UUID ALICE_UUID = UUID.randomUUID();
  private static final UUID BOB_UUID = UUID.randomUUID();

  @Mock private BaseTaskRepository taskRepository;
  @Mock private BaseTaskCommentRepository commentRepository;
  @Mock private TaskUserStateQueryRepository userStateQueryRepository;
  @Mock private NotificationService notificationService;
  @Mock private InboxSignalService inboxSignalService;
  @Mock private MentionResolver mentionResolver;
  @Mock private UserAccessor userAccessor;

  private CommentService service;

  @BeforeEach
  void setUp() {
    service = new CommentService(
        taskRepository,
        commentRepository,
        userStateQueryRepository,
        notificationService,
        inboxSignalService,
        mentionResolver,
        userAccessor,
        new AuditTimeConverter("Europe/Berlin"));

    final TenantInfo tenant = new TenantInfo(TENANT_UUID, TENANT_ID);
    final AuthUser user = new AuthUser(ACTOR_REF, "actor@brasi.de");
    when(userAccessor.requireCurrentUser()).thenReturn(user);
    TenantContextHolder.setTenant(tenant);

    when(notificationService.emit(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(UUID.randomUUID());
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  // ---- Test 1: Kommentar zaehlt hoch + setzt Aktivitaet ------------------------------------------

  @Test
  @DisplayName(
      "kommentar_zaehlt_hoch_und_setzt_aktivitaet: commentCount+1 + lastActivityAt"
          + " + save(task) + 1 Kommentar + invalidate")
  void kommentar_zaehlt_hoch_und_setzt_aktivitaet() {
    final Long taskId = 42L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 3);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.save(any(TaskComment.class))).thenAnswer(inv -> {
      final TaskComment c = inv.getArgument(0);
      c.setUuid(UUID.randomUUID());
      c.setCreatedAt(LocalDateTime.now());
      return c;
    });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

    // Aktive Teilnehmer
    final TaskUserState state1 = stateWithUserRef(ALICE_UUID, taskId);
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId))
        .thenReturn(List.of(state1));

    // Keine Mentions
    when(mentionResolver.resolve(any())).thenReturn(List.of());

    final CommentCreateInput input = new CommentCreateInput("Einfacher Kommentar.", null);
    final CommentModel model = service.createComment(taskUuid, input);

    // Kommentar gespeichert
    final ArgumentCaptor<TaskComment> commentCaptor = ArgumentCaptor.forClass(TaskComment.class);
    verify(commentRepository).save(commentCaptor.capture());
    final TaskComment savedComment = commentCaptor.getValue();
    assertThat(savedComment.getTaskId()).isEqualTo(taskId);
    assertThat(savedComment.getAuthorRef()).isEqualTo(ACTOR_REF);
    assertThat(savedComment.getText()).isEqualTo("Einfacher Kommentar.");
    assertThat(savedComment.isEdited()).isFalse();
    assertThat(savedComment.getParentCommentId()).isNull();

    // Task-Zaehler und Aktivitaet aktualisiert
    final ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
    verify(taskRepository).save(taskCaptor.capture());
    final Task savedTask = taskCaptor.getValue();
    assertThat(savedTask.getCommentCount()).isEqualTo(4); // 3 + 1
    assertThat(savedTask.getLastActivityAt()).isNotNull();

    // Keine REPLY-Notification (kein Eltern-Kommentar)
    verify(notificationService, never()).emit(
        any(), any(), eq(NotificationTypeCodes.REPLY), any(), any(), any(), any());

    // Keine MENTION-Notification (keine Mentions)
    verify(notificationService, never()).emit(
        any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());

    // Invalidate aufgerufen
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());
    assertThat(refsCaptor.getValue()).contains(ALICE_UUID);

    // Antwort-Modell korrekt
    assertThat(model).isNotNull();
    assertThat(model.taskId()).isEqualTo(taskUuid);
    assertThat(model.authorRef()).isEqualTo(ACTOR_REF);
    assertThat(model.parentCommentId()).isNull();
    assertThat(model.edited()).isFalse();
  }

  // ---- Test 2: Mention-Benachrichtigung ----------------------------------------------------------

  @Test
  @DisplayName(
      "mention_emittiert_mention_notification: @alice @bob @ghost -> 2 MENTION-Emits (alice+bob)")
  void mention_emittiert_mention_notification() {
    final Long taskId = 43L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.save(any(TaskComment.class))).thenAnswer(inv -> {
      final TaskComment c = inv.getArgument(0);
      c.setUuid(UUID.randomUUID());
      c.setCreatedAt(LocalDateTime.now());
      return c;
    });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    // alice und bob aufloesbar, ghost nicht
    when(mentionResolver.resolve("Hallo @alice @bob und @ghost."))
        .thenReturn(List.of(ALICE_UUID, BOB_UUID));

    service.createComment(taskUuid, new CommentCreateInput("Hallo @alice @bob und @ghost.", null));

    // Genau 2 MENTION-Notifications
    verify(notificationService, times(1)).emit(
        eq(ALICE_UUID),
        eq(taskId),
        eq(NotificationTypeCodes.MENTION),
        eq(NotificationSeverityCodes.INFO),
        any(),
        any(),
        eq(ACTOR_REF));
    verify(notificationService, times(1)).emit(
        eq(BOB_UUID),
        eq(taskId),
        eq(NotificationTypeCodes.MENTION),
        eq(NotificationSeverityCodes.INFO),
        any(),
        any(),
        eq(ACTOR_REF));

    // Keine REPLY-Notification
    verify(notificationService, never()).emit(
        any(), any(), eq(NotificationTypeCodes.REPLY), any(), any(), any(), any());
  }

  // ---- Test 3: REPLY an Eltern-Autor -------------------------------------------------------------

  @Test
  @DisplayName("reply_emittiert_reply_an_parent_autor: Eltern-Autor != Akteur -> 1 REPLY")
  void reply_emittiert_reply_an_parent_autor() {
    final Long taskId = 44L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 1);

    final UUID parentUuid = UUID.randomUUID();
    final UUID parentAuthorRef = ALICE_UUID;
    final TaskComment parent = commentWithId(10L, taskId, parentAuthorRef, parentUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(parent));
    when(commentRepository.save(any(TaskComment.class))).thenAnswer(inv -> {
      final TaskComment c = inv.getArgument(0);
      c.setUuid(UUID.randomUUID());
      c.setCreatedAt(LocalDateTime.now());
      return c;
    });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(mentionResolver.resolve(any())).thenReturn(List.of());

    service.createComment(
        taskUuid, new CommentCreateInput("Antwort auf deinen Kommentar.", parentUuid));

    // 1 REPLY-Notification an Eltern-Autor
    verify(notificationService, times(1)).emit(
        eq(parentAuthorRef),
        eq(taskId),
        eq(NotificationTypeCodes.REPLY),
        eq(NotificationSeverityCodes.INFO),
        any(),
        any(),
        eq(ACTOR_REF));

    // Keine MENTION
    verify(notificationService, never()).emit(
        any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
  }

  // ---- Test 4: Deduplizierung - REPLY-Empfaenger bekommt kein zusaetzliches MENTION -----------

  @Test
  @DisplayName(
      "reply_dedupe_kein_mention_wenn_schon_reply:"
          + " Eltern-Autor auch @mentioned -> nur REPLY, kein MENTION")
  void reply_dedupe_kein_mention_wenn_schon_reply() {
    final Long taskId = 45L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 2);

    final UUID parentUuid = UUID.randomUUID();
    final UUID parentAuthorRef = ALICE_UUID;
    final TaskComment parent = commentWithId(11L, taskId, parentAuthorRef, parentUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(parent));
    when(commentRepository.save(any(TaskComment.class))).thenAnswer(inv -> {
      final TaskComment c = inv.getArgument(0);
      c.setUuid(UUID.randomUUID());
      c.setCreatedAt(LocalDateTime.now());
      return c;
    });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    // alice ist im Mention-Resolver-Ergebnis (sie ist auch Eltern-Autor)
    when(mentionResolver.resolve(any())).thenReturn(List.of(ALICE_UUID));

    service.createComment(
        taskUuid, new CommentCreateInput("@alice danke!", parentUuid));

    // alice bekommt nur REPLY, nicht MENTION
    verify(notificationService, times(1)).emit(
        eq(ALICE_UUID),
        eq(taskId),
        eq(NotificationTypeCodes.REPLY),
        any(), any(), any(), any());
    verify(notificationService, never()).emit(
        eq(ALICE_UUID),
        any(),
        eq(NotificationTypeCodes.MENTION),
        any(), any(), any(), any());
  }

  // ---- Test 5: kein Selbst-Notify ----------------------------------------------------------------

  @Test
  @DisplayName(
      "kein_selbst_notify: Akteur erwaehnt sich selbst und antwortet auf eigenen Kommentar"
          + " -> kein emit an Akteur")
  void kein_selbst_notify() {
    final Long taskId = 46L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    // Eltern-Kommentar: Autor ist der AKTEUR selbst
    final UUID parentUuid = UUID.randomUUID();
    final TaskComment parent = commentWithId(12L, taskId, ACTOR_REF, parentUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(parent));
    when(commentRepository.save(any(TaskComment.class))).thenAnswer(inv -> {
      final TaskComment c = inv.getArgument(0);
      c.setUuid(UUID.randomUUID());
      c.setCreatedAt(LocalDateTime.now());
      return c;
    });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    // Akteur erwaehnt sich selbst
    when(mentionResolver.resolve(any())).thenReturn(List.of(ACTOR_REF));

    service.createComment(
        taskUuid, new CommentCreateInput("@actor eigene Anmerkung.", parentUuid));

    // Kein emit an Akteur (weder REPLY noch MENTION)
    verify(notificationService, never()).emit(
        eq(ACTOR_REF), any(), any(), any(), any(), any(), any());
  }

  // ---- Test 6: unbekannte Task -> 404 ------------------------------------------------------------

  @Test
  @DisplayName("unbekannte_task_404: Aufgabe nicht gefunden -> EntityNotFoundException")
  void unbekannte_task_404() {
    when(taskRepository.findByUuid(any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.createComment(
                UUID.randomUUID(), new CommentCreateInput("Text.", null)))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  // ---- Test 7: leerer Text -> 400 ----------------------------------------------------------------

  @Test
  @DisplayName("leerer_text_400: Text leer -> EntityValidationException")
  void leerer_text_400() {
    assertThatThrownBy(() -> new CommentCreateInput("", null))
        .isInstanceOf(EntityValidationException.class);

    assertThatThrownBy(() -> new CommentCreateInput("   ", null))
        .isInstanceOf(EntityValidationException.class);

    assertThatThrownBy(() -> new CommentCreateInput(null, null))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Test 8: fremder Eltern-Kommentar -> 400 --------------------------------------------------

  @Test
  @DisplayName(
      "fremder_parent_400: Eltern-Kommentar gehoert anderer Task -> EntityValidationException")
  void fremder_parent_400() {
    final Long taskId = 47L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    final UUID parentUuid = UUID.randomUUID();
    // Eltern-Kommentar gehoert einer ANDEREN Task (taskId=999)
    final TaskComment parent = commentWithId(13L, 999L, ALICE_UUID, parentUuid);
    parent.setTenantId(TENANT_ID);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(parent));

    assertThatThrownBy(
            () -> service.createComment(
                taskUuid, new CommentCreateInput("Antwort.", parentUuid)))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- P2-H: inaktiver Eltern-Kommentar -> 400 ------------------------------------------------

  @Test
  @DisplayName("inaktiver_parent_400: Eltern-Kommentar mit ENTITY_STATUS != ACTIVE -> EntityValidationException")
  void inaktiver_parent_400() {
    final Long taskId = 49L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final UUID parentUuid = UUID.randomUUID();
    final TaskComment inactiveParent = commentWithId(20L, taskId, ALICE_UUID, parentUuid);
    inactiveParent.setEntityState(io.keel.framework.api.model.EntityState.DEACTIVE);
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(inactiveParent));

    assertThatThrownBy(
            () -> service.createComment(taskUuid, new CommentCreateInput("Antwort.", parentUuid)))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Test 9: createdAt wird mit Persistenz-Zone (Europe/Berlin) konvertiert -----------------

  @Test
  @DisplayName(
      "created_at_persistence_zone: ANGELEGT_AM wird via Europe/Berlin konvertiert (nicht UTC)")
  void created_at_persistence_zone() {
    final Long taskId = 48L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    final LocalDateTime savedAt = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
    final Instant expectedInstant = savedAt.atZone(ZoneId.of("Europe/Berlin")).toInstant();

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.save(any(TaskComment.class))).thenAnswer(inv -> {
      final TaskComment c = inv.getArgument(0);
      c.setUuid(UUID.randomUUID());
      c.setCreatedAt(savedAt);
      return c;
    });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(mentionResolver.resolve(any())).thenReturn(List.of());

    final CommentModel model =
        service.createComment(taskUuid, new CommentCreateInput("Persistenz-Zone-Test.", null));

    assertThat(model.createdAt()).isEqualTo(expectedInstant);
  }

  // ---- Hilfsmethoden ---------------------------------------------------------------------------

  private static Task taskWithId(final Long id, final UUID uuid, final int commentCount) {
    final Task task = new Task();
    task.setId(id);
    task.setUuid(uuid);
    task.setTenantId(TENANT_ID);
    task.setEntityState(io.keel.framework.api.model.EntityState.ACTIVE);
    task.setTitle("Testtitel");
    task.setLastActivityAt(LocalDateTime.now().minusMinutes(10));
    task.setCommentCount(commentCount);
    return task;
  }

  private static TaskComment commentWithId(
      final Long id, final Long taskId, final UUID authorRef, final UUID uuid) {
    final TaskComment comment = new TaskComment();
    comment.setId(id);
    comment.setUuid(uuid);
    comment.setTenantId(TENANT_ID);
    comment.setTaskId(taskId);
    comment.setAuthorRef(authorRef);
    comment.setText("Eltern-Kommentar");
    comment.setEdited(false);
    return comment;
  }

  private static TaskUserState stateWithUserRef(final UUID userRef, final Long taskId) {
    final TaskUserState state = new TaskUserState();
    state.setId(99L);
    state.setTenantId(TENANT_ID);
    state.setTaskId(taskId);
    state.setUserRef(userRef);
    return state;
  }
}
