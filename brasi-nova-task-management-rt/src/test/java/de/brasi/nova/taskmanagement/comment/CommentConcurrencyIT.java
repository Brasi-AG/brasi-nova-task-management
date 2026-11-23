package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.attachment.AttachmentContentProvider;
import de.brasi.nova.taskmanagement.attachment.AttachmentModelMapper;
import de.brasi.nova.taskmanagement.attachment.AttachmentProperties;
import de.brasi.nova.taskmanagement.attachment.AttachmentQueryService;
import de.brasi.nova.taskmanagement.attachment.AttachmentTaskAccess;
import de.brasi.nova.taskmanagement.attachment.AttachmentThumbnailGenerator;
import de.brasi.nova.taskmanagement.attachment.AttachmentWriter;
import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.division.DivisionMentionItWiring;
import de.brasi.nova.taskmanagement.division.DivisionMentionResolver;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.AssigneeService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.participant.MarkerService;
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
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskCommentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
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
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
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
 *   <li><b>Aufnahme erwaehnter Nicht-Mitglieder (Intake #156):</b> {@link
 *       #createComment_mit_aufnahme_verliert_version_voller_rollback} belegt, dass die Aufnahme
 *       (Teilnehmer- und State-Zeile) vor dem TASK-save liegt und bei verlorenem Versions-Wettlauf
 *       mit zurueckgerollt wird; {@link
 *       #createComment_mit_aufnahme_vs_updateAssignees_kein_deadlock} laesst Kommentar mit Aufnahme
 *       und updateAssignees parallel laufen (kein Deadlock, alles oder nichts).
 *   <li><b>Gesehen-Marker (Review #150):</b> {@link
 *       #createComment_vs_markSeen_kommentar_nach_seen_ist_neu} laesst {@code markSeen} am Seam vor
 *       dem TASK-save vollstaendig committen; der Kommentar entsteht danach und ist fuer den
 *       Gesehen-Markierenden neu. Vor dem Fix war der Kommentar an dieser Stelle schon gestempelt
 *       (aelter als {@code SEEN_AT}) und wurde nach dem Commit faelschlich als gesehen geliefert.
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

  /** Erwaehnter Mandanten-Nutzer, der noch kein Mitglied ist (Intake #156: wird aufgenommen). */
  private static final UUID MENTIONED_REF = UUID.fromString("00000059-00e0-0059-0000-000000000595");

  /**
   * Zwei ausgetretene Personen, die BEIDE Aufrufe schreiben (Intake #156). Die HashSet-Reihenfolge
   * (HIGH vor LOW) laeuft der UUID-Sortierung entgegen, eine unsortierte Verarbeitung in
   * updateAssignees saehe also zuerst HIGH, die sortierte Aufnahme im Kommentar zuerst LOW.
   */
  private static final UUID EX_LOW = UUID.fromString("00000059-00f0-0059-0000-000000000595");

  private static final UUID EX_HIGH = UUID.fromString("00000059-00f1-0059-0000-000000000595");

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

  // Aufnahme-Verlierer-Aufgabe (mit Seam, Intake #156).
  private static final long TASK_ADD_LOSER_ID = 595103L;
  private static final UUID TASK_ADD_LOSER_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000595103");
  // Aufnahme-Deadlock-Beweis-Aufgabe (ohne Seam, Intake #156).
  private static final long TASK_ADD_PARALLEL_ID = 595104L;
  private static final UUID TASK_ADD_PARALLEL_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000595104");

  // Reihenfolge-Beweis-Aufgabe: zwei ausgetretene Personen, gleichzeitig erwaehnt und zugewiesen.
  private static final long TASK_ORDER_ID = 595105L;
  private static final UUID TASK_ORDER_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000595105");

  // Gesehen-Marker-Aufgabe (mit Seam, Review #150).
  private static final long TASK_SEEN_ID = 595106L;
  private static final UUID TASK_SEEN_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000595106");

  private static final long WINNER_JOIN_TIMEOUT_MS = 60_000L;

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TestCommentService commentService;
  @Autowired private TestAssigneeService assigneeService;
  @Autowired private MarkerService markerService;
  @Autowired private CommentQueryService commentQueryService;
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
    seedBenutzer(jt, 595504L, MENTIONED_REF, "mentioned@brasi.de");
    seedBenutzer(jt, 595505L, EX_LOW, "ex-low@brasi.de");
    seedBenutzer(jt, 595506L, EX_HIGH, "ex-high@brasi.de");

    // Deadlock-Beweis-Aufgabe: ACTOR (Creator+Assignee) + ASSIGNEE (Assignee) aktiv.
    insertTask(jt, TASK_NODEADLOCK_ID, TASK_NODEADLOCK_UUID, "IT-Concurrency-NoDeadlock");
    insertActiveAssignee(jt, 595201L, TASK_NODEADLOCK_ID, ACTOR_REF, true);
    insertActiveAssignee(jt, 595202L, TASK_NODEADLOCK_ID, ASSIGNEE_REF, false);

    // Versions-Verlierer-Aufgabe: ACTOR (Creator+Assignee) + ASSIGNEE (Assignee) aktiv.
    insertTask(jt, TASK_VERSION_ID, TASK_VERSION_UUID, "IT-Concurrency-Version");
    insertActiveAssignee(jt, 595203L, TASK_VERSION_ID, ACTOR_REF, true);
    insertActiveAssignee(jt, 595204L, TASK_VERSION_ID, ASSIGNEE_REF, false);

    // Aufnahme-Aufgaben (Intake #156): ACTOR (Creator+Assignee) + ASSIGNEE (Assignee) aktiv,
    // MENTIONED_REF ist kein Mitglied.
    insertTask(jt, TASK_ADD_LOSER_ID, TASK_ADD_LOSER_UUID, "IT-Concurrency-Aufnahme-Version");
    insertActiveAssignee(jt, 595205L, TASK_ADD_LOSER_ID, ACTOR_REF, true);
    insertActiveAssignee(jt, 595206L, TASK_ADD_LOSER_ID, ASSIGNEE_REF, false);
    insertTask(
        jt, TASK_ADD_PARALLEL_ID, TASK_ADD_PARALLEL_UUID, "IT-Concurrency-Aufnahme-Parallel");
    insertActiveAssignee(jt, 595207L, TASK_ADD_PARALLEL_ID, ACTOR_REF, true);
    insertActiveAssignee(jt, 595208L, TASK_ADD_PARALLEL_ID, ASSIGNEE_REF, false);

    // Reihenfolge-Beweis (Intake #156): ACTOR + ASSIGNEE aktiv, EX_LOW und EX_HIGH ausgetreten
    // (Teilnehmerzeile mit EXIT_KIND, State mit UNSUBSCRIBED_AT).
    insertTask(jt, TASK_ORDER_ID, TASK_ORDER_UUID, "IT-Concurrency-Reihenfolge");
    insertActiveAssignee(jt, 595209L, TASK_ORDER_ID, ACTOR_REF, true);
    insertActiveAssignee(jt, 595210L, TASK_ORDER_ID, ASSIGNEE_REF, false);
    insertExitedAssignee(jt, 595211L, TASK_ORDER_ID, EX_LOW);
    insertExitedAssignee(jt, 595212L, TASK_ORDER_ID, EX_HIGH);
    insertExitedUserState(jt, 595401L, TASK_ORDER_ID, EX_LOW);
    insertExitedUserState(jt, 595402L, TASK_ORDER_ID, EX_HIGH);

    // Gesehen-Marker (Review #150): ACTOR kommentiert, ASSIGNEE markiert als gesehen.
    insertTask(jt, TASK_SEEN_ID, TASK_SEEN_UUID, "IT-Concurrency-Gesehen");
    insertActiveAssignee(jt, 595213L, TASK_SEEN_ID, ACTOR_REF, true);
    insertActiveAssignee(jt, 595214L, TASK_SEEN_ID, ASSIGNEE_REF, false);
  }

  @BeforeEach
  void resetState() {
    winnerError.set(null);
    winnerTimedOut.set(false);
    inboxSignalPort.clear();
    notificationPushPort.clear();
    ACTOR_PER_THREAD.set(ACTOR_REF);
    commentService.armTaskSaveInjector(null);
    assigneeService.disarmOrderBarrier();
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
          new CommentCreateInput("Parallel, mit Mention.", null, List.of(ASSIGNEE_REF), List.of()));
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
                      new CommentCreateInput(
                          "Verliert den Versions-Race.", null, List.of(), List.of())))
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

  // ---- Intake #156: Aufnahme erwaehnter Nicht-Mitglieder ----------------------------------------

  @Test
  @DisplayName(
      "createComment_mit_aufnahme_verliert_version_voller_rollback (Intake #156): die Aufnahme der"
          + " erwaehnten Person (Teilnehmer- und State-Zeile) liegt vor dem TASK-save; verliert der"
          + " Kommentar den Versions-Wettlauf, gibt es 409 und KEINE Teilpersistenz (weder"
          + " Teilnehmer noch State noch MENTION noch Kommentar)")
  void createComment_mit_aufnahme_verliert_version_voller_rollback() {
    final int versionBefore = taskVersion(TASK_ADD_LOSER_UUID);
    final int commentsBefore = commentRowCount(TASK_ADD_LOSER_ID, ACTOR_REF);

    // Am Seam (nach der Aufnahme, vor dem TASK-save) bumpt ein eigener Thread auf einer
    // unabhaengigen, sofort committenden Verbindung die TASK-VERSION (im Thread des Verlierers
    // lief der Update in dessen Transaktion und wuerde mit zurueckgerollt): der versionierte save
    // des Verlierers trifft die veraltete VERSION.
    commentService.armTaskSaveInjector(
        () ->
            runWinnerOnSeparateThread(
                () ->
                    jdbcTemplate.update(
                        "UPDATE [dbo].[TASK] SET VERSION = VERSION + 1 WHERE ID = ?",
                        TASK_ADD_LOSER_ID)));

    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ACTOR_REF);
    try {
      assertThatThrownBy(
              () ->
                  commentService.createComment(
                      TASK_ADD_LOSER_UUID,
                      new CommentCreateInput(
                          "Verliert den Versions-Race.", null, List.of(MENTIONED_REF), List.of())))
          .as("Versions-Verlierer -> 409 (BusinessRuleException)")
          .isInstanceOf(BusinessRuleException.class);
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
    }

    assertThat(winnerTimedOut.get()).as("der unabhaengige Bump beendete rechtzeitig").isFalse();
    assertThat(winnerError.get()).as("der unabhaengige Bump committete fehlerfrei").isNull();
    assertThat(taskVersion(TASK_ADD_LOSER_UUID))
        .as("nur der unabhaengige Bump, der Verlierer hat die Aufgabe nicht gespeichert")
        .isEqualTo(versionBefore + 1);
    assertThat(commentRowCount(TASK_ADD_LOSER_ID, ACTOR_REF))
        .as("kein Kommentar persistiert")
        .isEqualTo(commentsBefore);
    assertThat(participantRowCount(TASK_ADD_LOSER_ID, MENTIONED_REF))
        .as("die Aufnahme wurde zurueckgerollt (keine Teilnehmerzeile)")
        .isZero();
    assertThat(userStateRowCount(TASK_ADD_LOSER_ID, MENTIONED_REF))
        .as("kein persoenlicher State")
        .isZero();
    assertThat(notificationCountOfRecipient(TASK_ADD_LOSER_ID, MENTIONED_REF))
        .as("keine MENTION gespeichert")
        .isZero();
  }

  @Test
  @DisplayName(
      "createComment_mit_aufnahme_vs_updateAssignees_kein_deadlock (Intake #156): Kommentar mit"
          + " Erwaehnung eines Nicht-Mitglieds und updateAssignees mit ueberlappenden Teilnehmern"
          + " laufen parallel -> kein Deadlock/Timeout; ein Fehler ist hoechstens ein Versions-"
          + " konflikt (409) mit vollem Rollback, die Aufnahme ist alles oder nichts")
  void createComment_mit_aufnahme_vs_updateAssignees_kein_deadlock() throws Exception {
    final AtomicReference<Throwable> winnerErr = new AtomicReference<>();
    final Thread winner =
        new Thread(
            () -> {
              TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
              ACTOR_PER_THREAD.set(ACTOR_REF);
              try {
                assigneeService.updateAssignees(
                    TASK_ADD_PARALLEL_UUID,
                    new AssigneeUpdateInput(Set.of(ACTOR_REF, ASSIGNEE_REF, TARGET_WIN)));
              } catch (final Throwable t) {
                winnerErr.set(t);
              } finally {
                ACTOR_PER_THREAD.remove();
                TenantContextHolder.clear();
              }
            },
            "concurrency-updateAssignees-aufnahme");

    final AtomicReference<Throwable> commentErr = new AtomicReference<>();
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ACTOR_REF);
    winner.start();
    try {
      commentService.createComment(
          TASK_ADD_PARALLEL_UUID,
          new CommentCreateInput(
              "Parallel, mit Aufnahme.", null, List.of(MENTIONED_REF, ASSIGNEE_REF), List.of()));
    } catch (final Throwable t) {
      commentErr.set(t);
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
    }
    winner.join(WINNER_JOIN_TIMEOUT_MS);

    assertThat(winner.isAlive())
        .as("updateAssignees-Thread beendete rechtzeitig (kein Deadlock/Wartezyklus)")
        .isFalse();
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

    // Alles oder nichts: Kommentar, Aufnahme und MENTION stehen zusammen oder gar nicht da.
    final int commentRows = commentRowCount(TASK_ADD_PARALLEL_ID, ACTOR_REF);
    if (commentErr.get() == null) {
      assertThat(commentRows).as("erfolgreicher createComment -> genau 1 Kommentar").isEqualTo(1);
      assertThat(participantRowCount(TASK_ADD_PARALLEL_ID, MENTIONED_REF))
          .as("die erwaehnte Person ist aufgenommen")
          .isEqualTo(1);
      assertThat(notificationCountOfRecipient(TASK_ADD_PARALLEL_ID, MENTIONED_REF))
          .as("genau die MENTION, keine PARTICIPANT")
          .isEqualTo(1);
    } else {
      assertThat(commentRows).as("fehlgeschlagener createComment -> kein Kommentar").isZero();
      assertThat(participantRowCount(TASK_ADD_PARALLEL_ID, MENTIONED_REF))
          .as("fehlgeschlagener createComment -> keine Aufnahme (Rollback)")
          .isZero();
      assertThat(notificationCountOfRecipient(TASK_ADD_PARALLEL_ID, MENTIONED_REF)).isZero();
    }
  }

  @Test
  @DisplayName(
      "createComment_aufnahme_vs_updateAssignees_gleiche_sperrreihenfolge (Intake #156): zwei"
          + " ausgetretene Personen werden gleichzeitig erwaehnt (Aufnahme im Kommentar) und per PUT"
          + " zugewiesen - beide Aufrufe schreiben dieselben Zeilen. Beide werden vor dem ersten"
          + " Write synchronisiert; bei unterschiedlicher Reihenfolge sperrten sie ueberkreuz"
          + " (Deadlock). Erwartet: genau ein Aufruf gewinnt, der andere scheitert mit einem"
          + " Versionskonflikt (409) und vollem Rollback, der Endzustand ist konsistent")
  void createComment_aufnahme_vs_updateAssignees_gleiche_sperrreihenfolge() throws Exception {
    assigneeService.armOrderBarrier();
    final AtomicReference<Throwable> assignErr = new AtomicReference<>();
    final Thread assigner =
        new Thread(
            () -> {
              TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
              ACTOR_PER_THREAD.set(ACTOR_REF);
              try {
                assigneeService.updateAssignees(
                    TASK_ORDER_UUID,
                    new AssigneeUpdateInput(Set.of(ACTOR_REF, ASSIGNEE_REF, EX_LOW, EX_HIGH)));
              } catch (final Throwable t) {
                assignErr.set(t);
              } finally {
                ACTOR_PER_THREAD.remove();
                TenantContextHolder.clear();
              }
            },
            "concurrency-updateAssignees-reihenfolge");

    final AtomicReference<Throwable> commentErr = new AtomicReference<>();
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ACTOR_REF);
    assigner.start();
    try {
      commentService.createComment(
          TASK_ORDER_UUID,
          new CommentCreateInput(
              "Beide ausgetreten, beide erwaehnt.", null, List.of(EX_HIGH, EX_LOW), List.of()));
    } catch (final Throwable t) {
      commentErr.set(t);
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
    }
    assigner.join(WINNER_JOIN_TIMEOUT_MS);

    assertThat(assigner.isAlive())
        .as("updateAssignees-Thread beendete rechtzeitig (kein Deadlock/Wartezyklus)")
        .isFalse();
    // Genau ein Gewinner: beide lasen dieselbe TASK-VERSION, der versionierte save laesst nur einen
    // durch. Der Verlierer scheitert mit BusinessRuleException UND OptimisticLockingFailure als
    // Ursache - ein Deadlock-Opfer haette eine andere Ursache.
    final boolean commentWon = commentErr.get() == null;
    final boolean assignWon = assignErr.get() == null;
    assertThat(commentWon ^ assignWon)
        .as(
            "genau ein Aufruf gewinnt (Kommentar: %s, Zuweisung: %s)",
            commentErr.get(), assignErr.get())
        .isTrue();
    final Throwable loser = commentWon ? assignErr.get() : commentErr.get();
    assertThat(loser)
        .as("Verlierer: Versionskonflikt (409), nie Deadlock")
        .isInstanceOf(BusinessRuleException.class);
    assertThat(
            causeChainContains(
                loser, org.springframework.dao.OptimisticLockingFailureException.class))
        .as("Ursache des Verlierers: %s", loser)
        .isTrue();

    for (final UUID ref : List.of(EX_LOW, EX_HIGH)) {
      final Map<String, Object> row =
          jdbcTemplate.queryForMap(
              "SELECT ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND FROM [dbo].[TASK_PARTICIPANT]"
                  + " WHERE TASK_ID=? AND PARTICIPANT_REF=?",
              TASK_ORDER_ID,
              ref);
      assertThat(row.get("EXIT_KIND")).as("%s ist wieder aktiv", ref).isNull();
      assertThat(row.get("PARTICIPANT_FLAG")).isEqualTo(true);
      if (commentWon) {
        assertThat(row.get("ASSIGNEE_FLAG"))
            .as("Kommentar gewann: reiner Teilnehmer")
            .isEqualTo(false);
        assertThat(notificationCountByCode(TASK_ORDER_ID, ref, "MENTION")).isEqualTo(1);
        assertThat(notificationCountByCode(TASK_ORDER_ID, ref, "ASSIGN")).isZero();
        assertThat(notificationCountByCode(TASK_ORDER_ID, ref, "PARTICIPANT")).isZero();
      } else {
        assertThat(row.get("ASSIGNEE_FLAG")).as("Zuweisung gewann: Assignee").isEqualTo(true);
        assertThat(notificationCountByCode(TASK_ORDER_ID, ref, "ASSIGN")).isEqualTo(1);
        assertThat(notificationCountByCode(TASK_ORDER_ID, ref, "MENTION")).isZero();
      }
    }
    assertThat(commentRowCount(TASK_ORDER_ID, ACTOR_REF))
        .as("Kommentar nur, wenn er gewann (sonst voller Rollback)")
        .isEqualTo(commentWon ? 1 : 0);
  }

  // ---- Review #150: Gesehen-Marker gegen laufenden Kommentar ------------------------------------

  @Test
  @DisplayName(
      "createComment_vs_markSeen_kommentar_nach_seen_ist_neu (Review #150): markSeen committet,"
          + " waehrend der Kommentar am Seam vor dem TASK-save offen ist; der Kommentar wird erst"
          + " danach gestempelt und ist fuer den Gesehen-Markierenden neu (isNew=true)")
  void createComment_vs_markSeen_kommentar_nach_seen_ist_neu() {
    // Am Seam haelt createComment seine Transaktion offen; ASSIGNEE markiert auf eigenem Thread als
    // gesehen und committet, bevor der Kommentar fortsetzt.
    commentService.armTaskSaveInjector(
        () ->
            runWinnerOnSeparateThread(
                () -> {
                  ACTOR_PER_THREAD.set(ASSIGNEE_REF);
                  markerService.markSeen(TASK_SEEN_UUID);
                }));

    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ACTOR_REF);
    final UUID commentId;
    try {
      commentId =
          commentService
              .createComment(
                  TASK_SEEN_UUID,
                  new CommentCreateInput(
                      "Entsteht waehrend des Gesehen-Markierens.", null, List.of(), List.of()))
              .id();
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
    }

    assertThat(winnerTimedOut.get()).as("markSeen beendete rechtzeitig (kein Warten)").isFalse();
    assertThat(winnerError.get()).as("markSeen committet fehlerfrei").isNull();

    final LocalDateTime seenAt =
        jdbcTemplate.queryForObject(
            "SELECT SEEN_AT FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID=? AND USER_REF=?",
            LocalDateTime.class,
            TASK_SEEN_ID,
            ASSIGNEE_REF);
    final LocalDateTime createdAt =
        jdbcTemplate.queryForObject(
            "SELECT ANGELEGT_AM FROM [dbo].[TASK_COMMENT] WHERE EXTERNALID=?",
            LocalDateTime.class,
            commentId);
    assertThat(seenAt).as("SEEN_AT gesetzt").isNotNull();
    assertThat(createdAt).as("Kommentar nach SEEN_AT gestempelt").isAfter(seenAt);

    // Sicht des Gesehen-Markierenden ueber den echten Lesepfad.
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ASSIGNEE_REF);
    try {
      assertThat(commentQueryService.getComments(TASK_SEEN_UUID))
          .filteredOn(c -> c.id().equals(commentId))
          .singleElement()
          .as("der nach SEEN_AT committete Kommentar ist fuer ASSIGNEE neu")
          .extracting(c -> c.isNew())
          .isEqualTo(true);
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
    }
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

  private int notificationCountByCode(final long taskId, final UUID ref, final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
            + " JOIN [dbo].[DIC_NOTIFICATION_TYPE] d ON n.TYPE_ID = d.ID"
            + " WHERE n.TASK_ID=? AND n.RECIPIENT_REF=? AND d.CODE=?",
        Integer.class,
        taskId,
        ref,
        code);
  }

  private static boolean causeChainContains(
      final Throwable error, final Class<? extends Throwable> type) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }

  private int participantRowCount(final long taskId, final UUID ref) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?",
        Integer.class,
        taskId,
        ref);
  }

  private int userStateRowCount(final long taskId, final UUID ref) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID=? AND USER_REF=?",
        Integer.class,
        taskId,
        ref);
  }

  private int notificationCountOfRecipient(final long taskId, final UUID ref) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] WHERE TASK_ID=? AND RECIPIENT_REF=?",
        Integer.class,
        taskId,
        ref);
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

  private static void insertExitedAssignee(
      final JdbcTemplate jt, final long id, final long taskId, final UUID ref) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON,"
            + " ANGELEGT_AM) VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 1, 1,"
            + " 'UNSUBSCRIBED', GETDATE(), 'it-seed', GETDATE())",
        id,
        id,
        TENANT_ID,
        taskId,
        ref,
        TENANT_ID);
  }

  private static void insertExitedUserState(
      final JdbcTemplate jt, final long id, final long taskId, final UUID ref) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM,"
            + " EXTERNALID, VERSION, TENANT_ID, TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED,"
            + " IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT) VALUES (?, 'ACTIVE', 'it-seed', GETDATE(),"
            + " NEWID(), 1, ?, ?, ?, ?, 0, 0, 0, GETDATE())",
        id,
        id,
        TENANT_ID,
        taskId,
        ref,
        TENANT_ID);
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

  /**
   * Test-Subklasse von {@link AssigneeService}: synchronisiert updateAssignees und die Aufnahme per
   * Erwaehnung (beide ueber ihre Seams) einmalig je Thread VOR dem ersten Write an einer Barriere
   * (Intake #156, Reihenfolge-Beweis). Ohne scharfe Barriere sind die Haken ohne Wirkung.
   */
  static class TestAssigneeService extends AssigneeService {

    private final AtomicReference<CyclicBarrier> orderBarrier = new AtomicReference<>();
    private final Set<Thread> synchronizedThreads =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    TestAssigneeService(
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
      super(
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

    void armOrderBarrier() {
      synchronizedThreads.clear();
      orderBarrier.set(new CyclicBarrier(2));
    }

    void disarmOrderBarrier() {
      orderBarrier.set(null);
      synchronizedThreads.clear();
    }

    private void synchronizeBeforeFirstWrite() {
      final CyclicBarrier barrier = orderBarrier.get();
      if (barrier == null || !synchronizedThreads.add(Thread.currentThread())) {
        return;
      }
      try {
        barrier.await(30, TimeUnit.SECONDS);
      } catch (final Exception e) {
        throw new IllegalStateException("Barriere vor dem ersten Write nicht erreicht.", e);
      }
    }

    @Override
    protected void onBeforeAssigneeAddForTest(final Long taskId, final UUID ref) {
      synchronizeBeforeFirstWrite();
    }

    @Override
    protected void onBeforeParticipantSaveForTest(final Long taskId, final UUID ref) {
      synchronizeBeforeFirstWrite();
    }
  }

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
        final TaskMembershipGuard membershipGuard,
        final AssigneeService assigneeService,
        final AttachmentWriter attachmentWriter,
        final AttachmentModelMapper attachmentModelMapper,
        final AttachmentProperties attachmentProperties,
        final DivisionMentionResolver divisionMentionResolver) {
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
          membershipGuard,
          new de.brasi.nova.taskmanagement.notification.NotificationMuteFilter(
              userStateQueryRepository),
          assigneeService,
          attachmentWriter,
          attachmentModelMapper,
          attachmentProperties,
          divisionMentionResolver);
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
  // Intake #190: CommentService haengt am DivisionMentionResolver (Paket division).
  @org.springframework.context.annotation.Import(DivisionMentionItWiring.class)
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
    TestAssigneeService assigneeService(
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
      return new TestAssigneeService(
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
    MarkerService markerService(
        final TaskKeysetQueryRepository taskRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final UserStateService userStateService,
        final InboxSignalService inboxSignalService,
        final InboxGenerationService inboxGenerationService,
        final UserAccessor userAccessor,
        final TaskMembershipGuard membershipGuard) {
      return new MarkerService(
          taskRepository,
          userStateQueryRepository,
          userStateService,
          inboxSignalService,
          inboxGenerationService,
          userAccessor,
          membershipGuard);
    }

    /** Intake #152: Abbildung der Anhaenge (Links ohne oeffentliche Basis). */
    @Bean
    AttachmentModelMapper attachmentModelMapper(final AuditTimeConverter auditTimeConverter) {
      return new AttachmentModelMapper(auditTimeConverter, "");
    }

    /** Intake #152: Anhang-Konfiguration ohne Speicherpfad; dieser IT schreibt keine Dateien. */
    @Bean
    AttachmentProperties attachmentProperties() {
      return new AttachmentProperties(
          new AttachmentProperties.Storage(null), List.of(), 20, null, null, null);
    }

    /** Intake #152: Dateispeicher als Mock; dieser IT liest und schreibt keine Anhang-Dateien. */
    @Bean
    AttachmentContentProvider attachmentContentProvider() {
      return org.mockito.Mockito.mock(AttachmentContentProvider.class);
    }

    /**
     * Intake #152: Lesedienst der Anhaenge fuer die Kommentarliste ({@code getComments} liest die
     * Kommentar-Anhaenge ueber ihn); die Vorschau-Erzeugung braucht die Liste nicht (Mock).
     */
    @Bean
    AttachmentQueryService attachmentQueryService(
        final UserAccessor userAccessor,
        final BaseTaskRepository taskRepository,
        final TaskMembershipGuard membershipGuard,
        final TaskAttachmentQueryRepository attachmentRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final AttachmentContentProvider attachmentContentProvider,
        final AttachmentModelMapper attachmentModelMapper,
        final AttachmentProperties attachmentProperties) {
      return new AttachmentQueryService(
          userAccessor,
          new AttachmentTaskAccess(taskRepository, membershipGuard),
          attachmentRepository,
          participantQueryRepository,
          attachmentContentProvider,
          org.mockito.Mockito.mock(AttachmentThumbnailGenerator.class),
          attachmentModelMapper,
          attachmentProperties);
    }

    @Bean
    CommentQueryService commentQueryService(
        final BaseTaskRepository taskRepository,
        final TaskCommentQueryRepository commentQueryRepository,
        final UserAccessor userAccessor,
        final AuditTimeConverter auditTimeConverter,
        final TaskMembershipGuard membershipGuard,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final AttachmentQueryService attachmentQueryService) {
      return new CommentQueryService(
          taskRepository,
          commentQueryRepository,
          userAccessor,
          auditTimeConverter,
          membershipGuard,
          userStateQueryRepository,
          attachmentQueryService);
    }

    /**
     * Intake #152: Writer der Anhaenge fuer den Konstruktor des CommentService; dieser IT legt nur
     * Kommentare ohne Anhaenge an, der Writer wird nie aufgerufen.
     */
    @Bean
    AttachmentWriter attachmentWriter(
        final TaskAttachmentQueryRepository attachmentRepository,
        final AttachmentContentProvider attachmentContentProvider) {
      return new AttachmentWriter(attachmentRepository, attachmentContentProvider);
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
        final TaskMembershipGuard membershipGuard,
        final AssigneeService assigneeService,
        final AttachmentWriter attachmentWriter,
        final AttachmentModelMapper attachmentModelMapper,
        final AttachmentProperties attachmentProperties,
        final DivisionMentionResolver divisionMentionResolver) {
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
          membershipGuard,
          assigneeService,
          attachmentWriter,
          attachmentModelMapper,
          attachmentProperties,
          divisionMentionResolver);
    }
  }
}
