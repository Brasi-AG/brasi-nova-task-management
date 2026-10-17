package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.inbox.model.InboxSnapshotModel;
import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.notification.NotificationQueryService;
import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
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
  private NotificationQueryService notificationQueryService;
  private TagCatalogService tagCatalogService;
  private UserReadRepository userReadRepository;
  private DictionaryCodeResolver resolver;
  private AuditTimeConverter auditTimeConverter;
  private InboxSnapshotService service;

  @BeforeEach
  void setUp() {
    taskQueryRepository = mock(TaskKeysetQueryRepository.class);
    notificationQueryService = mock(NotificationQueryService.class);
    tagCatalogService = mock(TagCatalogService.class);
    userReadRepository = mock(UserReadRepository.class);
    resolver = mock(DictionaryCodeResolver.class);
    auditTimeConverter = new AuditTimeConverter("Europe/Berlin");

    service =
        new InboxSnapshotService(
            taskQueryRepository,
            notificationQueryService,
            tagCatalogService,
            userReadRepository,
            resolver,
            auditTimeConverter);

    when(resolver.resolveTaskStatusOpen()).thenReturn(OPEN_ID);
    when(resolver.resolveTaskStatusDone()).thenReturn(DONE_ID);
    when(resolver.resolveTaskStatusCancelled()).thenReturn(CANCELLED_ID);
    when(resolver.resolveTaskStatusCode(any())).thenReturn("OPEN");
    when(resolver.resolveTaskPriorityCode(any())).thenReturn("NORMAL");
    when(notificationQueryService.listFor(any(), anyLong(), any(), any(), anyInt()))
        .thenReturn(new NotificationPage(List.of(), null, false));
    when(tagCatalogService.list()).thenReturn(List.of());
  }

  // ---- Kern: snapshot_asOf_user_scoped_kein_sequence ------------------------------------------

  @Test
  @DisplayName("snapshot_asOf_user_scoped_kein_sequence")
  void snapshot_asOf_user_scoped_kein_sequence() {
    final List<Task> pageTasks = buildTasks(2);
    // First-Page: Sentinel-Keyset (asOf=now, la=now, ext=MAX_UUID), limit+1=51
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

    final InboxSnapshotModel snapshot = service.loadSnapshot(USER_REF, TENANT_ID);

    // asOf gesetzt + plausibel + identisch mit tasks.asOf (EIN Wasserzeichen)
    assertThat(snapshot.asOf()).isNotNull();
    assertThat(snapshot.asOf()).isAfter(Instant.now().minusSeconds(120));
    assertThat(snapshot.asOf()).isBefore(Instant.now().plusSeconds(120));
    assertThat(snapshot.tasks().asOf()).isEqualTo(snapshot.asOf());

    // user-scoped: aktive Query mit genau diesem userRef aufgerufen
    verify(taskQueryRepository)
        .findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(LocalDateTime.class), any(LocalDateTime.class),
            eq(MAX_UUID), eq(DEFAULT_LIMIT + 1));

    // Inhalt
    assertThat(snapshot.tasks().items()).hasSize(2);
    assertThat(snapshot.tasks().hasMore()).isFalse();
    assertThat(snapshot.participantSummaries()).hasSize(1);
    assertThat(snapshot.archiveTotals().all()).isEqualTo(5L);
    assertThat(snapshot.archiveTotals().done()).isEqualTo(3L);
    assertThat(snapshot.archiveTotals().left()).isEqualTo(2L);

    // Archiv-Zaehler teilen das asOf des Snapshots (gleiches Wasserzeichen)
    final Instant asOfInstant = snapshot.asOf();
    final LocalDateTime asOfLdt = LocalDateTime.ofInstant(asOfInstant, auditTimeConverter.zone());
    verify(taskQueryRepository).countArchiveAll(USER_REF, DONE_ID, CANCELLED_ID, asOfLdt);

    // struktureller Beweis: KEIN sequence/Delta-Feld im Snapshot (arch-v2 §3)
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

    final InboxSnapshotModel snapshot = service.loadSnapshot(USER_REF, TENANT_ID);

    assertThat(snapshot.tasks().items()).isEmpty();
    assertThat(snapshot.participantSummaries()).isEmpty();
    // KEIN collection-IN mit leerer Menge (MSSQL IN () = Syntaxfehler)
    verify(userReadRepository, never()).findActiveParticipantsForTasks(any());
  }

  // ---- aktive Fortsetzung: hasMore + nextCursor ----------------------------------------------

  @Test
  @DisplayName("loadActivePage_first_page_hasMore_true_und_nextCursor_gesetzt")
  void loadActivePage_first_page_hasMore_true_und_nextCursor_gesetzt() {
    final int limit = 2;
    // limit+1 = 3 -> hasMore=true, 2 Items
    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(limit + 1)))
        .thenReturn(buildTasks(3));

    final KeysetPage<TaskSummaryModel> page = service.loadActivePage(USER_REF, null, limit);

    assertThat(page.items()).hasSize(2);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursor()).isNotBlank();
    assertThat(page.asOf()).isNotNull();
    // nextCursor ist ein 3-Token-Cursor (asOf, lastActivityAt, externalId)
    assertThat(KeysetCursor.decode(page.nextCursor(), 3)).hasSize(3);
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
            List.of(asOf.toString(), lastActivity.toString(), lastExternal.toString()));

    when(taskQueryRepository.findActivePage(
            eq(USER_REF), eq(OPEN_ID), eq(asOf), eq(lastActivity), eq(lastExternal), eq(limit + 1)))
        .thenReturn(buildTasks(1));

    final KeysetPage<TaskSummaryModel> page = service.loadActivePage(USER_REF, cursor, limit);

    assertThat(page.items()).hasSize(1);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
    // asOf aus dem Cursor durchgereicht (kein frisches now)
    assertThat(page.asOf()).isEqualTo(auditTimeConverter.toInstant(asOf));
    verify(taskQueryRepository)
        .findActivePage(USER_REF, OPEN_ID, asOf, lastActivity, lastExternal, limit + 1);
  }

  @Test
  @DisplayName("loadActivePage_malformed_cursor_falsche_tokenzahl_wirft_400")
  void loadActivePage_malformed_cursor_falsche_tokenzahl_wirft_400() {
    // nur 2 Token statt 3 -> KeysetCursor.decode(cursor,3) -> EntityValidationException
    final String badCursor =
        KeysetCursor.encode(List.of(LocalDateTime.now().toString(), UUID.randomUUID().toString()));
    assertThatThrownBy(() -> service.loadActivePage(USER_REF, badCursor, 10))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("loadActivePage_cursor_nicht_parsebare_tokens_wirft_400")
  void loadActivePage_cursor_nicht_parsebare_tokens_wirft_400() {
    final String badCursor =
        KeysetCursor.encode(List.of("KEIN_DATUM", "KEIN_DATUM", "KEINE_UUID"));
    assertThatThrownBy(() -> service.loadActivePage(USER_REF, badCursor, 10))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Limit-Clamp ---------------------------------------------------------------------------

  @Test
  @DisplayName("loadActivePage_limit_untergrenze_auf_1_geclampt")
  void loadActivePage_limit_untergrenze_auf_1_geclampt() {
    when(taskQueryRepository.findActivePage(eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID),
            eq(2)))
        .thenReturn(List.of());
    service.loadActivePage(USER_REF, null, 0); // 0 -> clamp 1 -> limit+1=2
    verify(taskQueryRepository)
        .findActivePage(eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID), eq(2));
  }

  @Test
  @DisplayName("loadActivePage_limit_obergrenze_auf_100_geclampt")
  void loadActivePage_limit_obergrenze_auf_100_geclampt() {
    when(taskQueryRepository.findActivePage(eq(USER_REF), eq(OPEN_ID), any(), any(), eq(MAX_UUID),
            eq(101)))
        .thenReturn(List.of());
    service.loadActivePage(USER_REF, null, 999); // 999 -> clamp 100 -> limit+1=101
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

  // ---- Hilfsmethoden --------------------------------------------------------------------------

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
