package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.attachment.AttachmentFileType;
import de.brasi.nova.taskmanagement.attachment.AttachmentModelMapper;
import de.brasi.nova.taskmanagement.attachment.AttachmentProperties;
import de.brasi.nova.taskmanagement.attachment.AttachmentWriter;
import de.brasi.nova.taskmanagement.attachment.PreparedAttachment;
import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.division.DivisionMentionResolver;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationMuteFilter;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.AssigneeService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskCommentRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;

/**
 * Unit-Tests fuer {@link CommentService} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale: beweist die Kern-Invarianten fuer Kommentar-Erstellung:
 *
 * <ol>
 *   <li>COMMENT_COUNT++ + LAST_ACTIVITY_AT + save(task) + 1 Kommentar gespeichert + invalidate
 *   <li>MENTION-Benachrichtigungen aus den EXPLIZITEN Ziel-GUIDs des Clients (NICHT aus dem Text),
 *       mandanten-scoped auf aktive Benutzer validiert; nicht aufloesbare GUIDs still verworfen
 *   <li>REPLY-Benachrichtigung an Eltern-Autor
 *   <li>Deduplizierung: Eltern-Autor erhaelt nur REPLY, nicht zusaetzlich MENTION; doppelte GUID ->
 *       1x
 *   <li>Kein Selbst-Notify
 *   <li>404 bei unbekannter Aufgabe
 *   <li>400 bei leerem Text / ungueltigem Eltern-Kommentar / null-Element in mentions
 * </ol>
 *
 * <p>Intake #152 Teil B2: Anhaenge werden in derselben Transaktion nach dem Kommentar-Insert und
 * vor jeder Benachrichtigung gespeichert ({@link AttachmentWriter} gemockt, echter {@link
 * AttachmentModelMapper}); scheitert das Speichern, gibt es keine Benachrichtigung, keinen Bump und
 * kein Signal.
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
  @Mock private TaskParticipantQueryRepository participantQueryRepository;
  @Mock private NotificationService notificationService;
  @Mock private InboxSignalService inboxSignalService;
  @Mock private InboxGenerationService inboxGenerationService;
  @Mock private UserReadRepository userReadRepository;
  @Mock private UserAccessor userAccessor;
  @Mock private TaskMembershipGuard membershipGuard;
  @Mock private AssigneeService assigneeService;
  @Mock private AttachmentWriter attachmentWriter;
  @Mock private DivisionMentionResolver divisionMentionResolver;

  private AttachmentModelMapper attachmentModelMapper;
  private AttachmentProperties attachmentProperties;
  private CommentService service;

  @BeforeEach
  void setUp() {
    attachmentModelMapper =
        new AttachmentModelMapper(new AuditTimeConverter("Europe/Berlin"), "/task-management");
    // Intake #152: hoechstens 20 Anhaenge je Kommentar (Sicherung im CommentService).
    attachmentProperties =
        new AttachmentProperties(
            new AttachmentProperties.Storage(null), List.of(), 20, null, null, null);
    service =
        new CommentService(
            taskRepository,
            commentRepository,
            userStateQueryRepository,
            participantQueryRepository,
            notificationService,
            inboxSignalService,
            inboxGenerationService,
            userReadRepository,
            userAccessor,
            new AuditTimeConverter("Europe/Berlin"),
            membershipGuard,
            new NotificationMuteFilter(userStateQueryRepository),
            assigneeService,
            attachmentWriter,
            attachmentModelMapper,
            attachmentProperties,
            divisionMentionResolver);

    final TenantInfo tenant = new TenantInfo(TENANT_UUID, TENANT_ID);
    final AuthUser user = new AuthUser(ACTOR_REF, "actor@brasi.de");
    when(userAccessor.requireCurrentUser()).thenReturn(user);
    TenantContextHolder.setTenant(tenant);

    // Slice 3/Auflage 4: Mention/Reply-Empfaenger werden auf WRITE-Member gefiltert. Default: jeder
    // in diesen Tests adressierte Empfaenger (alice/bob/parent-author) IST WRITE-Member -> die
    // bestehenden Notification-Erwartungen bleiben gueltig. Tests fuer Nicht-/Ex-Member-Empfaenger
    // stuben isWriteMember gezielt auf false. Der WRITE-Member-Guard des Akteurs
    // (requireWriteMember)
    // wirft im Default-Mock nicht -> Happy Path.
    when(membershipGuard.isWriteMember(anyLong(), any(), anyLong())).thenReturn(true);

    when(notificationService.emit(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(UUID.randomUUID());

    // Codex #6: createComment bumpt die aktiven Teilnehmer jetzt ueber den zentralen Helfer
    // bumpSorted
    // (delegiert produktiv je Ref sortiert an bump). Der gemockte Helfer an denselben Mock
    // delegieren,
    // damit die bestehenden je-Ref bump(..)-Verifikationen unveraendert gueltig bleiben.
    org.mockito.Mockito.doAnswer(
            inv -> {
              final long t = inv.getArgument(0);
              final Collection<UUID> c = inv.getArgument(1);
              if (c != null) {
                c.stream().sorted().forEach(r -> inboxGenerationService.bump(t, r));
              }
              return null;
            })
        .when(inboxGenerationService)
        .bumpSorted(anyLong(), any());
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  // ---- Test 1: Kommentar zaehlt hoch + setzt Aktivitaet
  // ------------------------------------------

  @Test
  @DisplayName(
      "kommentar_zaehlt_hoch_und_setzt_aktivitaet: commentCount+1 + lastActivityAt"
          + " + save(task) + 1 Kommentar + invalidate; keine mentions -> kein User-Lookup")
  void kommentar_zaehlt_hoch_und_setzt_aktivitaet() {
    final Long taskId = 42L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 3);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
              final TaskComment c = inv.getArgument(0);
              c.setUuid(UUID.randomUUID());
              c.setCreatedAt(LocalDateTime.now());
              return c;
            });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

    // Aktive Teilnehmer
    final TaskUserState state1 = stateWithUserRef(ALICE_UUID, taskId);
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of(state1));
    final TaskParticipant archivedReader = new TaskParticipant();
    archivedReader.setParticipantRef(BOB_UUID);
    when(participantQueryRepository.findArchiveReadersByTaskId(taskId))
        .thenReturn(List.of(archivedReader));

    final CommentCreateInput input =
        new CommentCreateInput("Einfacher Kommentar.", null, List.of(), List.of());
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
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.REPLY), any(), any(), any(), any());

    // Keine MENTION-Notification (keine Mentions)
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());

    // Leere mentions -> gar kein User-Lookup (fail-fast / kein unnoetiger Repo-Call)
    verifyNoInteractions(userReadRepository);

    // Invalidate aufgerufen
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());
    assertThat(refsCaptor.getValue()).containsExactlyInAnyOrder(ALICE_UUID, BOB_UUID);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> bumpCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxGenerationService).bumpSorted(eq(TENANT_ID), bumpCaptor.capture());
    assertThat(bumpCaptor.getValue()).containsExactlyInAnyOrderElementsOf(refsCaptor.getValue());

    // Codex #6: Inbox-Generation JEDES aktiven Teilnehmers gebumpt (hier genau ALICE) - sonst
    // lieferte ein alter aktiver Cursor nach dem LAST_ACTIVITY_AT-Bump stale 200 statt 410.
    verify(inboxGenerationService).bump(TENANT_ID, ALICE_UUID);
    verify(inboxGenerationService).bump(TENANT_ID, BOB_UUID);

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
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
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
        new CommentCreateInput("Hallo zusammen.", null, List.of(ALICE_UUID, BOB_UUID, GHOST_UUID), List.of()));

    // Genau 2 MENTION-Notifications (alice + bob); ghost still verworfen
    verify(notificationService, times(1))
        .emit(
            eq(ALICE_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.MENTION),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(notificationService, times(1))
        .emit(
            eq(BOB_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.MENTION),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(notificationService, never())
        .emit(eq(GHOST_UUID), any(), any(), any(), any(), any(), any());

    // Keine REPLY-Notification
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.REPLY), any(), any(), any(), any());

    // Codex #6: keine aktiven Teilnehmer -> weder Bump noch Invalidate (empty-Guard).
    verifyNoInteractions(inboxGenerationService);
    verify(inboxSignalService, never()).invalidate(anyLong(), any());
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
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
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
        service.createComment(taskUuid, new CommentCreateInput("Text.", null, List.of(BOB_UUID), List.of()));

    assertThat(model).isNotNull();
    verify(commentRepository).save(any(TaskComment.class));
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
  }

  // ---- Test 2c: doppelte GUID -> genau eine MENTION ---------------------------------------------

  @Test
  @DisplayName(
      "doppelte_mention_guid_ein_notify: mentions=[alice,alice] -> genau 1 MENTION an alice")
  void doppelte_mention_guid_ein_notify() {
    final Long taskId = 52L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
              final TaskComment c = inv.getArgument(0);
              c.setUuid(UUID.randomUUID());
              c.setCreatedAt(LocalDateTime.now());
              return c;
            });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(ALICE_UUID)));

    service.createComment(
        taskUuid, new CommentCreateInput("Doppelt.", null, List.of(ALICE_UUID, ALICE_UUID), List.of()));

    verify(notificationService, times(1))
        .emit(
            eq(ALICE_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.MENTION),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  // ---- Test 3: REPLY an Eltern-Autor
  // -------------------------------------------------------------

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
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
              final TaskComment c = inv.getArgument(0);
              c.setUuid(UUID.randomUUID());
              c.setCreatedAt(LocalDateTime.now());
              return c;
            });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.createComment(
        taskUuid, new CommentCreateInput("Antwort auf deinen Kommentar.", parentUuid, List.of(), List.of()));

    // 1 REPLY-Notification an Eltern-Autor
    verify(notificationService, times(1))
        .emit(
            eq(parentAuthorRef),
            eq(taskId),
            eq(NotificationTypeCodes.REPLY),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));

    // Keine MENTION
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
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
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
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
        taskUuid, new CommentCreateInput("danke!", parentUuid, List.of(ALICE_UUID), List.of()));

    // alice bekommt nur REPLY, nicht MENTION
    verify(notificationService, times(1))
        .emit(
            eq(ALICE_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.REPLY),
            any(),
            any(),
            any(),
            any());
    verify(notificationService, never())
        .emit(eq(ALICE_UUID), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
  }

  // ---- Test 5: kein Selbst-Notify
  // ----------------------------------------------------------------

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
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
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
        taskUuid, new CommentCreateInput("eigene Anmerkung.", parentUuid, List.of(ACTOR_REF), List.of()));

    // Kein emit an Akteur (weder REPLY noch MENTION)
    verify(notificationService, never())
        .emit(eq(ACTOR_REF), any(), any(), any(), any(), any(), any());
  }

  // ---- Test 6: unbekannte Task -> 404
  // ------------------------------------------------------------

  @Test
  @DisplayName("unbekannte_task_404: Aufgabe nicht gefunden -> EntityNotFoundException")
  void unbekannte_task_404() {
    when(taskRepository.findByUuid(any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.createComment(
                    UUID.randomUUID(), new CommentCreateInput("Text.", null, List.of(), List.of())))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  // ---- Test 7: leerer Text -> 400
  // ----------------------------------------------------------------

  @Test
  @DisplayName("leerer_text_400: Text leer -> EntityValidationException")
  void leerer_text_400() {
    assertThatThrownBy(() -> new CommentCreateInput("", null, List.of(), List.of()))
        .isInstanceOf(EntityValidationException.class);

    assertThatThrownBy(() -> new CommentCreateInput("   ", null, List.of(), List.of()))
        .isInstanceOf(EntityValidationException.class);

    assertThatThrownBy(() -> new CommentCreateInput(null, null, List.of(), List.of()))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Test 7b: null-Element in mentions -> 400 (F-P2b-Muster) ---------------------------------

  @Test
  @DisplayName("mentions_null_element_400: mentions=[null] -> EntityValidationException")
  void mentions_null_element_400() {
    assertThatThrownBy(() -> new CommentCreateInput("Text.", null, Arrays.asList((UUID) null), List.of()))
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
    assertThatThrownBy(() -> new CommentCreateInput("Text.", null, tooMany, List.of()))
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
            () ->
                service.createComment(
                    taskUuid, new CommentCreateInput("Antwort.", parentUuid, List.of(), List.of())))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- P2-H: inaktiver Eltern-Kommentar -> 400 ------------------------------------------------

  @Test
  @DisplayName(
      "inaktiver_parent_400: Eltern-Kommentar mit ENTITY_STATUS != ACTIVE -> EntityValidationException")
  void inaktiver_parent_400() {
    final Long taskId = 49L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final UUID parentUuid = UUID.randomUUID();
    final TaskComment inactiveParent = commentWithId(20L, taskId, ALICE_UUID, parentUuid);
    inactiveParent.setEntityState(io.keel.framework.api.model.EntityState.DEACTIVE);
    when(commentRepository.findByUuid(parentUuid.toString()))
        .thenReturn(Optional.of(inactiveParent));

    assertThatThrownBy(
            () ->
                service.createComment(
                    taskUuid, new CommentCreateInput("Antwort.", parentUuid, List.of(), List.of())))
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
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
              final TaskComment c = inv.getArgument(0);
              c.setUuid(UUID.randomUUID());
              c.setCreatedAt(savedAt);
              return c;
            });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    final CommentModel model =
        service.createComment(
            taskUuid, new CommentCreateInput("Persistenz-Zone-Test.", null, List.of(), List.of()));

    assertThat(model.createdAt()).isEqualTo(expectedInstant);
  }

  // ---- Slice 3: Akteur-WRITE-Member-Guard -----------------------------------------------------

  @Test
  @DisplayName(
      "non_member_actor_404_kein_write: Guard wirft 404 fuer Non-/Ex-Member-Akteur VOR der Mutation"
          + " -> kein Kommentar, kein Count-Bump, kein Notify")
  void non_member_actor_404_kein_write() {
    final Long taskId = 60L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    org.mockito.Mockito.doThrow(new EntityNotFoundException("Aufgabe nicht gefunden."))
        .when(membershipGuard)
        .requireWriteMember(taskId, ACTOR_REF, TENANT_ID);

    assertThatThrownBy(
            () -> service.createComment(taskUuid, new CommentCreateInput("Text.", null, List.of(), List.of())))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");

    // Keine Mutation, kein Fan-out.
    verify(commentRepository, never()).save(any(TaskComment.class));
    verify(taskRepository, never()).save(any(Task.class));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
  }

  // ---- Slice 3/Auflage 4: Mention/Reply-Empfaenger = WRITE-Member-Menge -----------------------

  @Test
  @DisplayName(
      "mention_nur_an_write_member: alice ist WRITE-Member (Notify), bob ist Ausgetretener/Nicht-"
          + "Teilnehmer (isWriteMember=false) -> KEINE MENTION an bob")
  void mention_nur_an_write_member() {
    final Long taskId = 61L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
              final TaskComment c = inv.getArgument(0);
              c.setUuid(UUID.randomUUID());
              c.setCreatedAt(LocalDateTime.now());
              return c;
            });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    // Beide loesen als aktive Mandanten-Nutzer auf (findActiveByUuids).
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(ALICE_UUID), userWith(BOB_UUID)));
    // Aber nur alice ist WRITE-Member; bob ist ausgetreten/Nicht-Teilnehmer.
    when(membershipGuard.isWriteMember(eq(taskId), eq(ALICE_UUID), eq(TENANT_ID))).thenReturn(true);
    when(membershipGuard.isWriteMember(eq(taskId), eq(BOB_UUID), eq(TENANT_ID))).thenReturn(false);

    service.createComment(
        taskUuid, new CommentCreateInput("Hallo.", null, List.of(ALICE_UUID, BOB_UUID), List.of()));

    // MENTION nur an alice, NICHT an bob.
    verify(notificationService, times(1))
        .emit(
            eq(ALICE_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.MENTION),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(notificationService, never())
        .emit(eq(BOB_UUID), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "reply_an_ausgetretenen_parent_kein_notify: Eltern-Autor ist ausgetreten (isWriteMember=false)"
          + " -> KEINE REPLY-Notification; Kommentar trotzdem angelegt")
  void reply_an_ausgetretenen_parent_kein_notify() {
    final Long taskId = 62L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 1);

    final UUID parentUuid = UUID.randomUUID();
    final UUID parentAuthorRef = ALICE_UUID; // ausgetreten
    final TaskComment parent = commentWithId(70L, taskId, parentAuthorRef, parentUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(parent));
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
              final TaskComment c = inv.getArgument(0);
              c.setUuid(UUID.randomUUID());
              c.setCreatedAt(LocalDateTime.now());
              return c;
            });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    // Eltern-Autor ist KEIN WRITE-Member (ausgetreten) -> kein REPLY-Ping.
    when(membershipGuard.isWriteMember(eq(taskId), eq(parentAuthorRef), eq(TENANT_ID)))
        .thenReturn(false);

    final CommentModel model =
        service.createComment(taskUuid, new CommentCreateInput("Antwort.", parentUuid, List.of(), List.of()));

    assertThat(model).isNotNull();
    verify(commentRepository).save(any(TaskComment.class));
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.REPLY), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "reply_an_aktiven_write_member_notify: Eltern-Autor ist aktiver WRITE-Member -> 1 REPLY")
  void reply_an_aktiven_write_member_notify() {
    final Long taskId = 63L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 1);

    final UUID parentUuid = UUID.randomUUID();
    final UUID parentAuthorRef = ALICE_UUID;
    final TaskComment parent = commentWithId(71L, taskId, parentAuthorRef, parentUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(parent));
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
              final TaskComment c = inv.getArgument(0);
              c.setUuid(UUID.randomUUID());
              c.setCreatedAt(LocalDateTime.now());
              return c;
            });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    when(membershipGuard.isWriteMember(eq(taskId), eq(parentAuthorRef), eq(TENANT_ID)))
        .thenReturn(true);

    service.createComment(taskUuid, new CommentCreateInput("danke!", parentUuid, List.of(), List.of()));

    verify(notificationService, times(1))
        .emit(
            eq(parentAuthorRef),
            eq(taskId),
            eq(NotificationTypeCodes.REPLY),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  // ===========================================================================================
  // Intake #136: Top-Level-Kommentar benachrichtigt die zugewiesene Person (COMMENT)
  // ===========================================================================================

  // ---- C1: Top-Level, 1 Assignee (nicht Autor, nicht erwaehnt) -> genau 1x COMMENT -------------

  @Test
  @DisplayName(
      "C1 comment_top_level_ein_assignee_ein_comment: Top-Level-Kommentar, 1 Assignee"
          + " (nicht Autor, nicht erwaehnt) -> genau 1x emit(COMMENT) an Assignee")
  void comment_top_level_ein_assignee_ein_comment() {
    final Long taskId = 80L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(assigneeWith(ALICE_UUID)));

    service.createComment(
        taskUuid, new CommentCreateInput("Top-Level ohne Mention.", null, List.of(), List.of()));

    // Genau 1 COMMENT-Notification an den Assignee (Typ/Empfaenger/Text/Actor verifiziert).
    final ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
    verify(notificationService, times(1))
        .emit(
            eq(ALICE_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.COMMENT),
            eq(NotificationSeverityCodes.INFO),
            textCaptor.capture(),
            eq("Testtitel"),
            eq(ACTOR_REF));
    assertThat(textCaptor.getValue())
        .isEqualTo("Neuer Kommentar bei der dir zugewiesenen Aufgabe.");

    // Kein MENTION, kein REPLY.
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.REPLY), any(), any(), any(), any());
  }

  // ---- C2: Autor == Assignee -> KEIN COMMENT (Self-Exclusion) ----------------------------------

  @Test
  @DisplayName(
      "C2 comment_autor_ist_assignee_kein_comment: Akteur selbst ist Assignee -> kein COMMENT")
  void comment_autor_ist_assignee_kein_comment() {
    final Long taskId = 81L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    // Der einzige Assignee ist der Akteur selbst.
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(assigneeWith(ACTOR_REF)));

    service.createComment(taskUuid, new CommentCreateInput("Eigener Kommentar.", null, List.of(), List.of()));

    verify(notificationService, never())
        .emit(eq(ACTOR_REF), any(), eq(NotificationTypeCodes.COMMENT), any(), any(), any(), any());
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.COMMENT), any(), any(), any(), any());
  }

  // ---- C3: Assignee auch erwaehnt -> nur MENTION, KEIN COMMENT (Vorrang) ------------------------

  @Test
  @DisplayName(
      "C3 comment_assignee_auch_erwaehnt_nur_mention: Assignee ist zugleich in mentions"
          + " -> nur MENTION, KEIN zusaetzliches COMMENT (MENTION-Vorrang)")
  void comment_assignee_auch_erwaehnt_nur_mention() {
    final Long taskId = 82L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(ALICE_UUID)));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(assigneeWith(ALICE_UUID)));

    service.createComment(
        taskUuid, new CommentCreateInput("Hallo Alice.", null, List.of(ALICE_UUID), List.of()));

    // alice bekommt MENTION, aber KEIN COMMENT (Vorrang).
    verify(notificationService, times(1))
        .emit(
            eq(ALICE_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.MENTION),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(notificationService, never())
        .emit(eq(ALICE_UUID), any(), eq(NotificationTypeCodes.COMMENT), any(), any(), any(), any());
  }

  // ---- C4: Reply (parentCommentId gesetzt) -> KEIN COMMENT (nur Top-Level) ----------------------

  @Test
  @DisplayName("C4 comment_reply_kein_comment: Reply-Kommentar (parentCommentId) -> KEIN COMMENT")
  void comment_reply_kein_comment() {
    final Long taskId = 83L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 1);

    final UUID parentUuid = UUID.randomUUID();
    // Eltern-Autor ist ein anderer WRITE-Member (bob) -> REPLY entsteht, aber nie COMMENT.
    final TaskComment parent = commentWithId(90L, taskId, BOB_UUID, parentUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(parent));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.createComment(taskUuid, new CommentCreateInput("Antwort.", parentUuid, List.of(), List.of()));

    // Kein COMMENT (Reply ist nicht Top-Level); der Assignee-Read darf fuer Reply gar nicht laufen.
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.COMMENT), any(), any(), any(), any());
    verify(participantQueryRepository, never()).findActiveAssigneesByTaskId(any());
  }

  // ---- C5: mehrere Assignees -> je ein COMMENT; ein Assignee, der Autor ist, ausgenommen --------

  @Test
  @DisplayName(
      "C5 comment_mehrere_assignees_je_ein_comment: 3 Assignees (alice, bob, Akteur)"
          + " -> je 1 COMMENT an alice+bob, KEIN COMMENT an Akteur")
  void comment_mehrere_assignees_je_ein_comment() {
    final Long taskId = 84L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(
            List.of(assigneeWith(ALICE_UUID), assigneeWith(BOB_UUID), assigneeWith(ACTOR_REF)));

    service.createComment(taskUuid, new CommentCreateInput("Fuer alle.", null, List.of(), List.of()));

    verify(notificationService, times(1))
        .emit(
            eq(ALICE_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.COMMENT),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(notificationService, times(1))
        .emit(
            eq(BOB_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.COMMENT),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    // Akteur selbst -> kein COMMENT (Self-Exclusion).
    verify(notificationService, never())
        .emit(eq(ACTOR_REF), any(), eq(NotificationTypeCodes.COMMENT), any(), any(), any(), any());
  }

  // ---- C6: reiner Participant (assigneeFlag=false) -> KEIN COMMENT (Branch-Test) ---------------

  @Test
  @DisplayName(
      "C6 comment_reiner_participant_kein_comment: findActiveAssigneesByTaskId liefert leere Menge"
          + " (reine Participants werden vom Query-Filter ausgeschlossen) -> KEIN COMMENT")
  void comment_reiner_participant_kein_comment() {
    final Long taskId = 85L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    // Der echte assigneeFlag=false-Filter liegt in findActiveAssigneesByTaskId (per IT I3 belegt);
    // hier gemockt als leere Assignee-Menge -> die COMMENT-Verzweigung darf nichts emittieren.
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());

    service.createComment(
        taskUuid, new CommentCreateInput("Nur Participant vorhanden.", null, List.of(), List.of()));

    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.COMMENT), any(), any(), any(), any());
  }

  // ===========================================================================================
  // Intake #158: Stummgeschaltete Aufgabe unterdrueckt COMMENT, nicht aber MENTION und REPLY
  // ===========================================================================================

  @Test
  @DisplayName(
      "mute_top_level: stummer Assignee erhaelt KEIN COMMENT, lauter Assignee schon;"
          + " Mute-Lesung gebuendelt (1 Query) VOR dem TASK-save")
  void mute_top_level_kommentar_unterdrueckt_comment_fuer_stummen_assignee() {
    final Long taskId = 88L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(assigneeWith(ALICE_UUID), assigneeWith(BOB_UUID)));
    when(userStateQueryRepository.findMutedByTaskId(eq(taskId)))
        .thenReturn(List.of(mutedState(BOB_UUID, taskId)));

    service.createComment(
        taskUuid, new CommentCreateInput("Top-Level, bob ist stumm.", null, List.of(), List.of()));

    verify(notificationService, times(1))
        .emit(eq(ALICE_UUID), any(), eq(NotificationTypeCodes.COMMENT), any(), any(), any(), any());
    verify(notificationService, never())
        .emit(eq(BOB_UUID), any(), any(), any(), any(), any(), any());
    final org.mockito.InOrder inOrder =
        org.mockito.Mockito.inOrder(userStateQueryRepository, taskRepository);
    inOrder.verify(userStateQueryRepository, times(1)).findMutedByTaskId(eq(taskId));
    inOrder.verify(taskRepository).save(any(Task.class));
  }

  @Test
  @DisplayName(
      "mute_mention: stummer Assignee, der erwaehnt wird, erhaelt die MENTION (Ausnahme),"
          + " aber kein COMMENT")
  void mute_mention_kommt_trotz_mute_an() {
    final Long taskId = 89L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(assigneeWith(BOB_UUID)));
    when(userStateQueryRepository.findMutedByTaskId(eq(taskId)))
        .thenReturn(List.of(mutedState(BOB_UUID, taskId)));

    service.createComment(
        taskUuid, new CommentCreateInput("@bob trotz Mute.", null, List.of(BOB_UUID), List.of()));

    verify(notificationService, times(1))
        .emit(eq(BOB_UUID), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
    verify(notificationService, never())
        .emit(eq(BOB_UUID), any(), eq(NotificationTypeCodes.COMMENT), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "mute_reply: stummer Eltern-Autor erhaelt die REPLY (Ausnahme); fuer Antworten wird der"
          + " Mute-Zustand gar nicht gelesen")
  void mute_reply_kommt_trotz_mute_an() {
    final Long taskId = 90L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 1);
    final UUID parentUuid = UUID.randomUUID();
    final TaskComment parent = commentWithId(92L, taskId, ALICE_UUID, parentUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(parent));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(userStateQueryRepository.findMutedByTaskId(eq(taskId)))
        .thenReturn(List.of(mutedState(ALICE_UUID, taskId)));

    service.createComment(
        taskUuid, new CommentCreateInput("Antwort an stumme alice.", parentUuid, List.of(), List.of()));

    verify(notificationService, times(1))
        .emit(eq(ALICE_UUID), any(), eq(NotificationTypeCodes.REPLY), any(), any(), any(), any());
    verify(userStateQueryRepository, never()).findMutedByTaskId(any());
  }

  // ---- InOrder (Codex-Hinweis: GETRENNTE Aufrufe!): Reads VOR taskRepository.save --------------

  @Test
  @DisplayName(
      "lock_order_top_level_mit_mention: alle Reads (MENTION-isWriteMember +"
          + " findActiveAssigneesByTaskId) laufen VOR taskRepository.save (Deadlock-Vermeidung)")
  void lock_order_top_level_mit_mention() {
    final Long taskId = 86L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(assigneeWith(ALICE_UUID)));

    service.createComment(
        taskUuid, new CommentCreateInput("Top-Level mit Mention.", null, List.of(BOB_UUID), List.of()));

    // GETRENNTE Aufrufe: die MENTION-WRITE-Pruefung und der Assignee-Read muessen VOR dem TASK-save
    // liegen (Teilnehmer-dann-Task == gleiche Lock-Reihenfolge wie updateAssignees).
    final org.mockito.InOrder inOrder =
        org.mockito.Mockito.inOrder(membershipGuard, participantQueryRepository, taskRepository);
    inOrder.verify(membershipGuard).isWriteMember(taskId, BOB_UUID, TENANT_ID);
    inOrder.verify(participantQueryRepository).findActiveAssigneesByTaskId(taskId);
    inOrder.verify(taskRepository).save(any(Task.class));
  }

  @Test
  @DisplayName(
      "#150 lock_order_kommentar_nach_task_save: der Kommentar (ANGELEGT_AM) entsteht erst nach"
          + " dem TASK-save, also unter der Schreibsperre der Aufgabenzeile (Serialisierung mit"
          + " markSeen)")
  void lock_order_kommentar_nach_task_save() {
    final Long taskId = 158L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 0);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.createComment(taskUuid, new CommentCreateInput("Nach dem TASK-save.", null, List.of(), List.of()));

    final org.mockito.InOrder inOrder =
        org.mockito.Mockito.inOrder(taskRepository, commentRepository);
    inOrder.verify(taskRepository).save(any(Task.class));
    inOrder.verify(commentRepository).save(any(TaskComment.class));
  }

  @Test
  @DisplayName(
      "lock_order_reply_mit_mention: alle Reads (REPLY-isWriteMember + MENTION-isWriteMember)"
          + " laufen VOR taskRepository.save (Deadlock-Vermeidung, Reply-Pfad)")
  void lock_order_reply_mit_mention() {
    final Long taskId = 87L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid, 1);

    final UUID parentUuid = UUID.randomUUID();
    final UUID parentAuthorRef = ALICE_UUID;
    final TaskComment parent = commentWithId(91L, taskId, parentAuthorRef, parentUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(commentRepository.findByUuid(parentUuid.toString())).thenReturn(Optional.of(parent));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));

    service.createComment(
        taskUuid, new CommentCreateInput("Antwort mit Mention.", parentUuid, List.of(BOB_UUID), List.of()));

    // GETRENNTE Aufrufe (Reply-Pfad): REPLY-WRITE-Pruefung (parent-Autor) und
    // MENTION-WRITE-Pruefung
    // (bob) VOR dem TASK-save. (Dieser Pfad kann nicht zugleich den Top-Level-Assignee-Read
    // ausloesen
    // -> daher ein eigener Test, Codex-Hinweis.)
    final org.mockito.InOrder inOrder =
        org.mockito.Mockito.inOrder(membershipGuard, taskRepository);
    inOrder.verify(membershipGuard).isWriteMember(taskId, parentAuthorRef, TENANT_ID);
    inOrder.verify(membershipGuard).isWriteMember(taskId, BOB_UUID, TENANT_ID);
    inOrder.verify(taskRepository).save(any(Task.class));
  }

  // ---- Intake #156: erwaehnte Nicht-Mitglieder werden Beteiligte -------------------------------

  @Test
  @DisplayName(
      "mention_nicht_mitglied_mit_recht_wird_beteiligter: bob (aktiver Mandanten-Nutzer, kein"
          + " Mitglied) wird per addAsParticipants aufgenommen und bekommt NUR die MENTION (keine"
          + " PARTICIPANT); alice (Mitglied) wird unveraendert benachrichtigt")
  void mention_nicht_mitglied_mit_recht_wird_beteiligter() {
    final Long taskId = 150L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(ALICE_UUID), userWith(BOB_UUID)));
    when(membershipGuard.isWriteMember(eq(taskId), eq(BOB_UUID), eq(TENANT_ID))).thenReturn(false);
    when(assigneeService.mayAddParticipants()).thenReturn(true);
    when(assigneeService.addAsParticipants(eq(taskId), eq(TENANT_ID), any()))
        .thenReturn(List.of(BOB_UUID));

    service.createComment(
        taskUuid, new CommentCreateInput("Hallo.", null, List.of(ALICE_UUID, BOB_UUID), List.of()));

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> added = ArgumentCaptor.forClass(Collection.class);
    verify(assigneeService).addAsParticipants(eq(taskId), eq(TENANT_ID), added.capture());
    assertThat(added.getValue())
        .as("nur das Nicht-Mitglied wird aufgenommen")
        .containsExactly(BOB_UUID);

    verifyMention(ALICE_UUID, taskId);
    verifyMention(BOB_UUID, taskId);
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.PARTICIPANT), any(), any(), any(), any());

    // Bump und Signal erreichen die neu aufgenommene Person (ihre Inbox aendert sich).
    verify(inboxGenerationService).bump(TENANT_ID, BOB_UUID);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> signalled = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), signalled.capture());
    assertThat(signalled.getValue()).contains(BOB_UUID);
  }

  @Test
  @DisplayName(
      "lock_order_aufnahme_erwaehnter: Mitgliedschafts-Reads UND die Aufnahme (Teilnehmer- und"
          + " State-Writes) laufen VOR taskRepository.save, der sortierte Bump danach")
  void lock_order_aufnahme_erwaehnter() {
    final Long taskId = 151L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));
    when(membershipGuard.isWriteMember(eq(taskId), eq(BOB_UUID), eq(TENANT_ID))).thenReturn(false);
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(assigneeWith(ALICE_UUID)));
    when(assigneeService.mayAddParticipants()).thenReturn(true);
    when(assigneeService.addAsParticipants(eq(taskId), eq(TENANT_ID), any()))
        .thenReturn(List.of(BOB_UUID));

    service.createComment(
        taskUuid, new CommentCreateInput("Top-Level mit Aufnahme.", null, List.of(BOB_UUID), List.of()));

    final org.mockito.InOrder inOrder =
        org.mockito.Mockito.inOrder(
            membershipGuard,
            participantQueryRepository,
            assigneeService,
            taskRepository,
            inboxGenerationService);
    inOrder.verify(membershipGuard).isWriteMember(taskId, BOB_UUID, TENANT_ID);
    inOrder.verify(participantQueryRepository).findActiveAssigneesByTaskId(taskId);
    inOrder.verify(assigneeService).addAsParticipants(eq(taskId), eq(TENANT_ID), any());
    inOrder.verify(taskRepository).save(any(Task.class));
    inOrder.verify(inboxGenerationService).bumpSorted(eq(TENANT_ID), any());
  }

  @Test
  @DisplayName(
      "mention_nicht_mitglied_ohne_recht_still_verworfen: ohne das Recht von POST /participants"
          + " bleibt bob Nicht-Mitglied (keine Aufnahme, keine MENTION), alice wird weiter"
          + " benachrichtigt")
  void mention_nicht_mitglied_ohne_recht_still_verworfen() {
    final Long taskId = 152L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(ALICE_UUID), userWith(BOB_UUID)));
    when(membershipGuard.isWriteMember(eq(taskId), eq(BOB_UUID), eq(TENANT_ID))).thenReturn(false);
    when(assigneeService.mayAddParticipants()).thenReturn(false);

    service.createComment(
        taskUuid, new CommentCreateInput("Hallo.", null, List.of(ALICE_UUID, BOB_UUID), List.of()));

    verify(assigneeService, never()).addAsParticipants(anyLong(), anyLong(), any());
    verifyMention(ALICE_UUID, taskId);
    verify(notificationService, never())
        .emit(eq(BOB_UUID), any(), any(), any(), any(), any(), any());
    verify(inboxGenerationService, never()).bump(TENANT_ID, BOB_UUID);
  }

  @Test
  @DisplayName(
      "mention_unbekannte_oder_inaktive_ref_nie_aufgenommen: loest findActiveByUuids nicht auf"
          + " (unbekannt, inaktiv, fremder Mandant), gibt es weder Aufnahme noch MENTION noch"
          + " einen Fehler")
  void mention_unbekannte_oder_inaktive_ref_nie_aufgenommen() {
    final Long taskId = 153L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of());
    when(assigneeService.mayAddParticipants()).thenReturn(true);

    final CommentModel model =
        service.createComment(
            taskUuid, new CommentCreateInput("Hallo.", null, List.of(GHOST_UUID), List.of()));

    assertThat(model).isNotNull();
    verify(assigneeService, never()).addAsParticipants(anyLong(), anyLong(), any());
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "mention_akteur_und_reply_empfaenger_nicht_aufgenommen: der Akteur und der Eltern-Autor"
          + " (aktives Mitglied, bekommt REPLY) sind keine Aufnahme-Kandidaten")
  void mention_akteur_und_reply_empfaenger_nicht_aufgenommen() {
    final Long taskId = 154L;
    final UUID taskUuid = stubOpenTask(taskId);
    final UUID parentUuid = UUID.randomUUID();
    when(commentRepository.findByUuid(parentUuid.toString()))
        .thenReturn(Optional.of(commentWithId(92L, taskId, ALICE_UUID, parentUuid)));
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(ACTOR_REF), userWith(ALICE_UUID)));
    when(assigneeService.mayAddParticipants()).thenReturn(true);

    service.createComment(
        taskUuid, new CommentCreateInput("Antwort.", parentUuid, List.of(ACTOR_REF, ALICE_UUID), List.of()));

    verify(assigneeService, never()).addAsParticipants(anyLong(), anyLong(), any());
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "mention_gleichzeitig_aufgenommener_bekommt_mention: meldet addAsParticipants die Person"
          + " nicht zurueck (anderer Aufruf war schneller), ist sie aber inzwischen WRITE-Mitglied,"
          + " bekommt sie die MENTION und wird gebumpt")
  void mention_gleichzeitig_aufgenommener_bekommt_mention() {
    final Long taskId = 155L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));
    // Erste Pruefung (vor der Aufnahme): kein Mitglied; zweite (danach): Mitglied.
    when(membershipGuard.isWriteMember(eq(taskId), eq(BOB_UUID), eq(TENANT_ID)))
        .thenReturn(false, true);
    when(assigneeService.mayAddParticipants()).thenReturn(true);
    when(assigneeService.addAsParticipants(eq(taskId), eq(TENANT_ID), any())).thenReturn(List.of());

    service.createComment(taskUuid, new CommentCreateInput("Hallo.", null, List.of(BOB_UUID), List.of()));

    verifyMention(BOB_UUID, taskId);
    verify(inboxGenerationService).bump(TENANT_ID, BOB_UUID);
  }

  @Test
  @DisplayName(
      "mention_nicht_aufgenommener_bleibt_ohne_mention: wird die Person weder aufgenommen noch ist"
          + " sie danach Mitglied (etwa TEAM-Zeile), gibt es keine MENTION")
  void mention_nicht_aufgenommener_bleibt_ohne_mention() {
    final Long taskId = 156L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));
    when(membershipGuard.isWriteMember(eq(taskId), eq(BOB_UUID), eq(TENANT_ID))).thenReturn(false);
    when(assigneeService.mayAddParticipants()).thenReturn(true);
    when(assigneeService.addAsParticipants(eq(taskId), eq(TENANT_ID), any())).thenReturn(List.of());

    service.createComment(taskUuid, new CommentCreateInput("Hallo.", null, List.of(BOB_UUID), List.of()));

    verify(notificationService, never())
        .emit(eq(BOB_UUID), any(), any(), any(), any(), any(), any());
    verify(inboxGenerationService, never()).bump(TENANT_ID, BOB_UUID);
  }

  @Test
  @DisplayName(
      "mention_doppelte_ref_wird_einmal_aufgenommen_und_benachrichtigt: [bob, bob] -> eine"
          + " Aufnahme, eine MENTION, ein Bump")
  void mention_doppelte_ref_wird_einmal_aufgenommen_und_benachrichtigt() {
    final Long taskId = 157L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));
    when(membershipGuard.isWriteMember(eq(taskId), eq(BOB_UUID), eq(TENANT_ID))).thenReturn(false);
    when(assigneeService.mayAddParticipants()).thenReturn(true);
    when(assigneeService.addAsParticipants(eq(taskId), eq(TENANT_ID), any()))
        .thenReturn(List.of(BOB_UUID));

    service.createComment(
        taskUuid, new CommentCreateInput("Hallo.", null, List.of(BOB_UUID, BOB_UUID), List.of()));

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> added = ArgumentCaptor.forClass(Collection.class);
    verify(assigneeService, times(1)).addAsParticipants(eq(taskId), eq(TENANT_ID), added.capture());
    assertThat(added.getValue()).containsExactly(BOB_UUID);
    verify(notificationService, times(1))
        .emit(
            eq(BOB_UUID),
            eq(taskId),
            eq(NotificationTypeCodes.MENTION),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(inboxGenerationService, times(1)).bump(TENANT_ID, BOB_UUID);
  }

  // ===========================================================================================
  // Intake #152 B2: Kommentar und Anhaenge in einer Transaktion
  // ===========================================================================================

  private static final Long ATT_TASK_ID = 152L;
  private static final Long COMMENT_ID = 15_201L;
  private static final UUID COMMENT_UUID = UUID.randomUUID();

  @Test
  @DisplayName(
      "#152 Anhaenge nach dem Kommentar-Insert und vor jeder Benachrichtigung gespeichert:"
          + " TASK-save -> Kommentar-save -> store(task, Kommentar-ID, Akteur, Dateien) -> emit")
  void i152_attachments_stored_after_comment_insert_before_notifications() {
    final Task task = stubAttachmentTask();
    stubWriterStoresAll();
    final PreparedAttachment prepared = prepared("a.pdf");

    service.createComment(task.getUuid(), input("Siehe Anhang"), List.of(prepared));

    final InOrder o =
        inOrder(taskRepository, commentRepository, attachmentWriter, notificationService);
    o.verify(taskRepository).save(task);
    o.verify(commentRepository).save(any(TaskComment.class));
    o.verify(attachmentWriter).store(task, COMMENT_ID, ACTOR_REF, List.of(prepared));
    o.verify(notificationService, atLeastOnce())
        .emit(any(), any(), any(), any(), any(), any(), any());
    verify(attachmentWriter, times(1)).store(any(), any(), any(), any());
    // Benachrichtigungen unveraendert wie beim reinen Kommentar: der Assignee bekommt COMMENT.
    verify(notificationService, times(1))
        .emit(
            eq(ALICE_UUID),
            eq(ATT_TASK_ID),
            eq(NotificationTypeCodes.COMMENT),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  @Test
  @DisplayName(
      "#152 JSON-Variante (2 Parameter) und leere Liste speichern keine Anhaenge; Antwort mit"
          + " attachments = []")
  void i152_json_variant_stores_no_attachments() {
    final Task task = stubAttachmentTask();

    final CommentModel json = service.createComment(task.getUuid(), input("Nur Text"));
    final CommentModel empty =
        service.createComment(task.getUuid(), input("Leere Liste"), List.of());

    verifyNoInteractions(attachmentWriter);
    assertThat(json.attachments()).isNotNull().isEmpty();
    assertThat(empty.attachments()).isNotNull().isEmpty();
  }

  @Test
  @DisplayName(
      "#152 attachments = null wird am Eingang abgelehnt (NullPointerException mit deutscher"
          + " Meldung), vor jedem Zugriff auf Benutzer, Aufgabe oder Speicher")
  void i152_null_attachments_rejected_at_entry() {
    final Task task = stubAttachmentTask();

    assertThatThrownBy(() -> service.createComment(task.getUuid(), input("Text"), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Anhänge");

    verifyNoInteractions(
        attachmentWriter, notificationService, inboxGenerationService, inboxSignalService);
    verify(taskRepository, never()).save(any());
    verify(commentRepository, never()).save(any(TaskComment.class));
  }

  @Test
  @DisplayName(
      "#152 Schreibfehler beim Speichern der Anhaenge wird durchgereicht: keine Benachrichtigung,"
          + " kein Bump, kein Signal (die Transaktion rollt zurueck)")
  void i152_writer_failure_propagates_without_notifications() {
    final Task task = stubAttachmentTask();
    when(attachmentWriter.store(any(), any(), any(), any()))
        .thenThrow(new UncheckedIOException(new IOException("voll")));

    assertThatThrownBy(
            () ->
                service.createComment(
                    task.getUuid(), input("Siehe Anhang"), List.of(prepared("a.pdf"))))
        .isInstanceOf(UncheckedIOException.class);

    verifyNoInteractions(notificationService, inboxGenerationService, inboxSignalService);
  }

  @Test
  @DisplayName(
      "#152 Antwort enthaelt die gespeicherten Anhaenge in Eingabereihenfolge (nicht entfernbar,"
          + " Links auf dem Aufgaben-Pfad)")
  void i152_response_contains_attachment_models() {
    final Task task = stubAttachmentTask();
    stubWriterStoresAll();

    final CommentModel model =
        service.createComment(
            task.getUuid(), input("Siehe Anhang"), List.of(prepared("a.pdf"), prepared("b.pdf")));

    assertThat(model.id()).isEqualTo(COMMENT_UUID);
    assertThat(model.text()).isEqualTo("Siehe Anhang");
    assertThat(model.isNew()).isFalse();
    assertThat(model.attachments())
        .extracting(AttachmentModel::fileName)
        .containsExactly("a.pdf", "b.pdf");
    for (final AttachmentModel attachment : model.attachments()) {
      assertThat(attachment.removable()).as("Kommentar-Anhaenge nie entfernbar").isFalse();
      assertThat(attachment.uploadedById()).isEqualTo(ACTOR_REF);
      final String expected =
          "/task-management/api/v1/tasks/"
              + task.getUuid()
              + "/attachments/"
              + attachment.id()
              + "/content";
      assertThat(attachment.links().content()).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName(
      "#152 Seam onAfterAttachmentsStoredForTest(Kommentar-ID) laeuft nach store und vor der"
          + " ersten Benachrichtigung; ohne Anhaenge laeuft er nicht")
  void i152_seam_after_store_before_notifications() {
    final Task task = stubAttachmentTask();
    final List<String> events = new ArrayList<>();
    when(attachmentWriter.store(any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              events.add("store");
              return storedFrom(inv.getArgument(3));
            });
    when(notificationService.emit(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              events.add("emit");
              return UUID.randomUUID();
            });
    final CommentService seamService = serviceWithSeam(events);

    seamService.createComment(task.getUuid(), input("Siehe Anhang"), List.of(prepared("a.pdf")));

    assertThat(events).containsExactly("store", "seam:" + COMMENT_ID, "emit");

    events.clear();
    seamService.createComment(task.getUuid(), input("Ohne Anhang"));
    assertThat(events).as("ohne Anhaenge kein store und kein Seam").containsExactly("emit");
  }

  @Test
  @DisplayName(
      "#152 Sicherung: mehr als maxPerTarget (20) Anhaenge je Kommentar -> Abbruch ohne store und"
          + " ohne Benachrichtigung; genau 20 werden gespeichert")
  void i152_more_than_max_per_target_rejected_without_store() {
    final Task task = stubAttachmentTask();
    stubWriterStoresAll();
    final PreparedAttachment prepared = prepared("a.pdf");

    assertThatThrownBy(
            () ->
                service.createComment(
                    task.getUuid(), input("Zu viele"), Collections.nCopies(21, prepared)))
        .isInstanceOf(RuntimeException.class);
    verifyNoInteractions(attachmentWriter, notificationService);

    final CommentModel model =
        service.createComment(task.getUuid(), input("Genau 20"), Collections.nCopies(20, prepared));
    assertThat(model.attachments()).hasSize(20);
  }

  /**
   * Stubt eine offene Aufgabe mit einem Leser und einem Assignee (ALICE); der Kommentar-save
   * vergibt {@link #COMMENT_ID} und {@link #COMMENT_UUID}.
   */
  private Task stubAttachmentTask() {
    final Task task = taskWithId(ATT_TASK_ID, UUID.randomUUID(), 0);
    when(taskRepository.findByUuid(task.getUuid().toString())).thenReturn(Optional.of(task));
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
              final TaskComment c = inv.getArgument(0);
              c.setId(COMMENT_ID);
              c.setUuid(COMMENT_UUID);
              c.setCreatedAt(LocalDateTime.now());
              return c;
            });
    when(userStateQueryRepository.findActiveUserRefsByTaskId(ATT_TASK_ID))
        .thenReturn(List.of(stateWithUserRef(ALICE_UUID, ATT_TASK_ID)));
    when(participantQueryRepository.findActiveAssigneesByTaskId(ATT_TASK_ID))
        .thenReturn(List.of(assigneeWith(ALICE_UUID)));
    return task;
  }

  /** Der gemockte Writer gibt je vorbereiteter Datei einen gespeicherten Anhang zurueck. */
  private void stubWriterStoresAll() {
    when(attachmentWriter.store(any(), any(), any(), any()))
        .thenAnswer(inv -> storedFrom(inv.getArgument(3)));
  }

  /** Gespeicherte Anhaenge zu den vorbereiteten Dateien, in Eingabereihenfolge. */
  private static List<TaskAttachment> storedFrom(final List<PreparedAttachment> prepared) {
    final List<TaskAttachment> stored = new ArrayList<>();
    long id = 900L;
    for (final PreparedAttachment p : prepared) {
      final TaskAttachment a = new TaskAttachment();
      a.setId(id);
      a.setUuid(UUID.randomUUID());
      a.setTenantId(TENANT_ID);
      a.setEntityState(io.keel.framework.api.model.EntityState.ACTIVE);
      a.setCreatedAt(LocalDateTime.now());
      a.setTaskId(ATT_TASK_ID);
      a.setCommentId(COMMENT_ID);
      a.setFileName(p.fileName());
      a.setMimeType(p.type().mimeType());
      a.setFileExtension(p.extension());
      a.setSizeBytes(p.sizeBytes());
      a.setSha256(p.sha256Hex());
      a.setStorageKey(TENANT_ID + "/0/" + id + "." + p.extension());
      a.setThumbnailPresent(p.hasThumbnail());
      a.setUploadedByRef(ACTOR_REF);
      stored.add(a);
      id++;
    }
    return stored;
  }

  /** Gepruefte PDF-Datei ohne Vorschaubild. */
  private static PreparedAttachment prepared(final String fileName) {
    final byte[] content = "%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1);
    return new PreparedAttachment(
        fileName,
        AttachmentFileType.PDF,
        "pdf",
        content.length,
        "0123456789abcdef".repeat(4),
        new ByteArrayResource(content),
        null);
  }

  private static CommentCreateInput input(final String text) {
    return new CommentCreateInput(text, null, List.of(), List.of());
  }

  /** CommentService wie im Setup, aber mit Seam, der {@code seam:<Kommentar-ID>} protokolliert. */
  private CommentService serviceWithSeam(final List<String> events) {
    return new CommentService(
        taskRepository,
        commentRepository,
        userStateQueryRepository,
        participantQueryRepository,
        notificationService,
        inboxSignalService,
        inboxGenerationService,
        userReadRepository,
        userAccessor,
        new AuditTimeConverter("Europe/Berlin"),
        membershipGuard,
        new NotificationMuteFilter(userStateQueryRepository),
        assigneeService,
        attachmentWriter,
        attachmentModelMapper,
        attachmentProperties,
        divisionMentionResolver) {
      @Override
      protected void onAfterAttachmentsStoredForTest(final Long commentId) {
        events.add("seam:" + commentId);
      }
    };
  }

  /** Stubt eine offene Aufgabe ohne Leser, ohne Assignees und mit funktionierenden Saves. */
  private UUID stubOpenTask(final Long taskId) {
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid, 0)));
    stubSave();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    return taskUuid;
  }

  /** Genau eine MENTION-Benachrichtigung an die Person, ausgeloest vom Akteur. */
  private void verifyMention(final UUID recipient, final Long taskId) {
    verify(notificationService, times(1))
        .emit(
            eq(recipient),
            eq(taskId),
            eq(NotificationTypeCodes.MENTION),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  // ---- Intake #190: Erwaehnung von Abteilungen (divisionMentions) -----------------------------
  //
  // Angenommene Signaturen: CommentService erhaelt den DivisionMentionResolver als letzten
  // Konstruktor-Parameter; CommentCreateInput(text, parentCommentId, mentions, divisionMentions);
  // Set<UUID> DivisionMentionResolver#resolve(Collection<UUID>). Der Mock liefert ohne Stub eine
  // leere Menge, daher verhalten sich die Tests oben wie ohne Abteilungen.

  @Test
  @DisplayName(
      "i190: Personen der erwaehnten Abteilungen werden mit mentions vereinigt und laufen in EINER"
          + " Aufloesung ueber findActiveByUuids; jede Person bekommt genau eine MENTION")
  void i190_division_members_united_with_mentions_before_dedup() {
    final Long taskId = 190L;
    final UUID taskUuid = stubOpenTask(taskId);
    final UUID division = UUID.randomUUID();
    when(divisionMentionResolver.resolve(any())).thenReturn(Set.of(ALICE_UUID, BOB_UUID));
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(ALICE_UUID), userWith(BOB_UUID)));

    service.createComment(
        taskUuid,
        new CommentCreateInput("Hallo Abteilung.", null, List.of(ALICE_UUID), List.of(division)));

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> divisions = ArgumentCaptor.forClass(Collection.class);
    verify(divisionMentionResolver).resolve(divisions.capture());
    assertThat(divisions.getValue()).containsExactly(division);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> lookedUp = ArgumentCaptor.forClass(Collection.class);
    verify(userReadRepository, times(1)).findActiveByUuids(lookedUp.capture());
    assertThat(lookedUp.getValue())
        .as("Vereinigung ohne Duplikat (alice steht in mentions und in der Abteilung)")
        .containsExactlyInAnyOrder(ALICE_UUID, BOB_UUID);

    verifyMention(ALICE_UUID, taskId);
    verifyMention(BOB_UUID, taskId);
  }

  @Test
  @DisplayName(
      "i190: Lock-Reihenfolge - Abteilungsaufloesung und Benutzer-Aufloesung laufen VOR"
          + " taskRepository.save")
  void i190_division_resolution_runs_before_task_save() {
    final Long taskId = 191L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(divisionMentionResolver.resolve(any())).thenReturn(Set.of(BOB_UUID));
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));

    service.createComment(
        taskUuid,
        new CommentCreateInput("Lock-Reihenfolge.", null, List.of(), List.of(UUID.randomUUID())));

    final InOrder inOrder = inOrder(divisionMentionResolver, userReadRepository, taskRepository);
    inOrder.verify(divisionMentionResolver).resolve(any());
    inOrder.verify(userReadRepository).findActiveByUuids(any());
    inOrder.verify(taskRepository).save(any(Task.class));
  }

  @Test
  @DisplayName(
      "i190: wirft der Resolver (unbekannte Abteilung oder Grenze), wird nichts geschrieben: kein"
          + " TASK-save, kein Kommentar, keine Aufnahme, keine Benachrichtigung, kein Bump")
  void i190_division_resolver_rejection_writes_nothing() {
    final Long taskId = 192L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(divisionMentionResolver.resolve(any()))
        .thenThrow(new EntityValidationException("Unbekannte oder nicht aktive Abteilung."));
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));
    when(assigneeService.mayAddParticipants()).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.createComment(
                    taskUuid,
                    new CommentCreateInput(
                        "Unbekannt.", null, List.of(BOB_UUID), List.of(UUID.randomUUID()))))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("Unbekannte oder nicht aktive Abteilung.");

    verify(taskRepository, never()).save(any(Task.class));
    verify(commentRepository, never()).save(any(TaskComment.class));
    verify(assigneeService, never()).addAsParticipants(anyLong(), anyLong(), any());
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxGenerationService, never()).bumpSorted(anyLong(), any());
    verify(inboxSignalService, never()).invalidate(anyLong(), any());
  }

  @Test
  @DisplayName(
      "i190: Abteilungsmitglied ohne Mitgliedschaft wird wie eine erwaehnte Person aufgenommen"
          + " (nur mit Hinzufuegen-Recht) und bekommt nur die MENTION")
  void i190_division_member_non_member_added_like_mention() {
    final Long taskId = 193L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(divisionMentionResolver.resolve(any())).thenReturn(Set.of(BOB_UUID));
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(BOB_UUID)));
    when(membershipGuard.isWriteMember(eq(taskId), eq(BOB_UUID), eq(TENANT_ID))).thenReturn(false);
    when(assigneeService.mayAddParticipants()).thenReturn(true);
    when(assigneeService.addAsParticipants(eq(taskId), eq(TENANT_ID), any()))
        .thenReturn(List.of(BOB_UUID));

    service.createComment(
        taskUuid,
        new CommentCreateInput("Nur Abteilung.", null, List.of(), List.of(UUID.randomUUID())));

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> added = ArgumentCaptor.forClass(Collection.class);
    verify(assigneeService).addAsParticipants(eq(taskId), eq(TENANT_ID), added.capture());
    assertThat(added.getValue()).containsExactly(BOB_UUID);
    verifyMention(BOB_UUID, taskId);
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.PARTICIPANT), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "i190: Akteur und REPLY-Empfaenger in der Abteilung -> Akteur nichts, Eltern-Autorin nur"
          + " REPLY, das uebrige Mitglied MENTION")
  void i190_actor_and_reply_recipient_in_division_not_mentioned() {
    final Long taskId = 194L;
    final UUID taskUuid = stubOpenTask(taskId);
    final UUID parentUuid = UUID.randomUUID();
    when(commentRepository.findByUuid(parentUuid.toString()))
        .thenReturn(Optional.of(commentWithId(19L, taskId, ALICE_UUID, parentUuid)));
    when(divisionMentionResolver.resolve(any()))
        .thenReturn(Set.of(ACTOR_REF, ALICE_UUID, BOB_UUID));
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(ACTOR_REF), userWith(ALICE_UUID), userWith(BOB_UUID)));

    service.createComment(
        taskUuid,
        new CommentCreateInput("Antwort.", parentUuid, List.of(), List.of(UUID.randomUUID())));

    verify(notificationService, never())
        .emit(eq(ACTOR_REF), any(), any(), any(), any(), any(), any());
    verify(notificationService, times(1))
        .emit(eq(ALICE_UUID), eq(taskId), eq(NotificationTypeCodes.REPLY), any(), any(), any(),
            any());
    verify(notificationService, never())
        .emit(eq(ALICE_UUID), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
    verifyMention(BOB_UUID, taskId);
  }

  @Test
  @DisplayName(
      "i190: Abteilung ohne aktive Benutzer (findActiveByUuids leer) -> Kommentar wird angelegt,"
          + " keine MENTION, keine Aufnahme")
  void i190_division_members_without_active_user_are_dropped() {
    final Long taskId = 195L;
    final UUID taskUuid = stubOpenTask(taskId);
    when(divisionMentionResolver.resolve(any())).thenReturn(Set.of(GHOST_UUID));
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of());
    when(assigneeService.mayAddParticipants()).thenReturn(true);

    final CommentModel model =
        service.createComment(
            taskUuid,
            new CommentCreateInput("Geister.", null, List.of(), List.of(UUID.randomUUID())));

    assertThat(model).isNotNull();
    verify(commentRepository).save(any(TaskComment.class));
    verify(assigneeService, never()).addAsParticipants(anyLong(), anyLong(), any());
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "i190: ohne mentions und ohne divisionMentions bleibt der Benutzer-Lookup aus (wie bisher)")
  void i190_without_any_mentions_no_user_lookup() {
    final Long taskId = 196L;
    final UUID taskUuid = stubOpenTask(taskId);

    service.createComment(taskUuid, new CommentCreateInput("Ohne.", null, null, null));

    verify(userReadRepository, never()).findActiveByUuids(any());
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.MENTION), any(), any(), any(), any());
  }

  // ---- Hilfsmethoden ---------------------------------------------------------------------------

  /** Standard-Stubs fuer die save-Methoden (Comment + Task), die fast jeder Test braucht. */
  private void stubSave() {
    when(commentRepository.save(any(TaskComment.class)))
        .thenAnswer(
            inv -> {
              final TaskComment c = inv.getArgument(0);
              c.setUuid(UUID.randomUUID());
              c.setCreatedAt(LocalDateTime.now());
              return c;
            });
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  /** Aktiver PERSON-Assignee (nur participantRef wird im COMMENT-Hook gebraucht). */
  private static TaskParticipant assigneeWith(final UUID ref) {
    final TaskParticipant p = new TaskParticipant();
    p.setParticipantRef(ref);
    p.setAssigneeFlag(true);
    return p;
  }

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

  /** Aktiver, stummgeschalteter UserState (Intake #158). */
  private static TaskUserState mutedState(final UUID userRef, final Long taskId) {
    final TaskUserState state = stateWithUserRef(userRef, taskId);
    state.setMuted(true);
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
