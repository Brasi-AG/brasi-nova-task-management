package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.push.InboxSignalPort;
import de.brasi.nova.taskmanagement.push.NotificationPushEnvelope;
import de.brasi.nova.taskmanagement.push.NotificationPushPort;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskNotificationRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository;
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
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.PermissionEvaluator;
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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Real-MSSQL-Nebenlaeufigkeits-IT fuer {@code AssigneeService#removeParticipant} (Review #145 Runde
 * 2): zwei gleichzeitige Entfernungen VERSCHIEDENER Personen derselben Aufgabe.
 *
 * <p><b>Befund:</b> stand die Leser-Lesung ({@code collectRemovalReaders}: alle aktiven States und
 * Archivleser der Aufgabe) hinter den eigenen Teilnehmer- und State-Schreibungen, wartete unter
 * sperrendem READ COMMITTED jede Transaktion beim Lesen auf die X-gesperrte Zeile der anderen:
 * Deadlock 1205 (HTTP 500), bevor der TASK-Versionskonflikt (409) greift. Seit der Korrektur stehen
 * alle Reads vor der ersten Schreibung.
 *
 * <p><b>Erzwungener Ablauf</b> (zwei Sync-Punkte, kein Zeitablauf):
 *
 * <ol>
 *   <li><b>Sync 1</b> am Seam {@code onBeforeRemoveMutationForTest} (nach allen Reads, vor der
 *       ersten Schreibung): beide Transaktionen haben die Aufgabe mit derselben VERSION gelesen und
 *       halten keine Schreibsperren.
 *   <li>Beide schreiben ihre Teilnehmer- und State-Zeile (X-Sperren auf je eigenen Zeilen).
 *   <li><b>Sync 2</b> am Historieneintrag ({@link TestTaskHistoryService#record}, die erste
 *       Schreibung nach der Mitgliedschaft): beide halten jetzt ihre X-Sperren. Die Gewinnerin
 *       laeuft weiter; die Verliererin wartet (mit ihren X-Sperren), bis die Gewinnerin fertig ist.
 *   <li>Die Gewinnerin schreibt Historie und Benachrichtigung, speichert die Aufgabe (VERSION+1),
 *       bumpt und committet: 204. Sie liest dabei keine Zeile der Verliererin mehr; mit der alten
 *       Reihenfolge laege hier ihre Leser-Lesung und bliebe an der State-Zeile der Verliererin
 *       haengen (die Verliererin wartet dann bis zum Timeout und laeuft selbst in die Lesung:
 *       Deadlock 1205; der Test wird so oder so rot, nie gruen).
 *   <li>Die Verliererin laeuft weiter und trifft beim versionierten TASK-save die veraltete
 *       VERSION: {@link BusinessRuleException} mit {@link OptimisticLockingFailureException} (409),
 *       voller Rollback.
 * </ol>
 *
 * <p>Der Seam erzwingt damit die Variante <b>eine 204 und eine 409</b>, nie die serielle Variante
 * (beide 204), denn die Verliererin hat die Aufgabe vor dem Commit der Gewinnerin gelesen. Die
 * Sync-Punkte haben Timeouts nur als Sicherung gegen Haengen; ein Timeout macht den Test rot.
 *
 * <p>Voraussetzung: die Wegwerf-DB laeuft mit sperrendem READ COMMITTED (kein RCSI), sonst warten
 * Lesungen nicht auf Schreibsperren und der Test bewiese nichts; das prueft der Test zuerst.
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = ParticipantRemoveConcurrencyIT.ItConfig.class,
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
      // Zwei offene Tx-Verbindungen plus je eine fuer die Sequenz-Vergabe (REQUIRES_NEW).
      "spring.datasource.hikari.maximum-pool-size=6",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class ParticipantRemoveConcurrencyIT {

  private static final long TENANT_ID = 595L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000595");

  /** Ersteller; entfernt in der Verlierer-Transaktion TARGET_LOSE. */
  private static final UUID CREATOR_REF = UUID.fromString("00000059-00a0-0059-0000-000000000595");

  /** Aktiver Assignee; entfernt in der Gewinner-Transaktion TARGET_WIN. */
  private static final UUID ACTOR2_REF = UUID.fromString("00000059-00a2-0059-0000-000000000595");

  /** Ziel der Verliererin: aktiver Assignee mit State. */
  private static final UUID TARGET_LOSE = UUID.fromString("00000059-00c0-0059-0000-000000000595");

  /** Ziel der Gewinnerin: reiner Beteiligter mit State. */
  private static final UUID TARGET_WIN = UUID.fromString("00000059-00d0-0059-0000-000000000595");

  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_NORMAL = 202L;
  private static final long DIRECTION = 301L;

  private static final long TASK_ID = 595101L;
  private static final UUID TASK_UUID = UUID.fromString("00000059-0000-0059-aaaa-000000595101");

  /** Historientext des Entfernens (AssigneeService#removeParticipant). */
  private static final String REMOVE_HISTORY_TEXT = "hat einen Beteiligten entfernt";

  /** Sicherung gegen Haengen an den Sync-Punkten; ein Ablauf macht den Test rot. */
  private static final long GATE_TIMEOUT_MS = 30_000L;

  private static final long JOIN_TIMEOUT_MS = 120_000L;

  private static final String WINNER = "winner";
  private static final String LOSER = "loser";

  /** Thread-lokaler Akteur (der UserAccessor liest ihn). */
  private static final ThreadLocal<UUID> ACTOR_PER_THREAD =
      ThreadLocal.withInitial(() -> CREATOR_REF);

  /** Thread-lokale Rolle fuer Sync 2 (Gewinnerin laeuft weiter, Verliererin wartet). */
  private static final ThreadLocal<String> ROLE_PER_THREAD = new ThreadLocal<>();

  /** Sync-Punkte des Laufs; vom Seam und vom Historien-Haken gelesen. */
  private static final AtomicReference<Gates> GATES = new AtomicReference<>();

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TestAssigneeService service;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecordingInboxSignalPort inboxSignalPort;
  @Autowired private RecordingNotificationPushPort notificationPushPort;

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
  static void seed(@Autowired final JdbcTemplate jt) {
    seedFirma(jt, TENANT_ID, TENANT_UUID);
    seedBenutzer(jt, 595501L, CREATOR_REF, "creator@brasi.de");
    seedBenutzer(jt, 595502L, ACTOR2_REF, "actor2@brasi.de");
    seedBenutzer(jt, 595503L, TARGET_LOSE, "target-lose@brasi.de");
    seedBenutzer(jt, 595504L, TARGET_WIN, "target-win@brasi.de");

    insertTask(jt, TASK_ID, TASK_UUID, "I145-Entfernen-parallel");
    insertParticipant(jt, 595301L, TASK_ID, CREATOR_REF, true, true);
    insertParticipant(jt, 595302L, TASK_ID, ACTOR2_REF, false, true);
    insertParticipant(jt, 595303L, TASK_ID, TARGET_LOSE, false, true);
    insertParticipant(jt, 595304L, TASK_ID, TARGET_WIN, false, false);
    insertActiveUserState(jt, 595401L, TASK_ID, CREATOR_REF);
    insertActiveUserState(jt, 595402L, TASK_ID, ACTOR2_REF);
    insertActiveUserState(jt, 595403L, TASK_ID, TARGET_LOSE);
    insertActiveUserState(jt, 595404L, TASK_ID, TARGET_WIN);
  }

  @Test
  @DisplayName(
      "i145_zwei_entfernungen_verschiedener_personen_parallel: beide halten ihre X-Sperren, keine"
          + " Lesung wartet mehr auf die andere -> eine 204, eine 409 (Versionskonflikt, voller"
          + " Rollback), nie 1205/500; nur die Gewinner-Entfernung ist wirksam")
  void i145_parallel_removal_of_different_persons_204_and_409_never_deadlock() {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = DB_NAME()",
                Boolean.class))
        .as("Voraussetzung: sperrendes READ COMMITTED (kein RCSI), sonst warten Lesungen nicht")
        .isFalse();

    final int taskVersionBefore = taskVersion();
    final int loseParticipantVersionBefore = participantVersion(TARGET_LOSE);
    final int loseStateVersionBefore = userStateVersion(TARGET_LOSE);
    final List<UUID> readers = List.of(CREATOR_REF, ACTOR2_REF, TARGET_LOSE, TARGET_WIN);
    final List<Long> generationsBefore = readers.stream().map(this::inboxGeneration).toList();
    inboxSignalPort.clear();
    notificationPushPort.clear();

    final Gates gates = new Gates();
    GATES.set(gates);
    final AtomicReference<Throwable> winnerError = new AtomicReference<>();
    final AtomicReference<Throwable> loserError = new AtomicReference<>();
    final Thread winner =
        removalThread("i145-remove-winner", ACTOR2_REF, TARGET_WIN, WINNER, winnerError, gates);
    final Thread loser =
        removalThread("i145-remove-loser", CREATOR_REF, TARGET_LOSE, LOSER, loserError, gates);
    try {
      winner.start();
      loser.start();
      joinOrFail(winner);
      joinOrFail(loser);
    } finally {
      GATES.set(null);
    }

    // Ablauf wie erzwungen: beide Sync-Punkte erreicht, kein Ablauf, kein Haengen.
    assertThat(winner.isAlive() || loser.isAlive()).as("beide Threads beendet").isFalse();
    assertThat(gates.gateTimedOut.get())
        .as("beide Transaktionen erreichten beide Sync-Punkte (keine Lesung wartete)")
        .isFalse();
    assertThat(gates.loserWaitTimedOut.get())
        .as("die Gewinnerin committete, waehrend die Verliererin ihre X-Sperren hielt")
        .isFalse();
    assertThat(gates.membershipWritesReached.get())
        .as("beide hielten ihre Teilnehmer- und State-Sperren gleichzeitig")
        .isEqualTo(2);

    // Ergebnis: eine 204, eine 409 aus dem Versionskonflikt; nie 1205/500.
    assertThat(winnerError.get()).as("Gewinnerin: 204 ohne Fehler (kein Deadlock)").isNull();
    assertThat(loserError.get())
        .as("Verliererin: 409 aus dem TASK-Versionskonflikt, kein Deadlock-Opfer")
        .isInstanceOf(BusinessRuleException.class)
        .hasCauseInstanceOf(OptimisticLockingFailureException.class);

    // DB-Endzustand: genau die Gewinner-Entfernung ist wirksam.
    assertThat(taskVersion()).as("TASK-VERSION genau +1").isEqualTo(taskVersionBefore + 1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND"
                    + " PARTICIPANT_REF=? AND EXIT_KIND='UNSUBSCRIBED' AND ASSIGNEE_FLAG=0"
                    + " AND ENTITY_STATUS='ACTIVE'",
                TASK_ID,
                TARGET_WIN))
        .as("TARGET_WIN ausgetreten, Zeile ACTIVE")
        .isEqualTo(1);
    assertThat(userStateUnsubscribed(TARGET_WIN)).as("State TARGET_WIN abgemeldet").isEqualTo(1);

    // Keine halbe Mutation der Verliererin.
    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND"
                    + " PARTICIPANT_REF=? AND EXIT_KIND IS NULL AND EXIT_AT IS NULL"
                    + " AND ASSIGNEE_FLAG=1",
                TASK_ID,
                TARGET_LOSE))
        .as("TARGET_LOSE weiter aktiver Assignee")
        .isEqualTo(1);
    assertThat(participantVersion(TARGET_LOSE))
        .as("Teilnehmerzeile TARGET_LOSE unveraendert")
        .isEqualTo(loseParticipantVersionBefore);
    assertThat(userStateUnsubscribed(TARGET_LOSE)).as("State TARGET_LOSE aktiv").isZero();
    assertThat(userStateVersion(TARGET_LOSE))
        .as("State TARGET_LOSE unveraendert")
        .isEqualTo(loseStateVersionBefore);
    assertThat(historyCount(ACTOR2_REF)).as("Historie der Gewinnerin").isEqualTo(1);
    assertThat(historyCount(CREATOR_REF)).as("keine Historie der Verliererin").isZero();

    // Benachrichtigungen nur fuer die wirksame Entfernung.
    assertThat(notificationCount(TARGET_WIN)).as("REMOVED an TARGET_WIN").isEqualTo(1);
    assertThat(notificationCount(TARGET_LOSE)).as("keine Benachrichtigung an TARGET_LOSE").isZero();
    assertThat(notificationPushPort.pushedRecipients())
        .as("Push nur an TARGET_WIN")
        .containsExactly(TARGET_WIN);

    // Bump und Signal nur aus der Gewinnerin: alle Leser genau einmal.
    assertThat(inboxSignalPort.sends()).as("genau ein Signal (Gewinnerin)").hasSize(1);
    assertThat(inboxSignalPort.sends().get(0))
        .as("Signal an die entfernte Person und alle aktiven Leser")
        .containsExactlyInAnyOrderElementsOf(readers);
    for (int i = 0; i < readers.size(); i++) {
      assertThat(inboxGeneration(readers.get(i)))
          .as("Generation von %s genau einmal gebumpt", readers.get(i))
          .isEqualTo(generationsBefore.get(i) + 1);
    }
  }

  private Thread removalThread(
      final String name,
      final UUID actorRef,
      final UUID targetRef,
      final String role,
      final AtomicReference<Throwable> error,
      final Gates gates) {
    return new Thread(
        () -> {
          TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
          ACTOR_PER_THREAD.set(actorRef);
          ROLE_PER_THREAD.set(role);
          try {
            service.removeParticipant(TASK_UUID, targetRef);
          } catch (final Throwable t) {
            error.set(t);
          } finally {
            if (WINNER.equals(role)) {
              gates.winnerDone.countDown();
            }
            ROLE_PER_THREAD.remove();
            ACTOR_PER_THREAD.remove();
            TenantContextHolder.clear();
          }
        },
        name);
  }

  private static void joinOrFail(final Thread thread) {
    try {
      thread.join(JOIN_TIMEOUT_MS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Warten auf " + thread.getName() + " unterbrochen", e);
    }
    if (thread.isAlive()) {
      thread.interrupt();
    }
  }

  // ---- Sync-Punkte
  // -------------------------------------------------------------------------------

  /** Sync-Punkte eines Laufs; Ablaeufe werden vermerkt und machen den Test rot. */
  static final class Gates {
    private final CyclicBarrier beforeMutation = new CyclicBarrier(2);
    private final CyclicBarrier afterMembershipWrites = new CyclicBarrier(2);
    private final CountDownLatch winnerDone = new CountDownLatch(1);
    private final AtomicBoolean gateTimedOut = new AtomicBoolean(false);
    private final AtomicBoolean loserWaitTimedOut = new AtomicBoolean(false);
    private final AtomicInteger membershipWritesReached = new AtomicInteger();

    void awaitBeforeMutation() {
      await(beforeMutation);
    }

    void awaitAfterMembershipWrites() {
      membershipWritesReached.incrementAndGet();
      await(afterMembershipWrites);
      if (LOSER.equals(ROLE_PER_THREAD.get())) {
        try {
          if (!winnerDone.await(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            loserWaitTimedOut.set(true);
          }
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          loserWaitTimedOut.set(true);
        }
      }
    }

    private void await(final CyclicBarrier barrier) {
      try {
        barrier.await(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (final TimeoutException | BrokenBarrierException e) {
        gateTimedOut.set(true);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        gateTimedOut.set(true);
      }
    }
  }

  // ---- Sonden
  // ------------------------------------------------------------------------------------

  private Integer count(final String sql, final Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private int taskVersion() {
    return count("SELECT VERSION FROM [dbo].[TASK] WHERE ID=?", TASK_ID);
  }

  private int participantVersion(final UUID ref) {
    return count(
        "SELECT VERSION FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?",
        TASK_ID,
        ref);
  }

  private int userStateVersion(final UUID ref) {
    return count(
        "SELECT VERSION FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID=? AND USER_REF=?", TASK_ID, ref);
  }

  private int userStateUnsubscribed(final UUID ref) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID=? AND USER_REF=?"
            + " AND UNSUBSCRIBED_AT IS NOT NULL",
        TASK_ID,
        ref);
  }

  private int historyCount(final UUID actorRef) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] WHERE TASK_ID=? AND TENANT_ID=?"
            + " AND ACTOR_REF=?",
        TASK_ID,
        TENANT_ID,
        actorRef);
  }

  private int notificationCount(final UUID recipientRef) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] WHERE TASK_ID=? AND TENANT_ID=?"
            + " AND RECIPIENT_REF=?",
        TASK_ID,
        TENANT_ID,
        recipientRef);
  }

  private long inboxGeneration(final UUID ref) {
    final Long g =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(GENERATION), 0) FROM [dbo].[TASK_INBOX_GENERATION]"
                + " WHERE TENANT_ID=? AND USER_REF=?",
            Long.class,
            TENANT_ID,
            ref);
    return g == null ? 0L : g;
  }

  // ---- Seed-Helfer
  // -------------------------------------------------------------------------------

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

  private static void insertParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID ref,
      final boolean creator,
      final boolean assignee) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, ?, ?, 1, 'it-seed',"
            + " GETDATE())",
        id,
        id,
        TENANT_ID,
        taskId,
        ref,
        TENANT_ID,
        creator ? 1 : 0,
        assignee ? 1 : 0);
  }

  private static void insertActiveUserState(
      final JdbcTemplate jt, final long id, final long taskId, final UUID ref) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED,"
            + " ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, 0, 0, 0, 'it-seed', GETDATE())",
        id,
        id,
        TENANT_ID,
        taskId,
        ref,
        TENANT_ID);
  }

  // ---- Aufzeichnende Ports
  // -----------------------------------------------------------------------

  /** Zeichnet die After-Commit-Signal-Sends auf. */
  static final class RecordingInboxSignalPort implements InboxSignalPort {
    private final List<Set<UUID>> sends = new CopyOnWriteArrayList<>();

    @Override
    public void send(final long tenantId, final Collection<UUID> userRefs) {
      sends.add(new HashSet<>(userRefs));
    }

    List<Set<UUID>> sends() {
      return sends;
    }

    void clear() {
      sends.clear();
    }
  }

  /** Zeichnet die After-Commit-Pushes je Empfaenger auf. */
  static final class RecordingNotificationPushPort implements NotificationPushPort {
    private final List<UUID> pushedRecipients = new CopyOnWriteArrayList<>();

    @Override
    public void push(
        final long tenantId, final UUID recipientRef, final NotificationPushEnvelope envelope) {
      pushedRecipients.add(recipientRef);
    }

    List<UUID> pushedRecipients() {
      return pushedRecipients;
    }

    void clear() {
      pushedRecipients.clear();
    }
  }

  // ---- Test-Services: Sync-Punkte
  // ----------------------------------------------------------------

  /** Sync 1 am Produktiv-Seam: nach allen Reads, vor der ersten Schreibung. */
  static class TestAssigneeService extends AssigneeService {

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
        final PermissionEvaluator permissionEvaluator) {
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

    @Override
    protected void onBeforeRemoveMutationForTest(final Long taskId, final UUID participantRef) {
      final Gates gates = GATES.get();
      if (gates != null) {
        gates.awaitBeforeMutation();
      }
    }
  }

  /**
   * Sync 2 am Historieneintrag des Entfernens: die erste Schreibung nach Teilnehmer- und
   * State-Zeile, also der Punkt, an dem beide Transaktionen ihre Mitgliedschafts-Sperren halten.
   */
  static class TestTaskHistoryService extends TaskHistoryService {

    TestTaskHistoryService(final TaskHistoryEntryQueryRepository repository) {
      super(repository);
    }

    @Override
    public void record(
        final Long taskId,
        final Long tenantId,
        final UUID actorRef,
        final String text,
        final String changeDetail) {
      final Gates gates = GATES.get();
      if (gates != null && REMOVE_HISTORY_TEXT.equals(text)) {
        gates.awaitAfterMembershipWrites();
      }
      super.record(taskId, tenantId, actorRef, text, changeDetail);
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
      return () -> "participant-remove-concurrency-it";
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
    TestTaskHistoryService taskHistoryService(final TaskHistoryEntryQueryRepository repo) {
      return new TestTaskHistoryService(repo);
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

    // Echte Services: der Rollback persistierter Benachrichtigungen und der After-Commit-Signale
    // der Verliererin ist so real belegbar.
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

    /** Stub: gewaehrt nur TASK_WRITE (das Entfernen prueft es am Controller, nicht im Service). */
    @Bean
    PermissionEvaluator permissionEvaluator() {
      return new PermissionEvaluator() {
        @Override
        public boolean hasAnyRole(final String... expectedRoles) {
          return false;
        }

        @Override
        public boolean hasRole(final String expectedRole) {
          return false;
        }

        @Override
        public List<String> getUserRoles() {
          return List.of();
        }

        @Override
        public boolean isOwner(final NovaEntity<?> entity) {
          return false;
        }

        @Override
        public boolean hasExactAuthority(final String authority) {
          return TaskManagementPermissions.TASK_WRITE.equals(authority);
        }
      };
    }

    @Bean
    TestAssigneeService testAssigneeService(
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
        final PermissionEvaluator permissionEvaluator) {
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
  }
}
