package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.inbox.model.InboxSnapshotModel;
import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.notification.NotificationQueryService;
import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.HandoverTargetRow;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagUuidRow;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.tag.TagCatalogService;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.validation.EntityValidationException;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link InboxSnapshotService} (alle Abhaengigkeiten gemockt, kein DB).
 *
 * <p>Deckt zusaetzlich zu den asOf-/Keyset-Invarianten die Snapshot-Rejection (Finding 2 / Option B):
 * 4-Token-Cursor mit Generation, check-AFTER-410 auf der Folgeseite und den Generation-Bracket mit
 * Retry/Exhaustion auf First-Page/Bootstrap (je Einstiegspunkt).
 */
class InboxSnapshotServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER_REF = UUID.randomUUID();
  private static final long OPEN_ID = 1L;
  private static final long DONE_ID = 2L;
  private static final long CANCELLED_ID = 3L;
  private static final int DEFAULT_LIMIT = 50;
  private static final UUID MAX_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

  private TaskKeysetQueryRepository taskQueryRepository;
  private TaskParticipantQueryRepository participantQueryRepository;
  private TaskTagAssignmentQueryRepository tagAssignmentQueryRepository;
  private TaskUserStateQueryRepository userStateQueryRepository;
  private TaskHandoverQueryRepository handoverQueryRepository;
  private InboxEnrichmentAssembler enrichmentAssembler;
  private NotificationQueryService notificationQueryService;
  private TagCatalogService tagCatalogService;
  private UserReadRepository userReadRepository;
  private DictionaryCodeResolver resolver;
  private AuditTimeConverter auditTimeConverter;
  private InboxGenerationService inboxGenerationService;
  private InboxSnapshotService service;

  @BeforeEach
  void setUp() {
    taskQueryRepository = mock(TaskKeysetQueryRepository.class);
    participantQueryRepository = mock(TaskParticipantQueryRepository.class);
    tagAssignmentQueryRepository = mock(TaskTagAssignmentQueryRepository.class);
    userStateQueryRepository = mock(TaskUserStateQueryRepository.class);
    handoverQueryRepository = mock(TaskHandoverQueryRepository.class);
    // Echter Assembler (reine Logik, keine DB) - in P2 isoliert getestet.
    enrichmentAssembler = new InboxEnrichmentAssembler();
    notificationQueryService = mock(NotificationQueryService.class);
    tagCatalogService = mock(TagCatalogService.class);
    userReadRepository = mock(UserReadRepository.class);
    resolver = mock(DictionaryCodeResolver.class);
    auditTimeConverter = new AuditTimeConverter("Europe/Berlin");
    inboxGenerationService = mock(InboxGenerationService.class);

    service =
        new InboxSnapshotService(
            taskQueryRepository,
            participantQueryRepository,
            tagAssignmentQueryRepository,
            userStateQueryRepository,
            handoverQueryRepository,
            enrichmentAssembler,
            notificationQueryService,
            tagCatalogService,
            userReadRepository,
            resolver,
            auditTimeConverter,
            inboxGenerationService);

    when(resolver.resolveTaskStatusOpen()).thenReturn(OPEN_ID);
    when(resolver.resolveTaskStatusDone()).thenReturn(DONE_ID);
    when(resolver.resolveTaskStatusCancelled()).thenReturn(CANCELLED_ID);
    when(resolver.resolveTaskStatusCode(any())).thenReturn("OPEN");
    when(resolver.resolveTaskPriorityCode(any())).thenReturn("NORMAL");
    when(notificationQueryService.listFor(any(), anyLong(), any(), any(), anyInt()))
        .thenReturn(new NotificationPage(List.of(), null, false));
    when(tagCatalogService.list()).thenReturn(List.of());
    // Default: Anreicherungs-Batch-Querys liefern leer (pro Aufgabe EMPTY-Anreicherung).
    when(participantQueryRepository.findActiveByTaskIds(any())).thenReturn(List.of());
    when(tagAssignmentQueryRepository.findActiveTagUuidsByTaskIds(any())).thenReturn(List.of());
    when(userStateQueryRepository.findByTaskIdsAndUserRef(any(), any())).thenReturn(List.of());
    when(handoverQueryRepository.findVisibleHandoverTargets(any(), any(), any()))
        .thenReturn(List.of());
    // Default: stabile Generation 0 (First-Page-Bracket stabil, keine Ablehnung).
    when(inboxGenerationService.currentGeneration(any())).thenReturn(0L);
  }

  // ---- Kern: snapshot_asOf_user_scoped_kein_sequence ------------------------------------------

  @Test
  @DisplayName("snapshot_asOf_user_scoped_kein_sequence")
  void snapshot_asOf_user_scoped_kein_sequence() {
    final List<Task> pageTasks = buildTasks(2);
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(LocalDateTime.class), any(LocalDateTime.class),
            eq(MAX_UUID), eq(DEFAULT_LIMIT + 1)))
        .thenReturn(pageTasks);
    when(userReadRepository.findActiveParticipantsForTasks(any()))
        .thenReturn(List.of(buildUser("kim@brasi.de", "Kim Test")));
    when(taskQueryRepository.countArchiveAll(eq(USER_REF), eq(DONE_ID), eq(CANCELLED_ID), any()))
        .thenReturn(5L);
    when(taskQueryRepository.countArchiveDone(eq(USER_REF), eq(DONE_ID), eq(CANCELLED_ID), any()))
        .thenReturn(3L);
    when(taskQueryRepository.countArchiveLeft(eq(USER_REF), any())).thenReturn(2L);

    final InboxSnapshotModel snapshot = service.loadSnapshot(USER_REF, TENANT_ID, true);

    assertThat(snapshot.asOf()).isNotNull();
    assertThat(snapshot.asOf()).isAfter(Instant.now().minusSeconds(120));
    assertThat(snapshot.asOf()).isBefore(Instant.now().plusSeconds(120));
    assertThat(snapshot.tasks().asOf()).isEqualTo(snapshot.asOf());

    verify(taskQueryRepository)
        .findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(LocalDateTime.class), any(LocalDateTime.class),
            eq(MAX_UUID), eq(DEFAULT_LIMIT + 1));

    assertThat(snapshot.tasks().items()).hasSize(2);
    assertThat(snapshot.tasks().hasMore()).isFalse();
    assertThat(snapshot.participantSummaries()).hasSize(1);
    assertThat(snapshot.archiveTotals().all()).isEqualTo(5L);
    assertThat(snapshot.archiveTotals().done()).isEqualTo(3L);
    assertThat(snapshot.archiveTotals().left()).isEqualTo(2L);

    final Instant asOfInstant = snapshot.asOf();
    final LocalDateTime asOfLdt = LocalDateTime.ofInstant(asOfInstant, auditTimeConverter.zone());
    verify(taskQueryRepository).countArchiveAll(USER_REF, DONE_ID, CANCELLED_ID, asOfLdt);

    final List<String> components =
        Arrays.stream(InboxSnapshotModel.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertThat(components).doesNotContain("sequence", "seq", "delta", "nextSequence");
  }

  // ---- leere aktive Seite: keine IN()-Leermenge, participantSummaries leer --------------------

  @Test
  @DisplayName("snapshot_leere_aktive_seite_participantSummaries_leer_ohne_IN_leer")
  void snapshot_leere_aktive_seite_participantSummaries_leer_ohne_in_leer() {
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(DEFAULT_LIMIT + 1)))
        .thenReturn(List.of());
    when(taskQueryRepository.countArchiveAll(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveDone(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveLeft(any(), any())).thenReturn(0L);

    final InboxSnapshotModel snapshot = service.loadSnapshot(USER_REF, TENANT_ID, true);

    assertThat(snapshot.tasks().items()).isEmpty();
    assertThat(snapshot.participantSummaries()).isEmpty();
    verify(userReadRepository, never()).findActiveParticipantsForTasks(any());
  }

  // ---- Authz: NOTIFICATION_READ gatet die Snapshot-Notifications ------------------------------

  @Test
  @DisplayName("snapshot_ohne_notification_read_liefert_leere_notifications")
  void snapshot_ohne_notification_read_liefert_leere_notifications() {
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(DEFAULT_LIMIT + 1)))
        .thenReturn(buildTasks(1));
    when(taskQueryRepository.countArchiveAll(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveDone(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveLeft(any(), any())).thenReturn(0L);
    when(userReadRepository.findActiveParticipantsForTasks(any())).thenReturn(List.of());

    final InboxSnapshotModel snapshot = service.loadSnapshot(USER_REF, TENANT_ID, false);

    // Fehlendes NOTIFICATION_READ -> leere Notification-Seite (contract-stabil, nicht null).
    assertThat(snapshot.notifications().items()).isEmpty();
    assertThat(snapshot.notifications().nextCursor()).isNull();
    assertThat(snapshot.notifications().hasMore()).isFalse();
    // fail-closed: die Notification-Quelle wird NIE angefragt (kein DB-Leak ohne Recht).
    verify(notificationQueryService, never()).listFor(any(), anyLong(), any(), any(), anyInt());
  }

  @Test
  @DisplayName("snapshot_mit_notification_read_liefert_notifications_durchgereicht")
  void snapshot_mit_notification_read_liefert_notifications() {
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(DEFAULT_LIMIT + 1)))
        .thenReturn(buildTasks(1));
    when(taskQueryRepository.countArchiveAll(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveDone(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveLeft(any(), any())).thenReturn(0L);
    when(userReadRepository.findActiveParticipantsForTasks(any())).thenReturn(List.of());
    // Distinktive Seite (all, cursor=null) -> beweist Pass-Through ohne NotificationModel zu bauen.
    final NotificationPage distinct = new NotificationPage(List.of(), "cursor-xyz", true);
    when(notificationQueryService.listFor(eq(USER_REF), eq(TENANT_ID), eq("all"), any(), anyInt()))
        .thenReturn(distinct);

    final InboxSnapshotModel snapshot = service.loadSnapshot(USER_REF, TENANT_ID, true);

    assertThat(snapshot.notifications()).isSameAs(distinct);
    verify(notificationQueryService, times(1))
        .listFor(eq(USER_REF), eq(TENANT_ID), eq("all"), any(), anyInt());
  }

  // ---- aktive Fortsetzung: hasMore + nextCursor (4-Token) -------------------------------------

  @Test
  @DisplayName("loadActivePage_first_page_hasMore_true_und_nextCursor_4_token")
  void loadActivePage_first_page_hasMore_true_und_nextCursor_gesetzt() {
    final int limit = 2;
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(limit + 1)))
        .thenReturn(buildTasks(3));

    final KeysetPage<TaskSummaryModel> page = service.loadActivePage(USER_REF, null, limit);

    assertThat(page.items()).hasSize(2);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursor()).isNotBlank();
    assertThat(page.asOf()).isNotNull();
    // nextCursor ist ein 4-Token-Cursor (asOf, lastActivityAt, externalId, generation)
    final List<String> tokens = KeysetCursor.decode(page.nextCursor(), 4);
    assertThat(tokens).hasSize(4);
    assertThat(tokens.get(3)).as("4. Token = Generation (stabil 0)").isEqualTo("0");
  }

  @Test
  @DisplayName("loadActivePage_cursor_dekodiert_fenster_und_reicht_es_durch")
  void loadActivePage_cursor_dekodiert_fenster_und_reicht_es_durch() {
    final int limit = 5;
    final LocalDateTime asOf = LocalDateTime.of(2026, 10, 17, 10, 0, 0);
    final LocalDateTime lastActivity = LocalDateTime.of(2026, 10, 16, 9, 30, 0);
    final UUID lastExternal = UUID.randomUUID();
    final String cursor =
        KeysetCursor.encode(
            List.of(asOf.toString(), lastActivity.toString(), lastExternal.toString(), "0"));

    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), eq(asOf), eq(lastActivity), eq(lastExternal), eq(limit + 1)))
        .thenReturn(buildTasks(1));

    final KeysetPage<TaskSummaryModel> page = service.loadActivePage(USER_REF, cursor, limit);

    assertThat(page.items()).hasSize(1);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
    assertThat(page.asOf()).isEqualTo(auditTimeConverter.toInstant(asOf));
    verify(taskQueryRepository)
        .findActivePage(USER_REF, OPEN_ID, asOf, lastActivity, lastExternal, limit + 1);
  }

  @Test
  @DisplayName("loadActivePage_malformed_cursor_falsche_tokenzahl_wirft_400")
  void loadActivePage_malformed_cursor_falsche_tokenzahl_wirft_400() {
    // 3 Token statt 4 (alter Cursor) -> KeysetCursor.decode(cursor,4) -> EntityValidationException
    final String badCursor =
        KeysetCursor.encode(
            List.of(
                LocalDateTime.now().toString(),
                LocalDateTime.now().toString(),
                UUID.randomUUID().toString()));
    assertThatThrownBy(() -> service.loadActivePage(USER_REF, badCursor, 10))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("loadActivePage_cursor_nicht_parsebare_tokens_wirft_400")
  void loadActivePage_cursor_nicht_parsebare_tokens_wirft_400() {
    // 4 Token, aber keine parsebaren Werte (Datum/UUID/Zahl) -> 400
    final String badCursor =
        KeysetCursor.encode(List.of("KEIN_DATUM", "KEIN_DATUM", "KEINE_UUID", "KEINE_ZAHL"));
    assertThatThrownBy(() -> service.loadActivePage(USER_REF, badCursor, 10))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Finding 2 / Option B: Folgeseite check-AFTER -> 410 ------------------------------------

  @Test
  @DisplayName("loadActivePage_folge_cursor_generation_mismatch_wirft_410")
  void loadActivePage_folge_cursor_generation_mismatch_wirft_410() {
    final int limit = 5;
    final LocalDateTime asOf = LocalDateTime.of(2026, 10, 17, 10, 0, 0);
    final LocalDateTime lastActivity = LocalDateTime.of(2026, 10, 16, 9, 30, 0);
    final UUID lastExternal = UUID.randomUUID();
    // Cursor traegt Generation 5; aktuelle Generation ist inzwischen 7 -> Mismatch -> 410.
    final String cursor =
        KeysetCursor.encode(
            List.of(asOf.toString(), lastActivity.toString(), lastExternal.toString(), "5"));
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(7L);
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), eq(asOf), eq(lastActivity), eq(lastExternal), eq(limit + 1)))
        .thenReturn(buildTasks(1));

    assertThatThrownBy(() -> service.loadActivePage(USER_REF, cursor, limit))
        .isInstanceOf(SnapshotExpiredException.class);
  }

  @Test
  @DisplayName("loadActivePage_folge_cursor_generation_gleich_liefert_seite")
  void loadActivePage_folge_cursor_generation_gleich_liefert_seite() {
    final int limit = 2;
    final LocalDateTime asOf = LocalDateTime.of(2026, 10, 17, 10, 0, 0);
    final LocalDateTime lastActivity = LocalDateTime.of(2026, 10, 16, 9, 30, 0);
    final UUID lastExternal = UUID.randomUUID();
    final String cursor =
        KeysetCursor.encode(
            List.of(asOf.toString(), lastActivity.toString(), lastExternal.toString(), "5"));
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(5L);
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), eq(asOf), eq(lastActivity), eq(lastExternal), eq(limit + 1)))
        .thenReturn(buildTasks(3));

    final KeysetPage<TaskSummaryModel> page = service.loadActivePage(USER_REF, cursor, limit);

    assertThat(page.items()).hasSize(2);
    assertThat(page.hasMore()).isTrue();
    // nextCursor traegt die unveraenderte Snapshot-Generation (5) weiter.
    assertThat(KeysetCursor.decode(page.nextCursor(), 4).get(3)).isEqualTo("5");
  }

  @Test
  @DisplayName("loadArchivePage_folge_cursor_generation_mismatch_wirft_410")
  void loadArchivePage_folge_cursor_generation_mismatch_wirft_410() {
    final int limit = 5;
    final LocalDateTime asOf = LocalDateTime.of(2026, 10, 17, 10, 0, 0);
    final LocalDateTime lastActivity = LocalDateTime.of(2026, 10, 16, 9, 30, 0);
    final UUID lastExternal = UUID.randomUUID();
    final String cursor =
        KeysetCursor.encode(
            List.of(asOf.toString(), lastActivity.toString(), lastExternal.toString(), "2"));
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(3L);
    when(taskQueryRepository.findArchiveAllPage(
            eq(USER_REF), eq(DONE_ID), eq(CANCELLED_ID), eq(asOf), eq(lastActivity),
            eq(lastExternal), eq(limit + 1)))
        .thenReturn(buildTasks(1));

    assertThatThrownBy(() -> service.loadArchivePage(USER_REF, "all", cursor, limit))
        .isInstanceOf(SnapshotExpiredException.class);
  }

  // ---- Finding 2 / Option B: First-Page Generation-Bracket mit Retry/Exhaustion ---------------

  @Test
  @DisplayName("loadActivePage_first_page_bracket_retry_stabilisiert_ohne_410")
  void loadActivePage_first_page_bracket_retry() {
    final int limit = 2;
    // Versuch 1: g1=0, g2=1 (Bump waehrend Assembly) -> Retry; Versuch 2: g1=1, g2=1 -> stabil.
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(0L, 1L, 1L, 1L);
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(limit + 1)))
        .thenReturn(buildTasks(3));

    final KeysetPage<TaskSummaryModel> page = service.loadActivePage(USER_REF, null, limit);

    assertThat(page.hasMore()).isTrue();
    // Gestempelte Generation = stabiler Wert 1.
    assertThat(KeysetCursor.decode(page.nextCursor(), 4).get(3)).isEqualTo("1");
    // Zwei Versuche -> Seite zweimal abgefragt.
    verify(taskQueryRepository, times(2))
        .findActivePage(eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(limit + 1));
  }

  @Test
  @DisplayName("loadActivePage_first_page_exhaustion_wirft_410")
  void loadActivePage_first_page_exhaustion_wirft_410() {
    // Jeder After-Read weicht ab -> 3 Versuche scheitern -> 410.
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(0L, 1L, 2L, 3L, 4L, 5L);
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), anyInt()))
        .thenReturn(buildTasks(1));

    assertThatThrownBy(() -> service.loadActivePage(USER_REF, null, 10))
        .isInstanceOf(SnapshotExpiredException.class);
    verify(taskQueryRepository, times(3))
        .findActivePage(eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), anyInt());
  }

  @Test
  @DisplayName("loadArchivePage_first_page_bracket_retry_stabilisiert_ohne_410")
  void loadArchivePage_first_page_bracket_retry() {
    final int limit = 2;
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(0L, 4L, 4L, 4L);
    when(taskQueryRepository.findArchiveAllPage(
            eq(USER_REF), eq(DONE_ID), eq(CANCELLED_ID), any(), any(), eq(MAX_UUID), eq(limit + 1)))
        .thenReturn(buildTasks(3));

    final KeysetPage<TaskSummaryModel> page = service.loadArchivePage(USER_REF, "all", null, limit);

    assertThat(page.hasMore()).isTrue();
    assertThat(KeysetCursor.decode(page.nextCursor(), 4).get(3)).isEqualTo("4");
  }

  @Test
  @DisplayName("loadArchivePage_first_page_exhaustion_wirft_410")
  void loadArchivePage_first_page_exhaustion_wirft_410() {
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(0L, 1L, 2L, 3L, 4L, 5L);
    when(taskQueryRepository.findArchiveAllPage(
            eq(USER_REF), eq(DONE_ID), eq(CANCELLED_ID), any(), any(), eq(MAX_UUID), anyInt()))
        .thenReturn(buildTasks(1));

    assertThatThrownBy(() -> service.loadArchivePage(USER_REF, "all", null, 10))
        .isInstanceOf(SnapshotExpiredException.class);
  }

  @Test
  @DisplayName("loadSnapshot_bracket_retry_bump_zwischen_seite_und_counts")
  void loadSnapshot_bracket_retry() {
    // Versuch 1: g1=0, g2=1 (Bump zwischen aktiver Seite und Counts) -> Retry; Versuch 2 stabil (1).
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(0L, 1L, 1L, 1L);
    // > DEFAULT_LIMIT -> hasMore=true, damit der auf der stabilen Generation gestempelte nextCursor
    // tatsaechlich geprueft werden kann (Codex-Auflage 2).
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(DEFAULT_LIMIT + 1)))
        .thenReturn(buildTasks(DEFAULT_LIMIT + 1));
    when(taskQueryRepository.countArchiveAll(any(), any(), any(), any())).thenReturn(1L);
    when(taskQueryRepository.countArchiveDone(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveLeft(any(), any())).thenReturn(1L);
    when(userReadRepository.findActiveParticipantsForTasks(any())).thenReturn(List.of());

    final InboxSnapshotModel snapshot = service.loadSnapshot(USER_REF, TENANT_ID, true);

    assertThat(snapshot.tasks().items()).hasSize(DEFAULT_LIMIT);
    assertThat(snapshot.tasks().hasMore()).isTrue();
    assertThat(snapshot.archiveTotals().all()).isEqualTo(1L);
    // nextCursor traegt die STABILE Generation (1, aus dem erfolgreichen 2. Versuch).
    assertThat(KeysetCursor.decode(snapshot.tasks().nextCursor(), 4).get(3)).isEqualTo("1");
    // Zwei Versuche -> aktive Seite zweimal abgefragt.
    verify(taskQueryRepository, times(2))
        .findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(DEFAULT_LIMIT + 1));
  }

  @Test
  @DisplayName("loadSnapshot_exhaustion_wirft_410")
  void loadSnapshot_exhaustion_wirft_410() {
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(0L, 1L, 2L, 3L, 4L, 5L);
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(DEFAULT_LIMIT + 1)))
        .thenReturn(buildTasks(1));
    when(taskQueryRepository.countArchiveAll(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveDone(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveLeft(any(), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.loadSnapshot(USER_REF, TENANT_ID, true))
        .isInstanceOf(SnapshotExpiredException.class);
  }

  @Test
  @DisplayName("loadSnapshot_participantSummaries_live_ausserhalb_generation_bracket")
  void loadSnapshot_participantSummaries_live_scope() {
    // Stabile requester-Generation -> genau 2 Generation-Reads (g1, g2); participantSummaries
    // (LIVE, nach dem Bracket) loesen KEINE weiteren Generation-Reads aus und kein 410.
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(DEFAULT_LIMIT + 1)))
        .thenReturn(buildTasks(1));
    when(taskQueryRepository.countArchiveAll(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveDone(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveLeft(any(), any())).thenReturn(0L);
    when(userReadRepository.findActiveParticipantsForTasks(any()))
        .thenReturn(List.of(buildUser("a@brasi.de", "A"), buildUser("b@brasi.de", "B")));

    final InboxSnapshotModel snapshot = service.loadSnapshot(USER_REF, TENANT_ID, true);

    assertThat(snapshot.participantSummaries()).hasSize(2);
    verify(inboxGenerationService, times(2)).currentGeneration(USER_REF);
  }

  // ---- Limit-Clamp ---------------------------------------------------------------------------

  @Test
  @DisplayName("loadActivePage_limit_untergrenze_auf_1_geclampt")
  void loadActivePage_limit_untergrenze_auf_1_geclampt() {
    when(taskQueryRepository.findActivePage(eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID),
            eq(2)))
        .thenReturn(List.of());
    service.loadActivePage(USER_REF, null, 0);
    verify(taskQueryRepository)
        .findActivePage(eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(2));
  }

  @Test
  @DisplayName("loadActivePage_limit_obergrenze_auf_100_geclampt")
  void loadActivePage_limit_obergrenze_auf_100_geclampt() {
    when(taskQueryRepository.findActivePage(eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID),
            eq(101)))
        .thenReturn(List.of());
    service.loadActivePage(USER_REF, null, 999);
    verify(taskQueryRepository)
        .findActivePage(eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(101));
  }

  // ---- Archiv-Filter-Routing -----------------------------------------------------------------

  @Test
  @DisplayName("loadArchivePage_filter_done_nutzt_done_query")
  void loadArchivePage_filter_done_nutzt_done_query() {
    when(taskQueryRepository.findArchiveDonePage(
            eq(USER_REF), eq(DONE_ID), eq(CANCELLED_ID), any(), any(), eq(MAX_UUID), eq(11)))
        .thenReturn(buildTasks(1));
    final KeysetPage<TaskSummaryModel> page = service.loadArchivePage(USER_REF, "done", null, 10);
    assertThat(page.items()).hasSize(1);
    verify(taskQueryRepository)
        .findArchiveDonePage(
            eq(USER_REF), eq(DONE_ID), eq(CANCELLED_ID), any(), any(), eq(MAX_UUID), eq(11));
  }

  @Test
  @DisplayName("loadArchivePage_filter_left_nutzt_left_query")
  void loadArchivePage_filter_left_nutzt_left_query() {
    when(taskQueryRepository.findArchiveLeftPage(
            eq(USER_REF), any(), any(), eq(MAX_UUID), eq(11)))
        .thenReturn(buildTasks(1));
    final KeysetPage<TaskSummaryModel> page = service.loadArchivePage(USER_REF, "left", null, 10);
    assertThat(page.items()).hasSize(1);
    verify(taskQueryRepository).findArchiveLeftPage(eq(USER_REF), any(), any(), eq(MAX_UUID), eq(11));
  }

  @Test
  @DisplayName("loadArchivePage_filter_null_default_all_nutzt_all_query")
  void loadArchivePage_filter_null_default_all_nutzt_all_query() {
    when(taskQueryRepository.findArchiveAllPage(
            eq(USER_REF), eq(DONE_ID), eq(CANCELLED_ID), any(), any(), eq(MAX_UUID), eq(11)))
        .thenReturn(buildTasks(1));
    final KeysetPage<TaskSummaryModel> page = service.loadArchivePage(USER_REF, null, null, 10);
    assertThat(page.items()).hasSize(1);
    verify(taskQueryRepository)
        .findArchiveAllPage(
            eq(USER_REF), eq(DONE_ID), eq(CANCELLED_ID), any(), any(), eq(MAX_UUID), eq(11));
  }

  @Test
  @DisplayName("loadArchivePage_ungueltiger_filter_wirft_400")
  void loadArchivePage_ungueltiger_filter_wirft_400() {
    assertThatThrownBy(() -> service.loadArchivePage(USER_REF, "pending", null, 10))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- BE-1: currentUserId + Anreicherung + N+1-Sentinel + Enrichment-im-Bracket --------------

  @Test
  @DisplayName("loadSnapshot_setzt_currentUserId_auf_anfrager")
  void loadSnapshot_setzt_currentUserId() {
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(DEFAULT_LIMIT + 1)))
        .thenReturn(buildTasks(1));
    when(taskQueryRepository.countArchiveAll(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveDone(any(), any(), any(), any())).thenReturn(0L);
    when(taskQueryRepository.countArchiveLeft(any(), any())).thenReturn(0L);
    when(userReadRepository.findActiveParticipantsForTasks(any())).thenReturn(List.of());

    final InboxSnapshotModel snapshot = service.loadSnapshot(USER_REF, TENANT_ID, true);

    assertThat(snapshot.currentUserId()).isEqualTo(USER_REF);
  }

  @Test
  @DisplayName("loadActivePage_reichert_summary_felder_an (creator/assignees/participants/tags/marker)")
  void loadActivePage_reichert_summary_an() {
    final long taskId = 1000L; // buildTasks(1) -> id 1000
    final UUID assignee = UUID.randomUUID();
    final UUID participant = UUID.randomUUID();
    final UUID tag = UUID.randomUUID();

    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(11)))
        .thenReturn(buildTasks(1));
    when(participantQueryRepository.findActiveByTaskIds(any()))
        .thenReturn(
            List.of(
                participant(taskId, USER_REF, ParticipantKind.PERSON, true, false, false, null),
                participant(taskId, assignee, ParticipantKind.PERSON, false, true, false, null),
                participant(taskId, participant, ParticipantKind.PERSON, false, false, true, null)));
    when(tagAssignmentQueryRepository.findActiveTagUuidsByTaskIds(any()))
        .thenReturn(List.of(new TaskTagUuidRow(taskId, tag)));
    when(userStateQueryRepository.findByTaskIdsAndUserRef(any(), eq(USER_REF)))
        .thenReturn(List.of(userState(taskId, USER_REF, true, false, true, null)));

    final TaskSummaryModel s = service.loadActivePage(USER_REF, null, 10).items().get(0);

    assertThat(s.createdById()).isEqualTo(USER_REF);
    assertThat(s.assigneeIds()).containsExactly(assignee);
    assertThat(s.participantIds()).containsExactly(participant);
    assertThat(s.tagIds()).containsExactly(tag);
    assertThat(s.isPinned()).isTrue();
    assertThat(s.isFlagged()).isFalse();
    assertThat(s.isMuted()).isTrue();
    assertThat(s.createdAt()).isNotNull();
    assertThat(s.exit()).isNull();
  }

  @Test
  @DisplayName("isDone=true fuer Status DONE UND CANCELLED, false fuer OPEN")
  void isDone_fuer_done_und_cancelled() {
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(11)))
        .thenReturn(buildTasks(1));

    when(resolver.resolveTaskStatusCode(any())).thenReturn("DONE");
    assertThat(service.loadActivePage(USER_REF, null, 10).items().get(0).isDone()).isTrue();

    when(resolver.resolveTaskStatusCode(any())).thenReturn("CANCELLED");
    assertThat(service.loadActivePage(USER_REF, null, 10).items().get(0).isDone()).isTrue();

    when(resolver.resolveTaskStatusCode(any())).thenReturn("OPEN");
    assertThat(service.loadActivePage(USER_REF, null, 10).items().get(0).isDone()).isFalse();
  }

  @Test
  @DisplayName("exit=handover wird in archiv-left-Summary gemappt (asOf-gebunden)")
  void exit_handover_in_summary() {
    final long taskId = 1000L;
    final LocalDateTime before = LocalDateTime.of(2020, 1, 1, 0, 0);
    when(taskQueryRepository.findArchiveLeftPage(eq(USER_REF), any(), any(), eq(MAX_UUID), eq(11)))
        .thenReturn(buildTasks(1));
    final TaskParticipant exited =
        participant(taskId, USER_REF, ParticipantKind.PERSON, false, false, true, ExitKind.HANDOVER);
    exited.setExitAt(before);
    exited.setCreatedAt(before);
    when(participantQueryRepository.findActiveByTaskIds(any())).thenReturn(List.of(exited));
    // BE-7: Uebergabeziele des sichtbaren Austritts-Handover-Ereignisses -> exit.handedOverToIds.
    final UUID targetHi = UUID.fromString("ffffffff-0000-0000-0000-00000000aaaa");
    final UUID targetLo = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");
    when(handoverQueryRepository.findVisibleHandoverTargets(any(), eq(USER_REF), any()))
        .thenReturn(
            List.of(new HandoverTargetRow(taskId, targetHi), new HandoverTargetRow(taskId, targetLo)));

    final TaskSummaryModel s = service.loadArchivePage(USER_REF, "left", null, 10).items().get(0);

    assertThat(s.exit()).isNotNull();
    assertThat(s.exit().kind()).isEqualTo("handover");
    // Ziele stabil sortiert (BY_STRING): Lo vor Hi.
    assertThat(s.exit().handedOverToIds()).containsExactly(targetLo, targetHi);
  }

  @Test
  @DisplayName("N+1-Sentinel: je Batch-Repo GENAU 1 Aufruf pro Versuch (nicht-leere Seite)")
  void nplus1_sentinel_genau_ein_aufruf_pro_versuch() {
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(11)))
        .thenReturn(buildTasks(3));

    service.loadActivePage(USER_REF, null, 10);

    verify(participantQueryRepository, times(1)).findActiveByTaskIds(any());
    verify(tagAssignmentQueryRepository, times(1)).findActiveTagUuidsByTaskIds(any());
    verify(userStateQueryRepository, times(1)).findByTaskIdsAndUserRef(any(), eq(USER_REF));
    verify(handoverQueryRepository, times(1)).findVisibleHandoverTargets(any(), eq(USER_REF), any());
  }

  @Test
  @DisplayName("N+1-Sentinel: leere Seite -> 0 Batch-Aufrufe (kein IN())")
  void nplus1_sentinel_leere_seite_null_aufrufe() {
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(11)))
        .thenReturn(List.of());

    service.loadActivePage(USER_REF, null, 10);

    verify(participantQueryRepository, never()).findActiveByTaskIds(any());
    verify(tagAssignmentQueryRepository, never()).findActiveTagUuidsByTaskIds(any());
    verify(userStateQueryRepository, never()).findByTaskIdsAndUserRef(any(), any());
    verify(handoverQueryRepository, never()).findVisibleHandoverTargets(any(), any(), any());
  }

  @Test
  @DisplayName("N+1-Sentinel: Retry -> ein Satz Batch-Aufrufe JE VERSUCH (Enrichment im Bracket)")
  void nplus1_sentinel_retry_ein_satz_je_versuch() {
    // Versuch 1 instabil (0->1), Versuch 2 stabil (1->1): Enrichment laeuft je Versuch einmal.
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(0L, 1L, 1L, 1L);
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(11)))
        .thenReturn(buildTasks(1));

    service.loadActivePage(USER_REF, null, 10);

    verify(participantQueryRepository, times(2)).findActiveByTaskIds(any());
    verify(tagAssignmentQueryRepository, times(2)).findActiveTagUuidsByTaskIds(any());
    verify(userStateQueryRepository, times(2)).findByTaskIdsAndUserRef(any(), eq(USER_REF));
    verify(handoverQueryRepository, times(2)).findVisibleHandoverTargets(any(), eq(USER_REF), any());
  }

  @Test
  @DisplayName("Enrichment im Bracket: Folgeseite laeuft VOR dem Generation-Check (Batch-Reads trotz 410)")
  void enrichment_im_bracket_folgeseite_vor_gen_check() {
    final int limit = 5;
    final LocalDateTime asOf = LocalDateTime.of(2026, 10, 17, 10, 0, 0);
    final LocalDateTime lastActivity = LocalDateTime.of(2026, 10, 16, 9, 30, 0);
    final UUID lastExternal = UUID.randomUUID();
    final String cursor =
        KeysetCursor.encode(
            List.of(asOf.toString(), lastActivity.toString(), lastExternal.toString(), "5"));
    when(inboxGenerationService.currentGeneration(USER_REF)).thenReturn(7L); // Mismatch -> 410
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), eq(asOf), eq(lastActivity), eq(lastExternal), eq(limit + 1)))
        .thenReturn(buildTasks(1));

    assertThatThrownBy(() -> service.loadActivePage(USER_REF, cursor, limit))
        .isInstanceOf(SnapshotExpiredException.class);

    // Anreicherung lief VOR dem Generation-Check (im Bracket), obwohl am Ende 410 geworfen wird.
    verify(participantQueryRepository, times(1)).findActiveByTaskIds(any());
    verify(userStateQueryRepository, times(1)).findByTaskIdsAndUserRef(any(), eq(USER_REF));
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private static TaskParticipant participant(
      final long taskId,
      final UUID ref,
      final ParticipantKind kind,
      final boolean creator,
      final boolean assignee,
      final boolean participant,
      final ExitKind exitKind) {
    final TaskParticipant p = new TaskParticipant();
    p.setTaskId(taskId);
    p.setParticipantRef(ref);
    p.setParticipantKind(kind);
    p.setCreatorFlag(creator);
    p.setAssigneeFlag(assignee);
    p.setParticipantFlag(participant);
    p.setExitKind(exitKind);
    p.setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
    return p;
  }

  private static TaskUserState userState(
      final long taskId,
      final UUID userRef,
      final boolean pinned,
      final boolean flagged,
      final boolean muted,
      final LocalDateTime unsubscribedAt) {
    final TaskUserState s = new TaskUserState();
    s.setTaskId(taskId);
    s.setUserRef(userRef);
    s.setPinned(pinned);
    s.setFlagged(flagged);
    s.setMuted(muted);
    s.setUnsubscribedAt(unsubscribedAt);
    s.setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
    return s;
  }

  private List<Task> buildTasks(final int count) {
    final List<Task> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      final Task t = new Task();
      t.setId((long) (1000 + i));
      t.setUuid(UUID.randomUUID());
      t.setTitle("Task " + i);
      t.setTaskStatusId(OPEN_ID);
      t.setPriorityId(10L);
      t.setDueDate(LocalDate.of(2026, 10, 20));
      t.setLastActivityAt(LocalDateTime.of(2026, 10, 17, 12, 0, i));
      t.setCreatedAt(LocalDateTime.of(2026, 10, 1, 6, 30, 0));
      t.setCommentCount(0);
      result.add(t);
    }
    return result;
  }

  private User buildUser(final String login, final String fullName) {
    final User u = new User();
    u.setId(900L);
    u.setUuid(UUID.randomUUID());
    u.setLoginName(login);
    u.setFullName(fullName);
    return u;
  }
}
