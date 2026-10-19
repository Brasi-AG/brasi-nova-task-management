package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
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
import java.util.ArrayList;
import java.util.Arrays;
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
 *   <li>MENTION-Benachrichtigungen aus den EXPLIZITEN Ziel-GUIDs des Clients (NICHT aus dem Text),
 *       mandanten-scoped auf aktive Benutzer validiert; nicht aufloesbare GUIDs still verworfen
 *   <li>REPLY-Benachrichtigung an Eltern-Autor
 *   <li>Deduplizierung: Eltern-Autor erhaelt nur REPLY, nicht zusaetzlich MENTION; doppelte GUID -> 1x
 *   <li>Kein Selbst-Notify
 *   <li>404 bei unbekannter Aufgabe
 *   <li>400 bei leerem Text / ungueltigem Eltern-Kommentar / null-Element in mentions
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
  private static final UUID GHOST_UUID = UUID.randomUUID();

  @Mock private BaseTaskRepository taskRepository;
  @Mock private BaseTaskCommentRepository commentRepository;
  @Mock private TaskUserStateQueryRepository userStateQueryRepository;
  @Mock private NotificationService notificationService;
  @Mock private InboxSignalService inboxSignalService;
  @Mock private UserReadRepository userReadRepository;
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
        userReadRepository,
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
          + " + save(task) + 1 Kommentar + invalidate; keine mentions -> kein User-Lookup")
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

    final CommentCreateInput input = new CommentCreateInput("Einfacher Kommentar.", null, List.of());
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

    // Leere mentions -> gar kein User-Lookup (fail-fast / kein unnoetiger Repo-Call)
    verifyNoInteractions(userReadRepository);

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

  // ---- Test 2: Mention-Benachrichtigung aus expliziten GUIDs (unaufloesbare verworfen) ----------

  @Test
  @DisplayName(
      "mention_emittiert_mention_notification: mentions=[alice,bob,ghost],"
          + " ghost nicht aufloesbar -> genau 2 MENTION-Emits (alice+bob)")
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

    // alice + bob loesen auf, ghost NICHT (nicht im Ergebnis von findActiveByUuids)
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(ALICE_UUID), userWith(BOB_UUID)));

    service.createComment(
        taskUuid,
        new CommentCreateInput(
            "Hallo zusammen.", null, List.of(ALICE_UUID, BOB_UUID, GHOST_UUID)));

    // Genau 2 MENTION-Notifications (alice + bob); ghost still verworfen
    verify(notificationService, times(1)).emit(
        eq(ALICE_UUID), eq(taskId), eq(NotificationTypeCodes.MENTION),
        eq(NotificationSeverityCodes.INFO), any(), any(), eq(ACTOR_REF));
    verify(notificationService, times(1)).emit(
        eq(BOB_UUID), eq(taskId), eq(NotificationTypeCodes.MENTION),
        eq(NotificationSeverityCodes.INFO), any(), any(), eq(ACTOR_REF));
    verify(notificationService, never()).emit(
        eq(GHOST_UUID), any(), any(), any(), any(), any(), any());

    // Keine REPLY-Notification
    verify(notificationService, never()).emit(
        any(), any(), eq(NotificationTypeCodes.REPLY), any(), any(), any(), any());
  }

  // ---- Test 2b: unaufloesbare/fremd-tenant GUID -> verworfen, Kommentar trotzdem angelegt -------

  @Test
  @DisplayName(
      "unaufloesbare_mention_verworfen_kommentar_bleibt: mentions=[bob] loest NICHT auf"
          + " -> keine MENTION, Kommentar dennoch gespeichert")
  void unaufloesbare_mention_verworfen_kommentar_bleibt() {
    final Long taskId = 51L;
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

    // Nicht aufloesbar (z.B. fremder Tenant / inaktiv) -> leeres Ergebnis
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of());

    final CommentModel model =
        service.createComment(taskUuid, new CommentCreateInput("Text.", null, List.of(BOB_UUID)));

    assertThat(model).isNotNull();
    verify(commentRepository).save(any(TaskComment.class));
    verify(notificationService, never()).emit(
        any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
  }

  // ---- Test 2c: doppelte GUID -> genau eine MENTION ---------------------------------------------

  @Test
  @DisplayName("doppelte_mention_guid_ein_notify: mentions=[alice,alice] -> genau 1 MENTION an alice")
  void doppelte_mention_guid_ein_notify() {
    final Long taskId = 52L;
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
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(ALICE_UUID)));

    service.createComment(
        taskUuid, new CommentCreateInput("Doppelt.", null, List.of(ALICE_UUID, ALICE_UUID)));

    verify(notificationService, times(1)).emit(
        eq(ALICE_UUID), eq(taskId), eq(NotificationTypeCodes.MENTION),
        any(), any(), any(), eq(ACTOR_REF));
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

    service.createComment(
        taskUuid, new CommentCreateInput("Antwort auf deinen Kommentar.", parentUuid, List.of()));

    // 1 REPLY-Notification an Eltern-Autor
    verify(notificationService, times(1)).emit(
        eq(parentAuthorRef), eq(taskId), eq(NotificationTypeCodes.REPLY),
        eq(NotificationSeverityCodes.INFO), any(), any(), eq(ACTOR_REF));

    // Keine MENTION
    verify(notificationService, never()).emit(
        any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
  }

  // ---- Test 4: Deduplizierung - REPLY-Empfaenger bekommt kein zusaetzliches MENTION -----------

  @Test
  @DisplayName(
      "reply_dedupe_kein_mention_wenn_schon_reply:"
          + " Eltern-Autor auch in mentions -> nur REPLY, kein MENTION")
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

    // alice ist in den Mentions (sie ist auch Eltern-Autor)
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(ALICE_UUID)));

    service.createComment(
        taskUuid, new CommentCreateInput("danke!", parentUuid, List.of(ALICE_UUID)));

    // alice bekommt nur REPLY, nicht MENTION
    verify(notificationService, times(1)).emit(
        eq(ALICE_UUID), eq(taskId), eq(NotificationTypeCodes.REPLY), any(), any(), any(), any());
    verify(notificationService, never()).emit(
        eq(ALICE_UUID), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
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

    // Akteur erwaehnt sich selbst (loest auf, wird aber per Self-Notify-Ausschluss gefiltert)
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(ACTOR_REF)));

    service.createComment(
        taskUuid, new CommentCreateInput("eigene Anmerkung.", parentUuid, List.of(ACTOR_REF)));

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
                UUID.randomUUID(), new CommentCreateInput("Text.", null, List.of())))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  // ---- Test 7: leerer Text -> 400 ----------------------------------------------------------------

  @Test
  @DisplayName("leerer_text_400: Text leer -> EntityValidationException")
  void leerer_text_400() {
    assertThatThrownBy(() -> new CommentCreateInput("", null, List.of()))
        .isInstanceOf(EntityValidationException.class);

    assertThatThrownBy(() -> new CommentCreateInput("   ", null, List.of()))
        .isInstanceOf(EntityValidationException.class);

    assertThatThrownBy(() -> new CommentCreateInput(null, null, List.of()))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Test 7b: null-Element in mentions -> 400 (F-P2b-Muster) ---------------------------------

  @Test
  @DisplayName("mentions_null_element_400: mentions=[null] -> EntityValidationException")
  void mentions_null_element_400() {
    assertThatThrownBy(() -> new CommentCreateInput("Text.", null, Arrays.asList((UUID) null)))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Test 7c: mentions ueber Obergrenze (100) -> 400 (SQL-Parameterlimit-Schutz) -------------

  @Test
  @DisplayName("mentions_ueber_limit_400: 101 Mentions -> EntityValidationException (Bound-Schutz)")
  void mentions_ueber_limit_400() {
    final List<UUID> tooMany = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      tooMany.add(UUID.randomUUID());
    }
    assertThatThrownBy(() -> new CommentCreateInput("Text.", null, tooMany))
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
                taskUuid, new CommentCreateInput("Antwort.", parentUuid, List.of())))
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
            () -> service.createComment(
                taskUuid, new CommentCreateInput("Antwort.", parentUuid, List.of())))
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

    final CommentModel model =
        service.createComment(
            taskUuid, new CommentCreateInput("Persistenz-Zone-Test.", null, List.of()));

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

  private static User userWith(final UUID uuid) {
    final User u = new User();
    u.setId(1L);
    u.setUuid(uuid);
    u.setLoginName("test");
    return u;
  }
}
