package de.brasi.nova.taskmanagement.inbox;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.inbox.model.ArchiveTotals;
import de.brasi.nova.taskmanagement.inbox.model.InboxSnapshotModel;
import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.notification.NotificationQueryService;
import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
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
 * Cursor weiter ({@code LAST_ACTIVITY_AT <= :asOf}).
 *
 * <p><b>Snapshot-Rejection (Finding 2 / Option B):</b> Membership-Zustandsfelder
 * ({@code UNSUBSCRIBED_AT}/{@code EXIT_KIND}/{@code EXIT_AT}) werden destruktiv ueberschrieben und
 * brechen die asOf-Invariante. Jeder Cursor traegt zusaetzlich die {@code GENERATION}
 * ({@link InboxGenerationService}) des Anfragers, unter der sein Snapshot erzeugt wurde:
 * <ul>
 *   <li><b>Folgeseite (Cursor):</b> Page-Query ZUERST, Generation DANACH lesen; weicht sie ab ->
 *       {@link SnapshotExpiredException} (HTTP 410), der Client re-bootstrapt (check-AFTER).
 *   <li><b>First-Page/Bootstrap (kein Cursor):</b> Generation-Bracket mit Retry — Generation lesen,
 *       membership-abhaengige Assembly mit frischem {@code asOf}, Generation erneut lesen; nur bei
 *       Stabilitaet ({@code g1 == g2}) wird die Generation in den Cursor gestempelt, sonst Retry.
 *       Nach {@code MAX_SNAPSHOT_ATTEMPTS} -> 410 (seltene sichere Degradation statt Mischantwort).
 * </ul>
 * Die Generation-Invariante deckt NUR die requester-gescopten, asOf-gebundenen Querys (aktive Seite
 * + Archiv-Counts). {@code participantSummaries} zeigen bewusst den LIVE-Teilnehmerstand und werden
 * ueber den Push-Invalidate-Kanal aktuell gehalten, nicht ueber Cursor/Generation.
 *
 * <p><b>Cursor:</b> opaker 4-Token-{@link KeysetCursor} ({@code asOf, lastActivityAt, externalId,
 * generation}), ohne HMAC. Limit wird auf {@code [1,100]} geclampt (Default 50).
 */
@Service
public class InboxSnapshotService {

  /** Default-Seitengroesse (analog NotificationQueryService R9). */
  private static final int DEFAULT_LIMIT = 50;

  /** Maximale Seitengroesse. */
  private static final int MAX_LIMIT = 100;

  /** Minimale Seitengroesse. */
  private static final int MIN_LIMIT = 1;

  /**
   * Maximale Versuche fuer den First-Page/Bootstrap-Generation-Bracket. Ein Versuch scheitert nur,
   * wenn waehrend der Assembly ein Membership-Bump des Anfragers committet. Bei anhaltender
   * Aenderung (praktisch unerreichbarer Bump-Sturm) -> {@link SnapshotExpiredException} (410).
   */
  private static final int MAX_SNAPSHOT_ATTEMPTS = 3;

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
  private final InboxGenerationService inboxGenerationService;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public InboxSnapshotService(
      final TaskKeysetQueryRepository taskQueryRepository,
      final NotificationQueryService notificationQueryService,
      final TagCatalogService tagCatalogService,
      final UserReadRepository userReadRepository,
      final DictionaryCodeResolver resolver,
      final AuditTimeConverter auditTimeConverter,
      final InboxGenerationService inboxGenerationService) {
    this.taskQueryRepository = taskQueryRepository;
    this.notificationQueryService = notificationQueryService;
    this.tagCatalogService = tagCatalogService;
    this.userReadRepository = userReadRepository;
    this.resolver = resolver;
    this.auditTimeConverter = auditTimeConverter;
    this.inboxGenerationService = inboxGenerationService;
  }

  /**
   * Erzeugt den Bootstrap-Snapshot der Inbox fuer den angegebenen Benutzer.
   *
   * <p>Ein frisches {@code asOf}-Wasserzeichen wird erzeugt und an die aktive Aufgabenseite UND die
   * Archiv-Zaehlungen durchgereicht, damit {@code snapshot.asOf == tasks.asOf} und die Zaehler
   * konsistent zum selben Zeitpunkt sind. Die requester-membership-abhaengigen Querys (aktive Seite
   * + Archiv-Counts) laufen im Generation-Bracket mit Retry (siehe Klassen-Javadoc).
   * Notifications = erste Seite (all), Tags = globaler Katalog, participantSummaries = distinkte
   * (LIVE-)Teilnehmer der Aufgaben dieser aktiven Seite.
   *
   * @param userRef  UUID des Benutzers (aus Security-Context)
   * @param tenantId interne Mandanten-ID (aus Security-Context)
   * @param notificationsVisible {@code true}, wenn der Aufrufer die Permission
   *     {@code task-management:NOTIFICATION_READ} besitzt (vom Controller als Autz-Grenze
   *     ausgewertet). Bei {@code false} wird die Notification-Seite NICHT geladen, sondern als
   *     leere Seite zurueckgegeben (fail-closed) - NOTIFICATION_READ gatet die Notification-Nutzlast
   *     im Bootstrap-Snapshot konsistent zum dedizierten {@code GET /api/v1/notifications}.
   * @return aufgebauter {@link InboxSnapshotModel}
   * @throws SnapshotExpiredException (HTTP 410) wenn unter anhaltender Membership-Aenderung kein
   *     konsistenter Snapshot erzeugt werden konnte
   */
  public InboxSnapshotModel loadSnapshot(
      final UUID userRef, final long tenantId, final boolean notificationsVisible) {
    final int effLimit = DEFAULT_LIMIT;
    final Long openStatusId = resolver.resolveTaskStatusOpen();
    final Long doneId = resolver.resolveTaskStatusDone();
    final Long cancelledId = resolver.resolveTaskStatusCancelled();

    // Generation-Bracket um die requester-membership-abhaengigen Querys (aktive Seite + Counts).
    BootstrapMembership membership = null;
    for (int attempt = 0; attempt < MAX_SNAPSHOT_ATTEMPTS; attempt++) {
      final long g1 = inboxGenerationService.currentGeneration(userRef);
      final LocalDateTime asOf = now();
      final List<Task> rawActive =
          taskQueryRepository.findActivePage(
              userRef, openStatusId, asOf, asOf, MAX_UUID, effLimit + 1);
      final ArchiveTotals archiveTotals =
          new ArchiveTotals(
              taskQueryRepository.countArchiveAll(userRef, doneId, cancelledId, asOf),
              taskQueryRepository.countArchiveDone(userRef, doneId, cancelledId, asOf),
              taskQueryRepository.countArchiveLeft(userRef, asOf));
      final long g2 = inboxGenerationService.currentGeneration(userRef);
      if (g1 == g2) {
        membership = new BootstrapMembership(asOf, rawActive, archiveTotals, g1);
        break;
      }
    }
    if (membership == null) {
      throw new SnapshotExpiredException();
    }

    // Stabiler requester-Stand -> aktive Seite bauen (Generation stempeln).
    final boolean hasMore = membership.rawActive().size() > effLimit;
    final List<Task> pageTasks =
        hasMore ? membership.rawActive().subList(0, effLimit) : membership.rawActive();
    final Instant asOfInstant = auditTimeConverter.toInstant(membership.asOf());
    final KeysetPage<TaskSummaryModel> tasks =
        new KeysetPage<>(
            pageTasks.stream().map(this::toSummary).toList(),
            hasMore ? encodeCursor(membership.asOf(), membership.generation(), pageTasks) : null,
            hasMore,
            asOfInstant);

    // LIVE-Teile ausserhalb der Generation-Invariante (siehe Klassen-Javadoc):
    // Notifications nur mit NOTIFICATION_READ (fail-closed): ohne Recht wird die Quelle NICHT
    // angefragt, sondern eine leere Seite zurueckgegeben (kein DB-Leak, contract-stabiles non-null-Feld).
    final NotificationPage notifications =
        notificationsVisible
            ? notificationQueryService.listFor(userRef, tenantId, FILTER_ALL, null, effLimit)
            : new NotificationPage(List.of(), null, false);
    final List<TagModel> tags = tagCatalogService.list();
    final List<ParticipantSummary> participantSummaries = participantSummariesFor(pageTasks);

    return new InboxSnapshotModel(
        asOfInstant, tasks, notifications, tags, participantSummaries, membership.archiveTotals());
  }

  /**
   * Liefert eine Keyset-Seite aktiver (OPEN) Aufgaben fuer den Benutzer (Fortsetzung des
   * Snapshots, {@code GET /api/v1/tasks}).
   *
   * @param userRef UUID des Benutzers
   * @param cursor  opaker 4-Token-Cursor (null/blank = erste Seite mit frischem asOf)
   * @param limit   Seitengroesse (wird geclampt)
   * @return Seite aktiver Aufgaben
   * @throws SnapshotExpiredException (HTTP 410) wenn ein Folge-Read eine abgelaufene Generation
   *     traegt oder die First-Page unter Dauer-Mutation nicht konsistent erzeugbar war
   */
  public KeysetPage<TaskSummaryModel> loadActivePage(
      final UUID userRef, final String cursor, final int limit) {
    final int effLimit = clampLimit(limit);
    final Long openStatusId = resolver.resolveTaskStatusOpen();
    return readPage(
        userRef,
        cursor,
        effLimit,
        (asOf, lastActivityAt, externalId) ->
            taskQueryRepository.findActivePage(
                userRef, openStatusId, asOf, lastActivityAt, externalId, effLimit + 1));
  }

  /**
   * Liefert eine Keyset-Seite archivierter Aufgaben je Filter ({@code all|done|left}) fuer den
   * Benutzer ({@code GET /api/v1/tasks/archive}).
   *
   * @param userRef UUID des Benutzers
   * @param filter  {@code all} (Default/null) | {@code done} | {@code left}
   * @param cursor  opaker 4-Token-Cursor (null/blank = erste Seite mit frischem asOf)
   * @param limit   Seitengroesse (wird geclampt)
   * @return Seite archivierter Aufgaben
   * @throws EntityValidationException bei ungueltigem Filter (HTTP 400)
   * @throws SnapshotExpiredException (HTTP 410) wie {@link #loadActivePage}
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
    final PageFetch fetch =
        switch (resolvedFilter) {
          case FILTER_DONE -> {
            final Long doneId = resolver.resolveTaskStatusDone();
            final Long cancelledId = resolver.resolveTaskStatusCancelled();
            yield (asOf, lastActivityAt, externalId) ->
                taskQueryRepository.findArchiveDonePage(
                    userRef, doneId, cancelledId, asOf, lastActivityAt, externalId, effLimit + 1);
          }
          case FILTER_LEFT ->
              (asOf, lastActivityAt, externalId) ->
                  taskQueryRepository.findArchiveLeftPage(
                      userRef, asOf, lastActivityAt, externalId, effLimit + 1);
          default -> {
            final Long doneId = resolver.resolveTaskStatusDone();
            final Long cancelledId = resolver.resolveTaskStatusCancelled();
            yield (asOf, lastActivityAt, externalId) ->
                taskQueryRepository.findArchiveAllPage(
                    userRef, doneId, cancelledId, asOf, lastActivityAt, externalId, effLimit + 1);
          }
        };
    return readPage(userRef, cursor, effLimit, fetch);
  }

  // ---- intern ---------------------------------------------------------------------------------

  /** Abruf einer Rohseite ({@code limit+1}) fuer ein Keyset-Fenster (asOf, lastActivityAt, ext). */
  @FunctionalInterface
  private interface PageFetch {
    List<Task> fetch(LocalDateTime asOf, LocalDateTime lastActivityAt, UUID externalId);
  }

  /** Keyset-Fenster: Wasserzeichen + Keyset-Position + Snapshot-Generation. */
  private record Window(
      LocalDateTime asOf, LocalDateTime lastActivityAt, UUID externalId, long generation) {}

  /** Stabiler requester-Membership-Stand aus dem Bootstrap-Bracket. */
  private record BootstrapMembership(
      LocalDateTime asOf, List<Task> rawActive, ArchiveTotals archiveTotals, long generation) {}

  /**
   * Einheitlicher Seiten-Read: First-Page (kein Cursor) via Generation-Bracket mit Retry;
   * Folgeseite (Cursor) via check-AFTER -> 410 bei Generation-Mismatch.
   */
  private KeysetPage<TaskSummaryModel> readPage(
      final UUID userRef, final String cursor, final int effLimit, final PageFetch fetch) {
    if (cursor == null || cursor.isBlank()) {
      // First-Page: Generation-Bracket. Die einzige membership-Query ist die Seite selbst.
      for (int attempt = 0; attempt < MAX_SNAPSHOT_ATTEMPTS; attempt++) {
        final long g1 = inboxGenerationService.currentGeneration(userRef);
        final LocalDateTime asOf = now();
        final List<Task> raw = fetch.fetch(asOf, asOf, MAX_UUID);
        final long g2 = inboxGenerationService.currentGeneration(userRef);
        if (g1 == g2) {
          return buildTaskPage(raw, asOf, g1, effLimit);
        }
      }
      throw new SnapshotExpiredException();
    }

    // Folgeseite: Page-Query ZUERST, Generation DANACH (check-AFTER).
    final Window window = decodeWindow(cursor);
    final List<Task> raw = fetch.fetch(window.asOf(), window.lastActivityAt(), window.externalId());
    final long current = inboxGenerationService.currentGeneration(userRef);
    if (current != window.generation()) {
      throw new SnapshotExpiredException();
    }
    return buildTaskPage(raw, window.asOf(), window.generation(), effLimit);
  }

  /**
   * Dekodiert den 4-Token-Cursor (asOf, lastActivityAt, externalId, generation). Malformed
   * (Tokenzahl, Datum, UUID, Zahl) -> {@link EntityValidationException} (HTTP 400). Ein alter
   * 3-Token-Cursor scheitert bereits an der Tokenzahl.
   */
  private Window decodeWindow(final String cursor) {
    final List<String> tokens = KeysetCursor.decode(cursor, 4);
    try {
      final LocalDateTime asOf = LocalDateTime.parse(tokens.get(0));
      final LocalDateTime lastActivityAt = LocalDateTime.parse(tokens.get(1));
      final UUID externalId = UUID.fromString(tokens.get(2));
      final long generation = Long.parseLong(tokens.get(3));
      return new Window(asOf, lastActivityAt, externalId, generation);
    } catch (DateTimeParseException | IllegalArgumentException e) {
      throw new EntityValidationException("Ungueltiger cursor.");
    }
  }

  /** Baut die Seite aus dem {@code limit+1}-Rohergebnis (hasMore + nextCursor + Mapping). */
  private KeysetPage<TaskSummaryModel> buildTaskPage(
      final List<Task> raw, final LocalDateTime asOf, final long generation, final int effLimit) {
    final boolean hasMore = raw.size() > effLimit;
    final List<Task> page = hasMore ? raw.subList(0, effLimit) : raw;
    final String nextCursor = hasMore ? encodeCursor(asOf, generation, page) : null;
    return new KeysetPage<>(
        page.stream().map(this::toSummary).toList(),
        nextCursor,
        hasMore,
        auditTimeConverter.toInstant(asOf));
  }

  /** Enkodiert den 4-Token-Cursor aus dem letzten Element der Seite + der Snapshot-Generation. */
  private String encodeCursor(
      final LocalDateTime asOf, final long generation, final List<Task> page) {
    final Task last = page.get(page.size() - 1);
    return KeysetCursor.encode(
        List.of(
            asOf.toString(),
            last.getLastActivityAt().toString(),
            last.getUuid().toString(),
            Long.toString(generation)));
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
   * Loest die distinkten (LIVE-)Teilnehmer der Seiten-Aufgaben auf. Leere Seite -> leere Liste
   * (schuetzt vor MSSQL {@code IN ()}-Syntaxfehler). Bewusst ausserhalb der Generation-Invariante.
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
