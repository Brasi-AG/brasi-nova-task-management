package de.brasi.nova.taskmanagement.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskNotification;
import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import de.brasi.nova.taskmanagement.repository.BaseTaskNotificationRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.NotificationQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link NotificationQueryService}.
 *
 * <p>Testet ohne DB: alle Abhaengigkeiten werden gemockt.
 */
class NotificationQueryServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER_REF = UUID.randomUUID();
  private static final long TYPE_ID = 401L;

  private NotificationQueryRepository queryRepository;
  private BaseTaskNotificationRepository notificationRepository;
  private BaseTaskRepository taskRepository;
  private DictionaryCodeResolver resolver;
  private AuditTimeConverter auditTimeConverter;
  private NotificationQueryService service;

  @BeforeEach
  void setUp() {
    queryRepository = mock(NotificationQueryRepository.class);
    notificationRepository = mock(BaseTaskNotificationRepository.class);
    taskRepository = mock(BaseTaskRepository.class);
    resolver = mock(DictionaryCodeResolver.class);
    // Echter AuditTimeConverter mit Default-Zone (Europe/Berlin) fuer Tests
    auditTimeConverter = new AuditTimeConverter("Europe/Berlin");

    service = new NotificationQueryService(
        queryRepository,
        notificationRepository,
        taskRepository,
        resolver,
        auditTimeConverter);

    // Resolver-Stubs
    when(resolver.resolveNotificationTypeCode(any())).thenReturn("ASSIGN");
    when(resolver.resolveNotificationSeverityCode(any())).thenReturn("INFO");
  }

  // ---- hasMore + nextCursor-Erzeugung (limit+1-Logik) -------------------------

  @Test
  @DisplayName("listFor_all_first_page_hasMore_true_wenn_mehr_als_limit")
  void listFor_all_first_page_hasMore_true_wenn_mehr_als_limit() {
    final int limit = 3;
    // limit+1 = 4 Ergebnisse -> hasMore=true, 3 Items geliefert
    final List<TaskNotification> entities = buildNotifications(limit + 1);
    when(queryRepository.findFirstPage(eq(USER_REF), eq(limit + 1))).thenReturn(entities);

    final NotificationPage page = service.listFor(USER_REF, TENANT_ID, "all", null, limit);

    assertThat(page.items()).hasSize(limit);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursor()).isNotNull();
  }

  @Test
  @DisplayName("listFor_all_first_page_hasMore_false_wenn_genau_limit")
  void listFor_all_first_page_hasMore_false_wenn_genau_limit() {
    final int limit = 3;
    // genau limit Ergebnisse -> hasMore=false
    final List<TaskNotification> entities = buildNotifications(limit);
    when(queryRepository.findFirstPage(eq(USER_REF), eq(limit + 1))).thenReturn(entities);

    final NotificationPage page = service.listFor(USER_REF, TENANT_ID, "all", null, limit);

    assertThat(page.items()).hasSize(limit);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  @DisplayName("listFor_all_first_page_hasMore_false_wenn_weniger_als_limit")
  void listFor_all_first_page_hasMore_false_wenn_weniger_als_limit() {
    final int limit = 5;
    final List<TaskNotification> entities = buildNotifications(2);
    when(queryRepository.findFirstPage(eq(USER_REF), eq(limit + 1))).thenReturn(entities);

    final NotificationPage page = service.listFor(USER_REF, TENANT_ID, "all", null, limit);

    assertThat(page.items()).hasSize(2);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  @DisplayName("listFor_cursor_vorhanden_nutzt_findPage")
  void listFor_cursor_vorhanden_nutzt_findPage() {
    final int limit = 2;
    final LocalDateTime ts = LocalDateTime.of(2026, 10, 17, 10, 0, 0);
    final UUID lastId = UUID.randomUUID();
    final String cursor = KeysetCursor.encode(List.of(ts.toString(), lastId.toString()));

    final List<TaskNotification> entities = buildNotifications(1);
    when(queryRepository.findPage(eq(USER_REF), eq(ts), eq(lastId), eq(limit + 1)))
        .thenReturn(entities);

    final NotificationPage page = service.listFor(USER_REF, TENANT_ID, "all", cursor, limit);

    assertThat(page.items()).hasSize(1);
    assertThat(page.hasMore()).isFalse();
    verify(queryRepository).findPage(eq(USER_REF), eq(ts), eq(lastId), eq(limit + 1));
  }

  // ---- filter-Validierung (unknown -> 400) ----------------------------------------

  @Test
  @DisplayName("listFor_unbekannter_filter_wirft_EntityValidationException")
  void listFor_unbekannter_filter_wirft_EntityValidationException() {
    assertThatThrownBy(() -> service.listFor(USER_REF, TENANT_ID, "unknown", null, 10))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("listFor_null_filter_wird_als_all_behandelt")
  void listFor_null_filter_wird_als_all_behandelt() {
    when(queryRepository.findFirstPage(eq(USER_REF), anyInt())).thenReturn(List.of());
    final NotificationPage page = service.listFor(USER_REF, TENANT_ID, null, null, 10);
    assertThat(page.items()).isEmpty();
    verify(queryRepository).findFirstPage(eq(USER_REF), anyInt());
  }

  // ---- limit-Clamp ---------------------------------------------------------------

  @Test
  @DisplayName("listFor_limit_untergrenze_wird_auf_1_geclampt")
  void listFor_limit_untergrenze_wird_auf_1_geclampt() {
    when(queryRepository.findFirstPage(eq(USER_REF), eq(2))).thenReturn(List.of());
    service.listFor(USER_REF, TENANT_ID, "all", null, 0); // 0 -> clamp to 1 -> limit+1=2
    verify(queryRepository).findFirstPage(eq(USER_REF), eq(2));
  }

  @Test
  @DisplayName("listFor_limit_obergrenze_wird_auf_100_geclampt")
  void listFor_limit_obergrenze_wird_auf_100_geclampt() {
    when(queryRepository.findFirstPage(eq(USER_REF), eq(101))).thenReturn(List.of());
    service.listFor(USER_REF, TENANT_ID, "all", null, 999); // 999 -> clamp to 100 -> limit+1=101
    verify(queryRepository).findFirstPage(eq(USER_REF), eq(101));
  }

  // ---- markRead: Ownership --------------------------------------------------------

  @Test
  @DisplayName("markRead_fremder_recipient_wirft_EntityNotFoundException")
  void markRead_fremder_recipient_wirft_EntityNotFoundException() {
    final UUID notifId = UUID.randomUUID();
    final TaskNotification n = buildNotification();
    n.setRecipientRef(UUID.randomUUID()); // fremder Empfaenger
    n.setTenantId(TENANT_ID);
    // Nach Fix: queryRepository.findActiveByUuid (active-scoped Lookup)
    when(queryRepository.findActiveByUuid(notifId.toString())).thenReturn(Optional.of(n));

    assertThatThrownBy(() -> service.markRead(notifId, USER_REF, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);

    verify(notificationRepository, never()).save(any());
  }

  @Test
  @DisplayName("markRead_fremder_tenant_wirft_EntityNotFoundException")
  void markRead_fremder_tenant_wirft_EntityNotFoundException() {
    final UUID notifId = UUID.randomUUID();
    final TaskNotification n = buildNotification();
    n.setRecipientRef(USER_REF);
    n.setTenantId(999L); // fremder Mandant
    // Nach Fix: queryRepository.findActiveByUuid (active-scoped Lookup)
    when(queryRepository.findActiveByUuid(notifId.toString())).thenReturn(Optional.of(n));

    assertThatThrownBy(() -> service.markRead(notifId, USER_REF, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);

    verify(notificationRepository, never()).save(any());
  }

  @Test
  @DisplayName("markRead_nicht_gefunden_wirft_EntityNotFoundException")
  void markRead_nicht_gefunden_wirft_EntityNotFoundException() {
    final UUID notifId = UUID.randomUUID();
    // Nach Fix: queryRepository.findActiveByUuid (active-scoped Lookup)
    when(queryRepository.findActiveByUuid(notifId.toString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.markRead(notifId, USER_REF, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  @DisplayName("markRead_eigene_notification_setzt_isRead_und_speichert")
  void markRead_eigene_notification_setzt_isRead_und_speichert() {
    final UUID notifId = UUID.randomUUID();
    final TaskNotification n = buildNotification();
    n.setRecipientRef(USER_REF);
    n.setTenantId(TENANT_ID);
    n.setRead(false);
    // Nach Fix: queryRepository.findActiveByUuid (active-scoped Lookup)
    when(queryRepository.findActiveByUuid(notifId.toString())).thenReturn(Optional.of(n));
    when(notificationRepository.save(any())).thenReturn(n);

    service.markRead(notifId, USER_REF, TENANT_ID);

    assertThat(n.isRead()).isTrue();
    verify(notificationRepository).save(n);
  }

  @Test
  @DisplayName("markRead_bereits_gelesen_ist_idempotent")
  void markRead_bereits_gelesen_ist_idempotent() {
    final UUID notifId = UUID.randomUUID();
    final TaskNotification n = buildNotification();
    n.setRecipientRef(USER_REF);
    n.setTenantId(TENANT_ID);
    n.setRead(true); // schon gelesen
    // Nach Fix: queryRepository.findActiveByUuid (active-scoped Lookup)
    when(queryRepository.findActiveByUuid(notifId.toString())).thenReturn(Optional.of(n));
    when(notificationRepository.save(any())).thenReturn(n);

    // kein Fehler erwartet
    service.markRead(notifId, USER_REF, TENANT_ID);

    verify(notificationRepository).save(n); // trotzdem gespeichert (idempotent)
  }

  @Test
  @DisplayName("markRead_deaktivierte_benachrichtigung_wirft_EntityNotFoundException")
  void markRead_deaktivierte_benachrichtigung_wirft_EntityNotFoundException() {
    // P2-Negativtest: deaktivierte Benachrichtigung muss neutrale 404 liefern (kein IS_READ-Update).
    // findActiveByUuid gibt empty zurueck (ENTITY_STATUS-Filter), daher kein save.
    final UUID notifId = UUID.randomUUID();
    when(queryRepository.findActiveByUuid(notifId.toString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.markRead(notifId, USER_REF, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);

    verify(notificationRepository, never()).save(any());
  }

  // ---- F-P2b: Seite mit mehreren Notifications zu unterschiedlichen Tasks -> korrekte UUIDs ----

  @Test
  @DisplayName("listFor_mehrere_notifications_mit_tasks_liefert_korrekte_externe_uuids")
  void listFor_mehrere_notifications_mit_tasks_liefert_korrekte_externe_uuids() {
    // Zwei unterschiedliche Tasks mit je eigener externer UUID
    final Task task1 = new Task();
    final UUID task1Uuid = UUID.randomUUID();
    task1.setId(1001L);
    task1.setUuid(task1Uuid);

    final Task task2 = new Task();
    final UUID task2Uuid = UUID.randomUUID();
    task2.setId(1002L);
    task2.setUuid(task2Uuid);

    // Stub fuer Batch-findAllById (F-P2b: batch-Load aller Task-IDs der Seite)
    when(taskRepository.findAllById(any())).thenReturn(List.of(task1, task2));

    // Seite mit 2 Notifications, jede auf anderen Task
    final List<TaskNotification> entities = new ArrayList<>();
    final TaskNotification n1 = buildNotification();
    n1.setTaskId(1001L);
    n1.setUuid(UUID.randomUUID());
    final TaskNotification n2 = buildNotification();
    n2.setTaskId(1002L);
    n2.setUuid(UUID.randomUUID());
    entities.add(n1);
    entities.add(n2);

    when(queryRepository.findFirstPage(eq(USER_REF), eq(3))).thenReturn(entities);

    final NotificationPage page = service.listFor(USER_REF, TENANT_ID, "all", null, 2);

    assertThat(page.items()).hasSize(2);
    // Beide Task-UUIDs muessen korrekt aufgeloest werden
    final var taskUuids = page.items().stream()
        .map(de.brasi.nova.taskmanagement.notification.model.NotificationModel::taskId)
        .collect(java.util.stream.Collectors.toList());
    assertThat(taskUuids).containsExactlyInAnyOrder(task1Uuid, task2Uuid);
  }

  // ---- F-P1: base64-gueltiger Cursor mit nicht-parsebarem Inhalt -> 400 ----------

  @Test
  @DisplayName("listFor_cursor_mit_nicht_parsebbarem_timestamp_wirft_EntityValidationException")
  void listFor_cursor_mit_nicht_parsebbarem_timestamp_wirft_EntityValidationException() {
    // base64url-gueltiger Cursor, aber Token[0] ist kein LocalDateTime
    final String badTs = de.brasi.nova.taskmanagement.paging.KeysetCursor.encode(
        List.of("KEIN_DATUM", UUID.randomUUID().toString()));

    assertThatThrownBy(() -> service.listFor(USER_REF, TENANT_ID, "all", badTs, 10))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("listFor_cursor_mit_nicht_parsebarer_uuid_wirft_EntityValidationException")
  void listFor_cursor_mit_nicht_parsebarer_uuid_wirft_EntityValidationException() {
    // base64url-gueltiger Cursor, aber Token[1] ist keine UUID
    final String badUuid = de.brasi.nova.taskmanagement.paging.KeysetCursor.encode(
        List.of(LocalDateTime.now().toString(), "KEINE_UUID"));

    assertThatThrownBy(() -> service.listFor(USER_REF, TENANT_ID, "all", badUuid, 10))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Hilfsmethoden --------------------------------------------------------------

  private TaskNotification buildNotification() {
    final TaskNotification n = new TaskNotification();
    n.setUuid(UUID.randomUUID());
    n.setId(1L);
    n.setTypeId(TYPE_ID);
    n.setSeverityId(null);
    n.setTaskId(null);
    n.setText("Test");
    n.setRead(false);
    n.setCreatedAt(LocalDateTime.of(2026, 10, 17, 12, 0, 0));
    return n;
  }

  private List<TaskNotification> buildNotifications(final int count) {
    final List<TaskNotification> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      final TaskNotification n = buildNotification();
      n.setUuid(UUID.randomUUID());
      n.setId((long) (i + 1));
      n.setCreatedAt(LocalDateTime.of(2026, 10, 17, 12, 0, i));
      result.add(n);
    }
    return result;
  }
}
