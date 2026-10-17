package de.brasi.nova.taskmanagement.notification;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskNotification;
import de.brasi.nova.taskmanagement.notification.model.NotificationModel;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lese-seitiger Service fuer Benachrichtigungen (separiert von {@link NotificationService}
 * der fuer Emit/Push zustaendig ist).
 *
 * <p>Implementiert Keyset-Paginierung (R1/R2/R12), Filter (R10), Limit-Clamp (R9)
 * und markRead/markAllRead (R4/R5).
 *
 * <p>Der {@code TenantContextHolder} wird vom KSF-Filter beim HTTP-Request gesetzt;
 * die Repository-Calls nutzen den bereits vorhandenen Kontext.
 */
@Service
public class NotificationQueryService {

  private static final Logger log = LoggerFactory.getLogger(NotificationQueryService.class);

  /** Default-Limit fuer eine Seite (R9). */
  private static final int DEFAULT_LIMIT = 50;

  /** Maximaler Limit-Wert (R9). */
  private static final int MAX_LIMIT = 100;

  /** Minimaler Limit-Wert (R9). */
  private static final int MIN_LIMIT = 1;

  private static final String FILTER_ALL = "all";
  private static final String FILTER_UNREAD = "unread";

  private final NotificationQueryRepository queryRepository;
  private final BaseTaskNotificationRepository notificationRepository;
  private final BaseTaskRepository taskRepository;
  private final DictionaryCodeResolver resolver;
  private final AuditTimeConverter auditTimeConverter;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public NotificationQueryService(
      final NotificationQueryRepository queryRepository,
      final BaseTaskNotificationRepository notificationRepository,
      final BaseTaskRepository taskRepository,
      final DictionaryCodeResolver resolver,
      final AuditTimeConverter auditTimeConverter) {
    this.queryRepository = queryRepository;
    this.notificationRepository = notificationRepository;
    this.taskRepository = taskRepository;
    this.resolver = resolver;
    this.auditTimeConverter = auditTimeConverter;
  }

  /**
   * Liefert eine Seite von Benachrichtigungen fuer den angegebenen Benutzer.
   *
   * <p>Filter (R10): {@code all} (default/null) liefert alle; {@code unread} nur IS_READ=0;
   * anderer Wert loest {@link EntityValidationException} (HTTP 400) aus.
   * Limit (R9): Default 50, geclampt auf [1, 100].
   * hasMore-Logik: limit+1 abfragen; wenn size &gt; limit, letztes Element abschneiden
   * und nextCursor generieren.
   *
   * <p>Der {@code TenantContextHolder} muss beim Aufruf bereits gesetzt sein
   * (wird vom KSF-Filterchain gesetzt). Der {@code tenantId}-Parameter wird nur fuer die
   * Tenantpruefung benutzt (gemaess Security-Anforderungen), nicht fuer das Setzen des Kontexts.
   *
   * @param userRef  UUID des Empfaengers
   * @param tenantId interne Mandanten-ID (aus Security-Context)
   * @param filter   "all" | "unread" | null
   * @param cursor   opaker Paginierungs-Cursor (null/blank = erste Seite)
   * @param limit    Seitengroesse (wird geclampt)
   * @return Seite der Benachrichtigungen
   */
  public NotificationPage listFor(
      final UUID userRef,
      final long tenantId,
      final String filter,
      final String cursor,
      final int limit) {

    // Filter validieren (R10)
    final String resolvedFilter = (filter == null || filter.isBlank()) ? FILTER_ALL : filter;
    if (!FILTER_ALL.equals(resolvedFilter) && !FILTER_UNREAD.equals(resolvedFilter)) {
      throw new EntityValidationException(
          "Ungueltiger filter-Wert: '" + filter + "'. Erlaubt: all, unread.");
    }

    // Limit clampen (R9): geclampt auf [1, 100]; 0 oder negativ -> MIN_LIMIT
    final int effectiveLimit = Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));

    final List<TaskNotification> raw;

    if (cursor == null || cursor.isBlank()) {
      // Erste Seite (R2): dedizierte First-Page-Query ohne Keyset-Praedikat
      if (FILTER_UNREAD.equals(resolvedFilter)) {
        raw = queryRepository.findUnreadFirstPage(userRef, effectiveLimit + 1);
      } else {
        raw = queryRepository.findFirstPage(userRef, effectiveLimit + 1);
      }
    } else {
      // Folgeseite: Cursor dekodieren (R12: LocalDateTime + UUID)
      final List<String> tokens = KeysetCursor.decode(cursor, 2);
      final LocalDateTime afterCreatedAt = LocalDateTime.parse(tokens.get(0));
      final UUID afterExternalId = UUID.fromString(tokens.get(1));

      if (FILTER_UNREAD.equals(resolvedFilter)) {
        raw = queryRepository.findUnreadPage(
            userRef, afterCreatedAt, afterExternalId, effectiveLimit + 1);
      } else {
        raw = queryRepository.findPage(
            userRef, afterCreatedAt, afterExternalId, effectiveLimit + 1);
      }
    }

    // hasMore-Logik: wenn mehr als effectiveLimit Ergebnisse vorhanden
    final boolean hasMore = raw.size() > effectiveLimit;
    final List<TaskNotification> page =
        hasMore ? raw.subList(0, effectiveLimit) : raw;

    // nextCursor aus dem letzten Element der Seite (R12: rohes LocalDateTime + EXTERNALID)
    final String nextCursor;
    if (hasMore) {
      final TaskNotification last = page.get(page.size() - 1);
      nextCursor = KeysetCursor.encode(
          List.of(
              last.getCreatedAt().toString(),
              last.getUuid().toString()));
    } else {
      nextCursor = null;
    }

    // Entities in Models mappen
    final List<NotificationModel> items = new ArrayList<>();
    for (final TaskNotification entity : page) {
      items.add(toModel(entity));
    }

    return new NotificationPage(items, nextCursor, hasMore);
  }

  /**
   * Markiert eine Benachrichtigung als gelesen.
   *
   * <p>Idempotent: bereits gelesene Benachrichtigungen werden ohne Fehler gespeichert.
   * 404-Masking (R5): nicht gefunden UND falscher Tenant/Empfaenger liefern
   * identische neutrale {@link EntityNotFoundException}.
   * Kein Invalidate/Notification nach dem Lesen (R5).
   *
   * @param notificationExternalId externe UUID der Benachrichtigung
   * @param actorRef               UUID des aktuellen Benutzers (aus Security-Context)
   * @param tenantId               interne Mandanten-ID
   */
  @Transactional
  public void markRead(
      final UUID notificationExternalId,
      final UUID actorRef,
      final long tenantId) {

    final Optional<TaskNotification> found =
        notificationRepository.findByUuid(notificationExternalId.toString());

    if (found.isEmpty()
        || found.get().getTenantId() != tenantId
        || !actorRef.equals(found.get().getRecipientRef())) {
      throw new EntityNotFoundException("Benachrichtigung nicht gefunden.");
    }

    final TaskNotification notification = found.get();
    notification.setRead(true);
    notificationRepository.save(notification);

    log.info(
        "NotificationQueryService.markRead: Benachrichtigung {} als gelesen markiert (Actor={}).",
        notificationExternalId,
        actorRef);
  }

  /**
   * Markiert alle eigenen ungelesenen Benachrichtigungen als gelesen (R4).
   *
   * <p>Laeuft in EINER Transaktion; iteriert und speichert jeden Eintrag (kein DML-{@code @NovaQuery}).
   * Scope strikt {@code RECIPIENT_REF = actorRef} + {@code TENANT_ID = tenantId}.
   * Kein Invalidate/Notification nach dem Lesen (R5).
   *
   * @param actorRef UUID des aktuellen Benutzers (aus Security-Context)
   * @param tenantId interne Mandanten-ID
   */
  @Transactional
  public void markAllRead(final UUID actorRef, final long tenantId) {
    final List<TaskNotification> unread = queryRepository.findUnreadAllForUser(actorRef);

    for (final TaskNotification n : unread) {
      n.setRead(true);
      notificationRepository.save(n);
    }

    log.info(
        "NotificationQueryService.markAllRead: {} ungelesene Benachrichtigungen als gelesen"
            + " markiert (Actor={}, Mandant={}).",
        unread.size(),
        actorRef,
        tenantId);
  }

  // ---- Mapping -----------------------------------------------------------------

  /**
   * Mappt eine {@link TaskNotification} auf ein {@link NotificationModel}.
   *
   * <p>taskId (R6): interne Task-ID wird auf externe Task-UUID aufgeloest (null-safe; defensiv
   * wenn Task nicht gefunden).
   * createdAt (R7): AuditTimeConverter-Instant (PFLICHT).
   * typeCode/severityCode: ueber DictionaryCodeResolver aufgeloest.
   *
   * @param entity die Notification-Entity
   * @return gemapptes Model
   */
  private NotificationModel toModel(final TaskNotification entity) {
    final String typeCode = resolver.resolveNotificationTypeCode(entity.getTypeId());
    final String severityCode = entity.getSeverityId() != null
        ? resolver.resolveNotificationSeverityCode(entity.getSeverityId())
        : null;

    // R6: externe Task-UUID (null-safe; defensiv wenn Task nicht gefunden)
    UUID taskUuid = null;
    if (entity.getTaskId() != null) {
      final Optional<Task> task = taskRepository.findById(entity.getTaskId());
      taskUuid = task.map(Task::getUuid).orElse(null);
    }

    return new NotificationModel(
        entity.getUuid(),
        typeCode,
        taskUuid,
        entity.getActorRef(),
        entity.getText(),
        entity.getPreview(),
        severityCode,
        entity.isRead(),
        auditTimeConverter.toInstant(entity.getCreatedAt())); // R7
  }
}
