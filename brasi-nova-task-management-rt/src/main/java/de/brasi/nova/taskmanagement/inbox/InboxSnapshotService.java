package de.brasi.nova.taskmanagement.inbox;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.inbox.model.ArchiveTotals;
import de.brasi.nova.taskmanagement.inbox.model.InboxSnapshotModel;
import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.notification.NotificationQueryService;
import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.participant.model.ParticipantSummary;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.tag.TagCatalogService;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Lese-Service fuer den Inbox-Bootstrap-Snapshot sowie die aktive/Archiv-Fortsetzungs-Reads
 * (arch-v2 §2.1/§3, plan-v3 Task 14).
 *
 * <p>Alle Reads sind benutzer-scoped ({@code userRef} aus dem Security-Context, nie aus
 * Clientdaten) und laufen im Request-Mandanten-Kontext (vom KSF-Filter gesetzt). Es gibt EIN
 * {@code asOf}-Wasserzeichen je Scroll: der Snapshot erzeugt es, die Folge-Reads tragen es im
 * Cursor weiter ({@code LAST_ACTIVITY_AT <= :asOf}). Kein {@code sequence}/Delta in 1a (arch-v2
 * §3: Reconnect = Re-Bootstrap mit neuem {@code asOf}).
 *
 * <p><b>Cursor:</b> opaker 3-Token-{@link KeysetCursor} ({@code asOf, lastActivityAt, externalId}),
 * ohne HMAC (ratifiziert in Task 13a; arch-v2 §3 HMAC/1..200 bewusst nicht umgesetzt). Limit wird
 * auf {@code [1,100]} geclampt (Default 50), analog {@link NotificationQueryService}.
 */
@Service
public class InboxSnapshotService {

  /** Default-Seitengroesse (analog NotificationQueryService R9). */
  private static final int DEFAULT_LIMIT = 50;

  /** Maximale Seitengroesse. */
  private static final int MAX_LIMIT = 100;

  /** Minimale Seitengroesse. */
  private static final int MIN_LIMIT = 1;

  private static final String FILTER_ALL = "all";
  private static final String FILTER_DONE = "done";
  private static final String FILTER_LEFT = "left";

  /**
   * Sentinel-Obergrenze fuer die First-Page (kein Cursor): maximaler {@code uniqueidentifier}.
   * Alle Bytes {@code 0xff} -> Maximum sowohl in der MSSQL-{@code uniqueidentifier}-Ordnung als
   * auch lexikografisch/H2. Zusammen mit {@code asOf=jetzt} (strikt nach allen persistierten
   * {@code LAST_ACTIVITY_AT}) macht das den Keyset-Zweig {@code < :asOf} all-inklusiv.
   */
  private static final UUID MAX_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

  private final TaskKeysetQueryRepository taskQueryRepository;
  private final NotificationQueryService notificationQueryService;
  private final TagCatalogService tagCatalogService;
  private final UserReadRepository userReadRepository;
  private final DictionaryCodeResolver resolver;
  private final AuditTimeConverter auditTimeConverter;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public InboxSnapshotService(
      final TaskKeysetQueryRepository taskQueryRepository,
      final NotificationQueryService notificationQueryService,
      final TagCatalogService tagCatalogService,
      final UserReadRepository userReadRepository,
      final DictionaryCodeResolver resolver,
      final AuditTimeConverter auditTimeConverter) {
    this.taskQueryRepository = taskQueryRepository;
    this.notificationQueryService = notificationQueryService;
    this.tagCatalogService = tagCatalogService;
    this.userReadRepository = userReadRepository;
    this.resolver = resolver;
    this.auditTimeConverter = auditTimeConverter;
  }

  /**
   * Erzeugt den Bootstrap-Snapshot der Inbox fuer den angegebenen Benutzer.
   *
   * <p>Ein frisches {@code asOf}-Wasserzeichen wird erzeugt und an die aktive Aufgabenseite UND die
   * Archiv-Zaehlungen durchgereicht, damit {@code snapshot.asOf == tasks.asOf} und die Zaehler
   * konsistent zum selben Zeitpunkt sind. Notifications = erste Seite (all), Tags = globaler
   * Katalog, participantSummaries = distinkte Teilnehmer NUR der Aufgaben dieser aktiven Seite.
   *
   * @param userRef  UUID des Benutzers (aus Security-Context)
   * @param tenantId interne Mandanten-ID (aus Security-Context)
   * @return aufgebauter {@link InboxSnapshotModel}
   */
  public InboxSnapshotModel loadSnapshot(final UUID userRef, final long tenantId) {
    final LocalDateTime asOf = now();
    final int effLimit = DEFAULT_LIMIT;

    // Aktive Seite (teilt das asOf des Snapshots) - First-Page via Sentinel-Keyset
    final Long openStatusId = resolver.resolveTaskStatusOpen();
    final List<Task> rawActive =
        taskQueryRepository.findActivePage(userRef, openStatusId, asOf, asOf, MAX_UUID, effLimit + 1);
    final boolean hasMore = rawActive.size() > effLimit;
    final List<Task> pageTasks = hasMore ? rawActive.subList(0, effLimit) : rawActive;
    final Instant asOfInstant = auditTimeConverter.toInstant(asOf);
    final KeysetPage<TaskSummaryModel> tasks =
        new KeysetPage<>(
            pageTasks.stream().map(this::toSummary).toList(),
            hasMore ? encodeCursor(asOf, pageTasks) : null,
            hasMore,
            asOfInstant);

    // Notifications: erste Seite (all)
    final NotificationPage notifications =
        notificationQueryService.listFor(userRef, tenantId, FILTER_ALL, null, effLimit);

    // Tags: globaler Katalog
    final List<TagModel> tags = tagCatalogService.list();

    // participantSummaries: NUR Teilnehmer der Aufgaben dieser Seite
    final List<ParticipantSummary> participantSummaries = participantSummariesFor(pageTasks);

    // Archiv-Gesamtzahlen (asOf-gebunden)
    final Long doneId = resolver.resolveTaskStatusDone();
    final Long cancelledId = resolver.resolveTaskStatusCancelled();
    final ArchiveTotals archiveTotals =
        new ArchiveTotals(
            taskQueryRepository.countArchiveAll(userRef, doneId, cancelledId, asOf),
            taskQueryRepository.countArchiveDone(userRef, doneId, cancelledId, asOf),
            taskQueryRepository.countArchiveLeft(userRef, asOf));

    return new InboxSnapshotModel(
        asOfInstant, tasks, notifications, tags, participantSummaries, archiveTotals);
  }

  /**
   * Liefert eine Keyset-Seite aktiver (OPEN) Aufgaben fuer den Benutzer (Fortsetzung des
   * Snapshots, {@code GET /api/v1/tasks}).
   *
   * @param userRef UUID des Benutzers
   * @param cursor  opaker 3-Token-Cursor (null/blank = erste Seite mit frischem asOf)
   * @param limit   Seitengroesse (wird geclampt)
   * @return Seite aktiver Aufgaben
   */
  public KeysetPage<TaskSummaryModel> loadActivePage(
      final UUID userRef, final String cursor, final int limit) {
    final int effLimit = clampLimit(limit);
    final Window window = resolveWindow(cursor);
    final Long openStatusId = resolver.resolveTaskStatusOpen();
    final List<Task> raw =
        taskQueryRepository.findActivePage(
            userRef, openStatusId, window.asOf(), window.lastActivityAt(), window.externalId(),
            effLimit + 1);
    return buildTaskPage(raw, window.asOf(), effLimit);
  }

  /**
   * Liefert eine Keyset-Seite archivierter Aufgaben je Filter ({@code all|done|left}) fuer den
   * Benutzer ({@code GET /api/v1/tasks/archive}).
   *
   * @param userRef UUID des Benutzers
   * @param filter  {@code all} (Default/null) | {@code done} | {@code left}
   * @param cursor  opaker 3-Token-Cursor (null/blank = erste Seite mit frischem asOf)
   * @param limit   Seitengroesse (wird geclampt)
   * @return Seite archivierter Aufgaben
   * @throws EntityValidationException bei ungueltigem Filter (HTTP 400)
   */
  public KeysetPage<TaskSummaryModel> loadArchivePage(
      final UUID userRef, final String filter, final String cursor, final int limit) {
    final String resolvedFilter = (filter == null || filter.isBlank()) ? FILTER_ALL : filter;
    if (!FILTER_ALL.equals(resolvedFilter)
        && !FILTER_DONE.equals(resolvedFilter)
        && !FILTER_LEFT.equals(resolvedFilter)) {
      throw new EntityValidationException(
          "Ungueltiger filter-Wert: '" + filter + "'. Erlaubt: all, done, left.");
    }

    final int effLimit = clampLimit(limit);
    final Window window = resolveWindow(cursor);

    final List<Task> raw;
    switch (resolvedFilter) {
      case FILTER_DONE -> {
        final Long doneId = resolver.resolveTaskStatusDone();
        final Long cancelledId = resolver.resolveTaskStatusCancelled();
        raw =
            taskQueryRepository.findArchiveDonePage(
                userRef, doneId, cancelledId, window.asOf(), window.lastActivityAt(),
                window.externalId(), effLimit + 1);
      }
      case FILTER_LEFT ->
          raw =
              taskQueryRepository.findArchiveLeftPage(
                  userRef, window.asOf(), window.lastActivityAt(), window.externalId(),
                  effLimit + 1);
      default -> {
        final Long doneId = resolver.resolveTaskStatusDone();
        final Long cancelledId = resolver.resolveTaskStatusCancelled();
        raw =
            taskQueryRepository.findArchiveAllPage(
                userRef, doneId, cancelledId, window.asOf(), window.lastActivityAt(),
                window.externalId(), effLimit + 1);
      }
    }
    return buildTaskPage(raw, window.asOf(), effLimit);
  }

  // ---- intern ---------------------------------------------------------------------------------

  /** Keyset-Fenster: Wasserzeichen + Keyset-Position (asOf, lastActivityAt, externalId). */
  private record Window(LocalDateTime asOf, LocalDateTime lastActivityAt, UUID externalId) {}

  /**
   * Loest das Keyset-Fenster aus dem Cursor auf. First-Page (null/blank): frisches
   * {@code asOf=jetzt} mit Sentinel-Keyset ({@code la=asOf}, {@code ext=MAX_UUID}). Folgeseite:
   * 3-Token-Cursor dekodieren; malformed -> 400.
   */
  private Window resolveWindow(final String cursor) {
    if (cursor == null || cursor.isBlank()) {
      final LocalDateTime asOf = now();
      return new Window(asOf, asOf, MAX_UUID);
    }
    final List<String> tokens = KeysetCursor.decode(cursor, 3);
    try {
      final LocalDateTime asOf = LocalDateTime.parse(tokens.get(0));
      final LocalDateTime lastActivityAt = LocalDateTime.parse(tokens.get(1));
      final UUID externalId = UUID.fromString(tokens.get(2));
      return new Window(asOf, lastActivityAt, externalId);
    } catch (DateTimeParseException | IllegalArgumentException e) {
      throw new EntityValidationException("Ungueltiger cursor.");
    }
  }

  /** Baut die Seite aus dem {@code limit+1}-Rohergebnis (hasMore + nextCursor + Mapping). */
  private KeysetPage<TaskSummaryModel> buildTaskPage(
      final List<Task> raw, final LocalDateTime asOf, final int effLimit) {
    final boolean hasMore = raw.size() > effLimit;
    final List<Task> page = hasMore ? raw.subList(0, effLimit) : raw;
    final String nextCursor = hasMore ? encodeCursor(asOf, page) : null;
    return new KeysetPage<>(
        page.stream().map(this::toSummary).toList(),
        nextCursor,
        hasMore,
        auditTimeConverter.toInstant(asOf));
  }

  /** Enkodiert den 3-Token-Cursor aus dem letzten Element der Seite. */
  private String encodeCursor(final LocalDateTime asOf, final List<Task> page) {
    final Task last = page.get(page.size() - 1);
    return KeysetCursor.encode(
        List.of(asOf.toString(), last.getLastActivityAt().toString(), last.getUuid().toString()));
  }

  /** Mappt eine {@link Task} auf das kompakte {@link TaskSummaryModel} (CODEs + Instant). */
  private TaskSummaryModel toSummary(final Task task) {
    return new TaskSummaryModel(
        task.getUuid(),
        task.getTitle(),
        resolver.resolveTaskStatusCode(task.getTaskStatusId()),
        resolver.resolveTaskPriorityCode(task.getPriorityId()),
        task.getDueDate(),
        auditTimeConverter.toInstant(task.getLastActivityAt()),
        task.getCommentCount());
  }

  /**
   * Loest die distinkten Teilnehmer der Seiten-Aufgaben auf. Leere Seite -> leere Liste (schuetzt
   * vor MSSQL {@code IN ()}-Syntaxfehler).
   */
  private List<ParticipantSummary> participantSummariesFor(final List<Task> pageTasks) {
    if (pageTasks.isEmpty()) {
      return List.of();
    }
    final List<Long> taskIds = pageTasks.stream().map(Task::getId).toList();
    final List<User> users = userReadRepository.findActiveParticipantsForTasks(taskIds);
    return users.stream()
        .map(u -> new ParticipantSummary(u.getUuid(), u.getLoginName(), u.getFullName()))
        .toList();
  }

  /** Aktuelles Wasserzeichen in der Persistenz-Zone (konsistent mit AuditTimeConverter). */
  private LocalDateTime now() {
    return LocalDateTime.now(auditTimeConverter.zone());
  }

  /** Clampt das Limit auf {@code [MIN_LIMIT, MAX_LIMIT]}. */
  private int clampLimit(final int limit) {
    return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
  }
}
