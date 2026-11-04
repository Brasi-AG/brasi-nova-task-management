package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.AssigneeService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.push.InboxSignalPort;
import de.brasi.nova.taskmanagement.push.NotificationPushEnvelope;
import de.brasi.nova.taskmanagement.push.NotificationPushPort;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskCommentRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskNotificationRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationTypeRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TagSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskDirectionRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskPriorityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskStatusRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Real-MSSQL-Nebenlaeufigkeits-IT fuer {@code CommentService.createComment()} vs {@code
 * AssigneeService.updateAssignees()} auf derselben Aufgabe (Intake #136, Concurrency-Nachweis der
 * SPEC).
 *
 * <p><b>Echte Services statt Mocks:</b> {@link NotificationService} + {@link InboxSignalService}
 * sind REAL verdrahtet (mit aufzeichnenden Ports). Belegt wird der VOLLSTAENDIGE Rollback des
 * Verlierers = KEINE Teilpersistenz (weder Kommentar noch COMMENT-Notification). Ueber den
 * After-Commit-Push trifft der Test bewusst KEINE Aussage: der VersionConflict schlaegt am
 * versionierten Task-Save zu, also VOR dem Emit - der Verlierer registriert somit nie einen
 * Push-Hook (es gibt nichts zurueckzunehmen).
 *
 * <p>Erlaubte Ergebnisse praezise (SPEC): entweder beide Tx serialisiert erfolgreich, ODER genau
 * eine scheitert mit VersionConflict ({@link BusinessRuleException}) und vollstaendigem Rollback
 * (dieselbe versionierte Aufgabe). AUSGESCHLOSSEN: Deadlock, Timeout, teilpersistierter Kommentar
 * oder teilpersistierte Notification.
 *
 * <ul>
 *   <li><b>Deadlock-/Lock-Order-Beweis ({@link
 *       #createComment_vs_updateAssignees_kein_deadlock}):</b> createComment (inkl. Fall "Assignee
 *       ist zugleich erwaehnt") und updateAssignees laufen OHNE Seam-Synchronisation mit
 *       ueberlappenden Teilnehmern parallel - beide committen; weder Deadlock noch Timeout. Belegt
 *       die gemeinsame Teilnehmer-dann-Task-Lock-Reihenfolge.
 *   <li><b>Versions-Verlierer ({@link #createComment_verliert_version_voller_rollback}):</b> der
 *       createComment-Verlierer pausiert am Top-of-save-Seam (noch KEINE TASK-Schreibsperre); ein
 *       nebenlaeufiger updateAssignees-Gewinner committet und bumpt die TASK-VERSION. Nach der
 *       Fortsetzung trifft der Verlierer beim versionierten TASK-save die stale VERSION -> 409 +
 *       voller Rollback (kein Kommentar, keine Notification, kein After-Commit-Push).
 * </ul>
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = CommentConcurrencyIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "spring.flyway.enabled=true",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0",
      "spring.flyway.locations=classpath:db/migration",
      "ksf.persistence.dialect=MSSQL",
      "ksf.persistence.ddl-auto=none",
      // Mindestens zwei Verbindungen (Verlierer haelt seine Tx-Verbindung waehrend er am Seam
      // pausiert; der Gewinner-Thread braucht eine zweite).
      "spring.datasource.hikari.maximum-pool-size=5",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class CommentConcurrencyIT {

  private static final long TENANT_ID = 595L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000595");

  /** Kommentar-Autor + Task-Creator (WRITE-Member). */
  private static final UUID ACTOR_REF = UUID.fromString("00000059-00a0-0059-0000-000000000595");

  /** Zweiter WRITE-Member: Assignee + Mention-Ziel (ueberlappt den updateAssignees-Scope). */
  private static final UUID ASSIGNEE_REF = UUID.fromString("00000059-00b0-0059-0000-000000000595");

  /** Neues Assignee-Ziel, das der updateAssignees-Gewinner ergaenzt (TASK-VERSION-Bump). */
  private static final UUID TARGET_WIN = UUID.fromString("00000059-00d0-0059-0000-000000000595");

  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_NORMAL = 202L;
  private static final long DIRECTION = 301L;

  // Deadlock-Beweis-Aufgabe (ohne Seam).
  private static final long TASK_NODEADLOCK_ID = 595101L;
  private static final UUID TASK_NODEADLOCK_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000595101");
  // Versions-Verlierer-Aufgabe (mit Seam).
  private static final long TASK_VERSION_ID = 595102L;
  private static final UUID TASK_VERSION_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000595102");

  private static final long WINNER_JOIN_TIMEOUT_MS = 60_000L;

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TestCommentService commentService;
  @Autowired private AssigneeService assigneeService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecordingInboxSignalPort inboxSignalPort;
  @Autowired private RecordingNotificationPushPort notificationPushPort;

  /** Thread-lokaler Akteur (kein geteiltes Mock-Re-Stubbing; der UserAccessor liest ihn). */
  private static final ThreadLocal<UUID> ACTOR_PER_THREAD =
      ThreadLocal.withInitial(() -> ACTOR_REF);

  private final AtomicReference<Throwable> winnerError = new AtomicReference<>();
  private final AtomicBoolean winnerTimedOut = new AtomicBoolean(false);

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
    registry.add("spring.datasource.url", () -> RefSchemaItDb.throwawayJdbcUrl(dbName));
    registry.add("spring.datasource.username", RefSchemaItEnv::user);
    registry.add("spring.datasource.password", RefSchemaItEnv::password);
    registry.add(
        "spring.datasource.driver-class-name",
        () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
  }

  @AfterAll
  static void teardown() {
    try {
      if (master != null && dbName != null) {
        RefSchemaItDb.dropThrowawayDb(master, dbName);
      }
    } finally {
      if (master != null) {
        master.close();
      }
    }
  }

  @BeforeAll
  static void seed(@Autowired JdbcTemplate jt) {
    seedFirma(jt, TENANT_ID, TENANT_UUID);
    seedBenutzer(jt, 595501L, ACTOR_REF, "actor@brasi.de");
    seedBenutzer(jt, 595502L, ASSIGNEE_REF, "assignee@brasi.de");
    seedBenutzer(jt, 595503L, TARGET_WIN, "target-win@brasi.de");

    // Deadlock-Beweis-Aufgabe: ACTOR (Creator+Assignee) + ASSIGNEE (Assignee) aktiv.
    insertTask(jt, TASK_NODEADLOCK_ID, TASK_NODEADLOCK_UUID, "IT-Concurrency-NoDeadlock");
    insertActiveAssignee(jt, 595201L, TASK_NODEADLOCK_ID, ACTOR_REF, true);
    insertActiveAssignee(jt, 595202L, TASK_NODEADLOCK_ID, ASSIGNEE_REF, false);

    // Versions-Verlierer-Aufgabe: ACTOR (Creator+Assignee) + ASSIGNEE (Assignee) aktiv.
    insertTask(jt, TASK_VERSION_ID, TASK_VERSION_UUID, "IT-Concurrency-Version");
    insertActiveAssignee(jt, 595203L, TASK_VERSION_ID, ACTOR_REF, true);
    insertActiveAssignee(jt, 595204L, TASK_VERSION_ID, ASSIGNEE_REF, false);
  }

  @BeforeEach
  void resetState() {
    winnerError.set(null);
    winnerTimedOut.set(false);
    inboxSignalPort.clear();
    notificationPushPort.clear();
    ACTOR_PER_THREAD.set(ACTOR_REF);
    commentService.armTaskSaveInjector(null);
  }

  // ---- Deadlock-/Lock-Order-Beweis: beide committen serialisiert, kein Deadlock/Timeout
  // ----------

  @Test
  @DisplayName(
      "createComment_vs_updateAssignees_kein_deadlock: Top-Level-Kommentar (Assignee auch erwaehnt)"
          + " parallel zu updateAssignees mit ueberlappenden Teilnehmern -> beide committen,"
          + " kein Deadlock/Timeout")
  void createComment_vs_updateAssignees_kein_deadlock() throws Exception {
    final int versionBefore = taskVersion(TASK_NODEADLOCK_UUID);

    // Gewinner-Thread: updateAssignees ergaenzt TARGET_WIN (Zielset {ACTOR, ASSIGNEE, TARGET_WIN}).
    final AtomicReference<Throwable> winnerErr = new AtomicReference<>();
    final Thread winner =
        new Thread(
            () -> {
              TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
              ACTOR_PER_THREAD.set(ACTOR_REF);
              try {
                assigneeService.updateAssignees(
                    TASK_NODEADLOCK_UUID,
                    new AssigneeUpdateInput(Set.of(ACTOR_REF, ASSIGNEE_REF, TARGET_WIN)));
              } catch (final Throwable t) {
                winnerErr.set(t);
              } finally {
                ACTOR_PER_THREAD.remove();
                TenantContextHolder.clear();
              }
            },
            "concurrency-updateAssignees");

    // Verlierer-/Haupt-Thread: createComment Top-Level mit Mention auf den ueberlappenden ASSIGNEE.
    final AtomicReference<Throwable> commentErr = new AtomicReference<>();
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ACTOR_REF);
    winner.start();
    try {
      commentService.createComment(
          TASK_NODEADLOCK_UUID,
          new CommentCreateInput("Parallel, mit Mention.", null, List.of(ASSIGNEE_REF)));
    } catch (final Throwable t) {
      commentErr.set(t);
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
    }
    winner.join(WINNER_JOIN_TIMEOUT_MS);

    // Kein haengender Thread (Deadlock/Timeout AUSGESCHLOSSEN).
    assertThat(winner.isAlive())
        .as("updateAssignees-Thread beendete rechtzeitig (kein Deadlock/Wartezyklus)")
        .isFalse();

    // Erlaubte Ergebnisse: entweder beide ok, ODER genau einer scheitert mit BusinessRuleException
    // (VersionConflict) + Rollback. Deadlock/Timeout/Teilpersistenz ist ausgeschlossen. Jeder
    // aufgetretene Fehler MUSS eine BusinessRuleException sein (keine SQL-Deadlock-Exception).
    if (commentErr.get() != null) {
      assertThat(commentErr.get())
          .as("createComment scheitert hoechstens mit VersionConflict (409), nie Deadlock")
          .isInstanceOf(BusinessRuleException.class);
    }
    if (winnerErr.get() != null) {
      assertThat(winnerErr.get())
          .as("updateAssignees scheitert hoechstens mit VersionConflict (409), nie Deadlock")
          .isInstanceOf(BusinessRuleException.class);
    }

    // Integritaet: hoechstens EIN TASK-VERSION-Bump je erfolgreicher versionierter Operation; der
    // Kommentar ist entweder vollstaendig da oder gar nicht (keine Teilpersistenz).
    final int commentRows = commentRowCount(TASK_NODEADLOCK_ID, ACTOR_REF);
    if (commentErr.get() == null) {
      assertThat(commentRows).as("erfolgreicher createComment -> genau 1 Kommentar").isEqualTo(1);
    } else {
      assertThat(commentRows)
          .as("fehlgeschlagener createComment -> kein Kommentar (Rollback)")
          .isZero();
    }
    assertThat(taskVersion(TASK_NODEADLOCK_UUID))
        .as("TASK-VERSION hoechstens um die Zahl der erfolgreichen versionierten Ops gestiegen")
        .isLessThanOrEqualTo(versionBefore + 2);
  }

  // ---- Versions-Verlierer: createComment pausiert am Seam, updateAssignees gewinnt -> 409
  // --------

  @Test
  @DisplayName(
      "createComment_verliert_version_voller_rollback: updateAssignees committet am Seam und bumpt"
          + " die TASK-VERSION -> createComment 409 + voller Rollback (kein Kommentar, keine"
          + " COMMENT-Notification, kein After-Commit-Push)")
  void createComment_verliert_version_voller_rollback() {
    final int versionBefore = taskVersion(TASK_VERSION_UUID);
    final int commentsBefore = commentRowCount(TASK_VERSION_ID, ACTOR_REF);

    // Gewinner am Seam: updateAssignees ergaenzt TARGET_WIN auf einem eigenen Thread (eigener
    // Tenant
    // + Akteur ACTOR, eigene @Transactional) und committet, bevor der Verlierer fortsetzt.
    commentService.armTaskSaveInjector(
        () ->
            runWinnerOnSeparateThread(
                () ->
                    assigneeService.updateAssignees(
                        TASK_VERSION_UUID,
                        new AssigneeUpdateInput(Set.of(ACTOR_REF, ASSIGNEE_REF, TARGET_WIN)))));

    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ACTOR_REF);
    try {
      assertThatThrownBy(
              () ->
                  commentService.createComment(
                      TASK_VERSION_UUID,
                      new CommentCreateInput("Verliert den Versions-Race.", null, List.of())))
          .as("Versions-Verlierer -> 409 (BusinessRuleException)")
          .isInstanceOf(BusinessRuleException.class);
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
    }

    // Gewinner-Thread sauber beendet, fehlerfrei committet.
    assertThat(winnerTimedOut.get())
        .as("updateAssignees-Gewinner beendete rechtzeitig (kein Deadlock)")
        .isFalse();
    assertThat(winnerError.get()).as("Gewinner committet fehlerfrei").isNull();

    // Positiv: der Gewinner hat die TASK-VERSION GENAU um 1 gebumpt + TARGET_WIN wurde Assignee.
    assertThat(taskVersion(TASK_VERSION_UUID))
        .as("Gewinner bumpt die TASK-VERSION um genau 1")
        .isEqualTo(versionBefore + 1);
    assertThat(activeAssigneeCount(TASK_VERSION_ID, TARGET_WIN))
        .as("Gewinner-Assignee TARGET_WIN aktiv")
        .isEqualTo(1);

    // Verlierer voll zurueckgerollt - KEINE Teilpersistenz (das ist die eigentliche Zusage dieses
    // Tests; ueber den After-Commit-Push trifft er bewusst keine Aussage: der VersionConflict
    // schlaegt
    // am versionierten taskRepository.save ZU, also VOR dem Emit - der Verlierer registriert somit
    // nie
    // einen Push-Hook, es gibt nichts zurueckzunehmen).
    assertThat(commentRowCount(TASK_VERSION_ID, ACTOR_REF))
        .as("Verlierer: kein neuer Kommentar persistiert (voller Rollback)")
        .isEqualTo(commentsBefore);
    assertThat(commentNotificationCount(TASK_VERSION_ID))
        .as("Verlierer: keine persistierte COMMENT-Notification (voller Rollback)")
        .isZero();
  }

  /**
   * Startet den Gewinner auf einem eigenen Thread (eigener Tenant/Akteur) und wartet mit Timeout.
   */
  private void runWinnerOnSeparateThread(final Runnable winnerOp) {
    final AtomicReference<Throwable> err = new AtomicReference<>();
    final Thread winner =
        new Thread(
            () -> {
              TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
              ACTOR_PER_THREAD.set(ACTOR_REF);
              try {
                winnerOp.run();
              } catch (final Throwable t) {
                err.set(t);
              } finally {
                ACTOR_PER_THREAD.remove();
                TenantContextHolder.clear();
              }
            },
            "concurrency-winner");
    winner.start();
    try {
      winner.join(WINNER_JOIN_TIMEOUT_MS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      err.set(e);
    }
    if (winner.isAlive()) {
      winnerTimedOut.set(true);
      winner.interrupt();
    }
    winnerError.set(err.get());
  }

  // ---- Sonden ----------------------------------------------------------------------------------

  private int taskVersion(final UUID taskUuid) {
    return jdbcTemplate.queryForObject(
        "SELECT VERSION FROM [dbo].[TASK] WHERE EXTERNALID=?", Integer.class, taskUuid);
  }

  private int commentRowCount(final long taskId, final UUID authorRef) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] WHERE TASK_ID=? AND TENANT_ID=? AND AUTHOR_REF=?",
        Integer.class,
        taskId,
        TENANT_ID,
        authorRef);
  }

  private int commentNotificationCount(final long taskId) {
    // TYPE_ID 409 = COMMENT (V9-Seed).
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] WHERE TASK_ID=? AND TENANT_ID=? AND TYPE_ID=409",
        Integer.class,
        taskId,
        TENANT_ID);
  }

  private int activeAssigneeCount(final long taskId, final UUID ref) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?"
            + " AND ASSIGNEE_FLAG=1 AND EXIT_KIND IS NULL",
        Integer.class,
        taskId,
        ref);
  }

  // ---- Seed-Helfer -----------------------------------------------------------------------------

  private static void seedFirma(final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " GUELTIG_VON) VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id,
        id,
        externalId,
        id);
  }

  private static void seedBenutzer(
      final JdbcTemplate jt, final long id, final UUID externalId, final String loginName) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " LOGIN_NAME, VOLLER_NAME) VALUES (?, 'ACTIVE', ?, 1, ?, ?, 'IT Test User')",
        id,
        id,
        externalId,
        TENANT_ID,
        loginName);
  }

  private static void insertTask(
      final JdbcTemplate jt, final long id, final UUID externalId, final String title) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE,"
            + " TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT,"
            + " COMMENT_COUNT, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?, GETDATE(), 0, 'it-seed', GETDATE())",
        id,
        id,
        externalId,
        TENANT_ID,
        title,
        STATUS_OPEN,
        PRIORITY_NORMAL,
        DIRECTION,
        TENANT_ID);
  }

  private static void insertActiveAssignee(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID ref,
      final boolean creator) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, ?, 1, 1, 'it-seed', GETDATE())",
        id,
        id,
        TENANT_ID,
        taskId,
        ref,
        TENANT_ID,
        creator ? 1 : 0);
  }

  // ---- Aufzeichnende Ports ---------------------------------------------------------------------

  static final class RecordingInboxSignalPort implements InboxSignalPort {
    private final List<Set<UUID>> sends = new CopyOnWriteArrayList<>();

    @Override
    public void send(final long tenantId, final Collection<UUID> userRefs) {
      sends.add(new HashSet<>(userRefs));
    }

    void clear() {
      sends.clear();
    }
  }

  static final class RecordingNotificationPushPort implements NotificationPushPort {
    private final List<UUID> pushedRecipients = new CopyOnWriteArrayList<>();

    @Override
    public void push(
        final long tenantId, final UUID recipientRef, final NotificationPushEnvelope envelope) {
      pushedRecipients.add(recipientRef);
    }

    void clear() {
      pushedRecipients.clear();
    }
  }

  // ---- Test-Service: Seam fuer deterministische Nebenlaeufigkeit -------------------------------

  /** Test-Subklasse: laesst am Top-of-save-Seam einen nebenlaeufigen Gewinner committen. */
  static class TestCommentService extends CommentService {

    private final AtomicReference<Runnable> taskSaveInjector = new AtomicReference<>();

    TestCommentService(
        final BaseTaskRepository taskRepository,
        final BaseTaskCommentRepository commentRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final NotificationService notificationService,
        final InboxSignalService inboxSignalService,
        final InboxGenerationService inboxGenerationService,
        final UserReadRepository userReadRepository,
        final UserAccessor userAccessor,
        final AuditTimeConverter auditTimeConverter,
        final TaskMembershipGuard membershipGuard) {
      super(
          taskRepository,
          commentRepository,
          userStateQueryRepository,
          participantQueryRepository,
          notificationService,
          inboxSignalService,
          inboxGenerationService,
          userReadRepository,
          userAccessor,
          auditTimeConverter,
          membershipGuard);
    }

    void armTaskSaveInjector(final Runnable injector) {
      this.taskSaveInjector.set(injector);
    }

    @Override
    protected void onBeforeTaskSaveForTest(final Long taskId) {
      // One-Shot: der Verlierer loest den Gewinner aus; ein zweiter Durchlauf sieht null -> No-Op.
      final Runnable inj = taskSaveInjector.getAndSet(null);
      if (inj != null) {
        inj.run();
      }
    }
  }

  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @EnableTransactionManagement
  @EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    FlywayAutoConfiguration.class,
    KsfPersistenceAutoConfiguration.class,
    KsfIntegrationAutoConfiguration.class,
    AopAutoConfiguration.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "comment-concurrency-it";
    }

    @Bean
    UserStateService userStateService(final TaskUserStateQueryRepository repo) {
      return new UserStateService(repo);
    }

    @Bean
    InboxGenerationService inboxGenerationService(final TaskInboxGenerationQueryRepository repo) {
      return new InboxGenerationService(repo);
    }

    @Bean
    TaskHistoryService taskHistoryService(
        final de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository repo) {
      return new TaskHistoryService(repo);
    }

    @Bean
    DictionaryCodeResolver dictionaryCodeResolver(
        final TaskStatusRepository taskStatusRepository,
        final TaskPriorityRepository taskPriorityRepository,
        final TaskDirectionRepository taskDirectionRepository,
        final NotificationTypeRepository notificationTypeRepository,
        final NotificationSeverityRepository notificationSeverityRepository,
        final TagSeverityRepository tagSeverityRepository) {
      return new DictionaryCodeResolver(
          taskStatusRepository,
          taskPriorityRepository,
          taskDirectionRepository,
          notificationTypeRepository,
          notificationSeverityRepository,
          tagSeverityRepository);
    }

    @Bean
    RecordingNotificationPushPort notificationPushPort() {
      return new RecordingNotificationPushPort();
    }

    @Bean
    RecordingInboxSignalPort inboxSignalPort() {
      return new RecordingInboxSignalPort();
    }

    @Bean
    NotificationService notificationService(
        final BaseTaskNotificationRepository notificationRepository,
        final DictionaryCodeResolver resolver,
        final RecordingNotificationPushPort pushPort) {
      return new NotificationService(notificationRepository, resolver, pushPort);
    }

    @Bean
    InboxSignalService inboxSignalService(final RecordingInboxSignalPort port) {
      return new InboxSignalService(port);
    }

    @Bean
    AuditTimeConverter auditTimeConverter() {
      return new AuditTimeConverter("Europe/Berlin");
    }

    @Bean
    UserAccessor userAccessor() {
      return () -> {
        final UUID ref = ACTOR_PER_THREAD.get();
        return new AuthUser(ref, ref + "@brasi.de");
      };
    }

    @Bean
    TaskMembershipGuard membershipGuard(
        final TaskParticipantQueryRepository participantQueryRepository) {
      return new TaskMembershipGuard(participantQueryRepository);
    }

    /**
     * Stub-PermissionEvaluator: gewaehrt TASK_WRITE (AssigneeService-Handover-Autz-Grenze inert).
     */
    @Bean
    io.keel.framework.api.security.PermissionEvaluator permissionEvaluator() {
      return new io.keel.framework.api.security.PermissionEvaluator() {
        @Override
        public boolean hasAnyRole(final String... expectedRoles) {
          return false;
        }

        @Override
        public boolean hasRole(final String expectedRole) {
          return false;
        }

        @Override
        public java.util.List<String> getUserRoles() {
          return java.util.List.of();
        }

        @Override
        public boolean isOwner(final io.keel.framework.api.model.NovaEntity<?> entity) {
          return false;
        }

        @Override
        public boolean hasExactAuthority(final String authority) {
          return de.brasi.nova.taskmanagement.security.TaskManagementPermissions.TASK_WRITE.equals(
              authority);
        }
      };
    }

    @Bean
    AssigneeService assigneeService(
        final BaseTaskRepository taskRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final UserStateService userStateService,
        final NotificationService notificationService,
        final InboxSignalService inboxSignalService,
        final UserReadRepository userReadRepository,
        final UserAccessor userAccessor,
        final InboxGenerationService inboxGenerationService,
        final TaskHandoverQueryRepository handoverRepository,
        final BaseTaskHandoverTargetRepository handoverTargetRepository,
        final TaskHistoryService taskHistoryService,
        final TaskMembershipGuard membershipGuard,
        final io.keel.framework.api.security.PermissionEvaluator permissionEvaluator) {
      return new AssigneeService(
          taskRepository,
          participantQueryRepository,
          userStateQueryRepository,
          userStateService,
          notificationService,
          inboxSignalService,
          userReadRepository,
          userAccessor,
          inboxGenerationService,
          handoverRepository,
          handoverTargetRepository,
          taskHistoryService,
          membershipGuard,
          permissionEvaluator);
    }

    @Bean
    TestCommentService testCommentService(
        final BaseTaskRepository taskRepository,
        final BaseTaskCommentRepository commentRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final NotificationService notificationService,
        final InboxSignalService inboxSignalService,
        final InboxGenerationService inboxGenerationService,
        final UserReadRepository userReadRepository,
        final UserAccessor userAccessor,
        final AuditTimeConverter auditTimeConverter,
        final TaskMembershipGuard membershipGuard) {
      return new TestCommentService(
          taskRepository,
          commentRepository,
          userStateQueryRepository,
          participantQueryRepository,
          notificationService,
          inboxSignalService,
          inboxGenerationService,
          userReadRepository,
          userAccessor,
          auditTimeConverter,
          membershipGuard);
    }
  }
}
