package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.model.ParticipantAddInput;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Real-MSSQL-Nebenlaeufigkeits-IT fuer {@code AssigneeService.addParticipants} (BE-6, D8). Belegt den
 * in-Tx Re-Read-Translate (Pfad A) auf ZWEI getrennten Wegen gegen echtes MSSQL - die Beweisarten sind
 * BEWUSST getrennt (Codex-Gate-Auflage), weil zwei echt-gleichzeitige Erst-Adds unter der
 * KSF-SequenzGenerator-Contention NICHT beide erfolgreich zurueckkehren (siehe Klassendoku unten):
 *
 * <ol>
 *   <li><b>{@link #reactivate_race_zwei_threads_beide_committen}</b> - ECHTER 2-Thread-Beweis
 *       (VERSION-Pfad): zwei Threads reaktivieren dieselbe exited Zeile, latch-synchronisiert zwischen
 *       Vorab-Read und save. Die Verliererin bekommt den Participant-VERSION-Optimistic-Lock
 *       (OptimisticLockingFailureException) -> Re-Read-Translate -> SKIP. Assert: beide 204 (beide
 *       aeusseren Tx committen, keine UnexpectedRollbackException), genau 1 Catch, {@code
 *       XACT_STATE()=1} (committable), 1 aktive Zeile, 1 UserState, Generation +1, TASK-VERSION
 *       unveraendert.
 *   <li><b>{@link #insert_unique_recovery_via_seam_injection}</b> - EINTHREADIGER Beweis der
 *       UNIQUE-Insert-Recovery (DuplicateKeyException-Pfad): der Test-Seam injiziert zwischen Vorab-Read
 *       und save EINMALIG eine bereits committete Konflikt-Zeile ueber eine WIRKLICH UNABHAENGIGE
 *       Verbindung (autocommit). Der Service-save trifft dann die reale
 *       {@code UQ_TASK_PARTICIPANT_TASK_USER(TASK_ID, PARTICIPANT_TENANT_ID, PARTICIPANT_REF)}-UNIQUE
 *       -> DuplicateKeyException -> Catch -> {@code XACT_STATE()=1} -> Re-Read findet die injizierte
 *       Gewinner-Zeile -> SKIP -> erfolgreicher aeusserer Commit. Assert zusaetzlich: KEINE Effekte der
 *       verlierenden Operation (kein zweiter Participant, kein UserState, kein Generation-Bump).
 * </ol>
 *
 * <p><b>Nicht bewiesen (bewusst, Codex-Gate):</b> dass zwei echt-gleichzeitige Erst-Adds desselben
 * neuen Ref STETS beide 204 liefern. Unter maximaler Simultaneitaet kollidieren zwei KSF-Inserts
 * ZUERST an der Allokation DERSELBEN benannten Sequenz ({@code SequenceGenerator.nextIds} ->
 * SYS_SEQUENCE-Optimistic-Lock), bevor die TASK_PARTICIPANT-UNIQUE greift; die Verliererin bekommt eine
 * transiente {@code BusinessRuleException} OHNE DAO-Ursache. Das ist ein KSF-WEITES Verhalten ALLER
 * Insert-Pfade (nicht BE-6-spezifisch) und wird bewusst NICHT als 204-SKIP geschluckt (enger
 * Ursachen-Guard) -> KSF-Follow-up / dokumentierter Client-Retry-Vertrag. addParticipants bleibt aus
 * Client-Sicht idempotent (Retry findet die Gewinner-Zeile oder allokiert sauber).
 *
 * <p>Service-Ebene (NICHT MockMvc): ein 2-Thread-HTTP-IT waere wegen Security-/Tenant-Kontext je Thread
 * flaky (vgl. {@code TaskOptimisticLockIT}-Javadoc). Uebersprungen ohne MSSQL-Zugang
 * ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = ParticipantAddConcurrencyIT.ItConfig.class,
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
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class ParticipantAddConcurrencyIT {

  private static final Logger log = LoggerFactory.getLogger(ParticipantAddConcurrencyIT.class);

  private static final long TENANT_ID = 593L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000593");
  private static final UUID CREATOR_REF = UUID.fromString("00000059-00c0-0059-0000-000000000593");

  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_NORMAL = 202L;
  private static final long DIRECTION = 301L;

  // Insert-UNIQUE-Recovery (einthreadig, Seam-Injektion).
  private static final long TASK_INSERT_ID = 593101L;
  private static final UUID TASK_INSERT_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000593101");
  private static final UUID INSERT_REF = UUID.fromString("00000059-0001-0059-0000-000000000593");
  private static final long INSERT_REF_DB_ID = 593201L;
  private static final long INJECTED_PARTICIPANT_ID = 593401L;

  // Reaktivierungs-Race (echter 2-Thread-Beweis, VERSION-Pfad).
  private static final long TASK_REACT_ID = 593102L;
  private static final UUID TASK_REACT_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000593102");
  private static final UUID REACT_REF = UUID.fromString("00000059-0002-0059-0000-000000000593");
  private static final long REACT_REF_DB_ID = 593202L;
  private static final long REACT_PARTICIPANT_ID = 593301L;

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TestAssigneeService service;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

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
    seedBenutzer(jt, INSERT_REF_DB_ID, INSERT_REF, "insert-ref@brasi.de", TENANT_ID);
    seedBenutzer(jt, REACT_REF_DB_ID, REACT_REF, "react-ref@brasi.de", TENANT_ID);
    insertTask(jt, TASK_INSERT_ID, TASK_INSERT_UUID, TENANT_ID, "Insert-UNIQUE-Recovery-Task");
    insertTask(jt, TASK_REACT_ID, TASK_REACT_UUID, TENANT_ID, "Reactivate-Race-Task");
    // Reaktivierungs-Race: eine bereits AUSGETRETENE Teilnehmer-Zeile fuer REACT_REF.
    insertExitedParticipant(jt, REACT_PARTICIPANT_ID, TASK_REACT_ID, REACT_REF, TENANT_ID);
  }

  // ---- Beweis 1: Insert-UNIQUE-Recovery (einthreadig, unabhaengige Konflikt-Injektion) ---------

  @Test
  @DisplayName(
      "insert_unique_recovery_via_seam_injection: committeter Fremd-Insert zwischen Vorab-Read und"
          + " save -> UNIQUE -> Catch (XACT_STATE=1) -> Re-Read-SKIP -> Commit; keine Verlierer-Effekte")
  void insert_unique_recovery_via_seam_injection() {
    // Injektion ueber eine WIRKLICH UNABHAENGIGE, bereits committete Verbindung (autocommit) -
    // NICHT ueber die tx-gebundene Verbindung des Service (sonst kein echter UNIQUE-Konflikt).
    service.armInjector(
        () ->
            injectCommittedParticipant(
                dataSource, INJECTED_PARTICIPANT_ID, TASK_INSERT_ID, INSERT_REF, TENANT_ID));

    final int versionBefore = taskVersion(TASK_INSERT_UUID);

    // Einthreadig: kein Sequenz-Contention-Confound; der Konflikt entsteht erst an der UNIQUE.
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    try {
      service.addParticipants(TASK_INSERT_UUID, new ParticipantAddInput(List.of(INSERT_REF)));
    } finally {
      TenantContextHolder.clear();
    }

    // Catch genau einmal + Committability-Sonde auf DERSELBEN Tx-Verbindung.
    assertThat(service.catchCount()).as("genau ein UNIQUE-Konflikt-Catch").isEqualTo(1);
    log.info(
        "D8-Committability-Sonde (UNIQUE-Pfad): XACT_STATE={}, (@@OPTIONS & 16384)={} (XACT_ABORT),"
            + " isolation={}",
        service.lastXactState(),
        service.lastXactAbortBit(),
        service.lastIsolation());
    assertThat(service.lastXactState())
        .as("XACT_STATE()=1 (committable) im UNIQUE-Konfliktzweig -> Pfad A belegt")
        .isEqualTo(1);

    // Genau 1 aktive Zeile = die injizierte Gewinner-Zeile (kein Duplikat durch den Service).
    assertThat(activeParticipantRows(TASK_INSERT_ID, INSERT_REF))
        .as("genau 1 aktive Participant-Zeile (injizierter Gewinner, kein Duplikat)")
        .isEqualTo(1);
    // Ausbleibende Effekte der verlierenden (Service-)Operation: kein UserState, kein Generation-Bump.
    assertThat(userStateRows(TASK_INSERT_ID, INSERT_REF))
        .as("Verliererin legt KEINEN UserState an (reiner SKIP)")
        .isZero();
    assertThat(generationRowCount(INSERT_REF))
        .as("Verliererin bumpt KEINE Generation (reiner SKIP)")
        .isZero();
    assertThat(taskVersion(TASK_INSERT_UUID))
        .as("TASK-VERSION unveraendert")
        .isEqualTo(versionBefore);
  }

  // ---- Beweis 2: Reaktivierungs-Race (echter 2-Thread-Beweis, VERSION-Pfad) ---------------------

  @Test
  @DisplayName(
      "reactivate_race_zwei_threads_beide_committen: zwei Threads reaktivieren dieselbe exited Zeile"
          + " -> VERSION-Konflikt -> Re-Read-SKIP; beide 204, 1 Catch, XACT_STATE=1, 1 Zeile, gen +1")
  void reactivate_race_zwei_threads_beide_committen() throws Exception {
    final CyclicBarrier barrier = new CyclicBarrier(2);
    service.armBarrier(barrier);

    final int versionBefore = taskVersion(TASK_REACT_UUID);

    final AtomicReference<Throwable> err1 = new AtomicReference<>();
    final AtomicReference<Throwable> err2 = new AtomicReference<>();
    final Thread t1 = raceThread(TASK_REACT_UUID, REACT_REF, err1);
    final Thread t2 = raceThread(TASK_REACT_UUID, REACT_REF, err2);
    t1.start();
    t2.start();
    t1.join(TimeUnit.SECONDS.toMillis(30));
    t2.join(TimeUnit.SECONDS.toMillis(30));

    // Beide Threads ohne Exception -> beide aeusseren Tx committen (keine UnexpectedRollbackException).
    assertThat(err1.get()).as("Thread 1 ohne Exception (Commit)").isNull();
    assertThat(err2.get()).as("Thread 2 ohne Exception (Commit)").isNull();
    assertThat(service.catchCount()).as("genau eine Verliererin im Konfliktzweig").isEqualTo(1);

    log.info(
        "D8-Committability-Sonde (VERSION-Pfad): XACT_STATE={}, (@@OPTIONS & 16384)={} (XACT_ABORT),"
            + " isolation={}",
        service.lastXactState(),
        service.lastXactAbortBit(),
        service.lastIsolation());
    assertThat(service.lastXactState())
        .as("XACT_STATE()=1 (committable) im VERSION-Konfliktzweig -> Pfad A belegt")
        .isEqualTo(1);

    assertThat(activeParticipantRows(TASK_REACT_ID, REACT_REF))
        .as("genau 1 aktive Participant-Zeile")
        .isEqualTo(1);
    assertThat(userStateRows(TASK_REACT_ID, REACT_REF)).as("genau 1 UserState").isEqualTo(1);
    assertThat(generation(REACT_REF)).as("Generation exakt +1 (kein Doppel-Bump)").isEqualTo(1L);
    assertThat(taskVersion(TASK_REACT_UUID))
        .as("TASK-VERSION unveraendert (additive Union)")
        .isEqualTo(versionBefore);
  }

  // ---- Helfer ----------------------------------------------------------------------------------

  private Thread raceThread(
      final UUID taskUuid, final UUID ref, final AtomicReference<Throwable> err) {
    return new Thread(
        () -> {
          try {
            TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
            service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));
          } catch (final Throwable t) {
            err.set(t);
          } finally {
            TenantContextHolder.clear();
          }
        });
  }

  /** Legt eine bereits committete Konflikt-Zeile ueber eine UNABHAENGIGE Verbindung (autocommit) an. */
  private static void injectCommittedParticipant(
      final DataSource ds, final long id, final long taskId, final UUID ref, final long tenantId) {
    final String sql =
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 0, 1, 'it-inject', GETDATE())";
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(true);
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setLong(1, id);
        ps.setLong(2, tenantId);
        ps.setLong(3, taskId);
        ps.setString(4, ref.toString());
        ps.setLong(5, tenantId);
        ps.executeUpdate();
      }
    } catch (final java.sql.SQLException e) {
      throw new IllegalStateException("Konflikt-Injektion fehlgeschlagen", e);
    }
  }

  private int activeParticipantRows(final long taskId, final UUID ref) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT]"
            + " WHERE TASK_ID = ? AND PARTICIPANT_REF = ? AND TENANT_ID = ?"
            + " AND ENTITY_STATUS = 'ACTIVE' AND EXIT_KIND IS NULL",
        Integer.class, taskId, ref, TENANT_ID);
  }

  private int userStateRows(final long taskId, final UUID ref) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE]"
            + " WHERE TASK_ID = ? AND USER_REF = ? AND TENANT_ID = ?",
        Integer.class, taskId, ref, TENANT_ID);
  }

  private int generationRowCount(final UUID ref) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_INBOX_GENERATION] WHERE USER_REF = ? AND TENANT_ID = ?",
        Integer.class, ref, TENANT_ID);
  }

  private long generation(final UUID ref) {
    final Long gen =
        jdbcTemplate.queryForObject(
            "SELECT GENERATION FROM [dbo].[TASK_INBOX_GENERATION]"
                + " WHERE USER_REF = ? AND TENANT_ID = ?",
            Long.class, ref, TENANT_ID);
    return gen == null ? 0L : gen;
  }

  private int taskVersion(final UUID taskUuid) {
    return jdbcTemplate.queryForObject(
        "SELECT VERSION FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskUuid);
  }

  // ---- Seed-Hilfsmethoden ----------------------------------------------------------------------

  private static void seedFirma(final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static void seedBenutzer(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final String loginName,
      final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, LOGIN_NAME, VOLLER_NAME) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, 'IT Test User')",
        id, id, externalId, tenantId, loginName);
  }

  private static void insertTask(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final long tenantId,
      final String title) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE, TASK_STATUS_ID, "
            + " PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT, COMMENT_COUNT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?, GETDATE(), 0, 'it-seed', GETDATE())",
        id, id, externalId, tenantId, title, STATUS_OPEN, PRIORITY_NORMAL, DIRECTION, tenantId);
  }

  private static void insertExitedParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID ref,
      final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 0, 1, 'UNSUBSCRIBED', "
            + " GETDATE(), 'it-seed', GETDATE())",
        id, id, tenantId, taskId, ref, tenantId);
  }

  // ---- IT-Konfigurations-Context ---------------------------------------------------------------

  /**
   * Test-Subklasse von {@link AssigneeService}: synchronisiert zwei Threads deterministisch
   * (Barrier-Modus, Reaktivierungs-Race) ODER injiziert einmalig eine Konflikt-Zeile (Injector-Modus,
   * Insert-UNIQUE) am Seam zwischen Vorab-Read und save, und erfasst Catch-Zaehler +
   * Committability-Sonde im Catch-Seam.
   */
  static class TestAssigneeService extends AssigneeService {

    private final JdbcTemplate probeJdbc;
    private volatile CyclicBarrier barrier;
    private final AtomicReference<Runnable> injectOnce = new AtomicReference<>();
    private final AtomicInteger catchCount = new AtomicInteger();
    private final AtomicInteger lastXactState = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger lastXactAbortBit = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger lastIsolation = new AtomicInteger(Integer.MIN_VALUE);

    TestAssigneeService(
        final BaseTaskRepository taskRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final UserStateService userStateService,
        final NotificationService notificationService,
        final de.brasi.nova.taskmanagement.inbox.InboxSignalService inboxSignalService,
        final UserReadRepository userReadRepository,
        final UserAccessor userAccessor,
        final InboxGenerationService inboxGenerationService,
        final de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository handoverRepository,
        final de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository
                handoverTargetRepository,
        final de.brasi.nova.taskmanagement.task.TaskHistoryService taskHistoryService,
        final de.brasi.nova.taskmanagement.security.TaskMembershipGuard membershipGuard,
        final io.keel.framework.api.security.PermissionEvaluator permissionEvaluator,
        final JdbcTemplate probeJdbc) {
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
      this.probeJdbc = probeJdbc;
    }

    void armBarrier(final CyclicBarrier b) {
      this.barrier = b;
      this.injectOnce.set(null);
      resetProbes();
    }

    void armInjector(final Runnable injector) {
      this.barrier = null;
      this.injectOnce.set(injector);
      resetProbes();
    }

    private void resetProbes() {
      catchCount.set(0);
      lastXactState.set(Integer.MIN_VALUE);
      lastXactAbortBit.set(Integer.MIN_VALUE);
      lastIsolation.set(Integer.MIN_VALUE);
    }

    int catchCount() {
      return catchCount.get();
    }

    int lastXactState() {
      return lastXactState.get();
    }

    int lastXactAbortBit() {
      return lastXactAbortBit.get();
    }

    int lastIsolation() {
      return lastIsolation.get();
    }

    @Override
    protected void onBeforeParticipantSaveForTest(final Long taskId, final UUID ref) {
      // Injector-Modus: einmalig eine committete Konflikt-Zeile anlegen (unabhaengige Verbindung).
      final Runnable inj = injectOnce.getAndSet(null);
      if (inj != null) {
        inj.run();
        return;
      }
      // Barrier-Modus: beide Threads VOR dem save synchronisieren.
      final CyclicBarrier b = this.barrier;
      if (b != null) {
        try {
          b.await(20, TimeUnit.SECONDS);
        } catch (final Exception e) {
          throw new IllegalStateException("Barrier-Synchronisation fehlgeschlagen", e);
        }
      }
    }

    @Override
    protected void onParticipantConflictCaughtForTest(final Long taskId, final UUID ref) {
      catchCount.incrementAndGet();
      // Committability-Sonde auf DERSELBEN transaktionsgebundenen Verbindung (D8-Auflage 2).
      final Integer xactState =
          probeJdbc.queryForObject("SELECT CAST(XACT_STATE() AS INT)", Integer.class);
      final Integer xactAbort = probeJdbc.queryForObject("SELECT @@OPTIONS & 16384", Integer.class);
      if (xactState != null) {
        lastXactState.set(xactState);
      }
      if (xactAbort != null) {
        lastXactAbortBit.set(xactAbort);
      }
      // Isolation nur best-effort protokollieren (sys.dm_exec_sessions braucht VIEW SERVER STATE;
      // kein Teil des Committability-Gates).
      try {
        final Integer isolation =
            probeJdbc.queryForObject(
                "SELECT transaction_isolation_level FROM sys.dm_exec_sessions"
                    + " WHERE session_id = @@SPID",
                Integer.class);
        if (isolation != null) {
          lastIsolation.set(isolation);
        }
      } catch (final RuntimeException ignored) {
        // Isolation-Erfassung optional.
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
      return () -> "participant-concurrency-it";
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
    NotificationService notificationService() {
      // addParticipants emittiert KEINE Notification (D5) -> Mock genuegt.
      return mock(NotificationService.class);
    }

    @Bean
    de.brasi.nova.taskmanagement.inbox.InboxSignalService inboxSignalService() {
      // Push wird in diesem IT nicht geprueft -> Mock (vermeidet InboxSignalPort-Wiring).
      return mock(de.brasi.nova.taskmanagement.inbox.InboxSignalService.class);
    }

    @Bean
    UserAccessor userAccessor() {
      final UserAccessor accessor = mock(UserAccessor.class);
      when(accessor.requireCurrentUser())
          .thenReturn(new AuthUser(CREATOR_REF, "creator@brasi.de"));
      return accessor;
    }

    @Bean
    de.brasi.nova.taskmanagement.task.TaskHistoryService taskHistoryService(
        final de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository repo) {
      return new de.brasi.nova.taskmanagement.task.TaskHistoryService(repo);
    }

    @Bean
    de.brasi.nova.taskmanagement.security.TaskMembershipGuard membershipGuard(
        final TaskParticipantQueryRepository participantQueryRepository) {
      return new de.brasi.nova.taskmanagement.security.TaskMembershipGuard(
          participantQueryRepository);
    }

    /**
     * Stub-{@link io.keel.framework.api.security.PermissionEvaluator} (Slice 4): dieser IT prueft
     * {@code addParticipants}-Nebenlaeufigkeit, nicht die Handover-Autz-Grenze; der Evaluator wird
     * nie konsultiert. Er erfuellt nur den Konstruktor-Vertrag (fail-closed Defaults).
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
          return false;
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
        final de.brasi.nova.taskmanagement.inbox.InboxSignalService inboxSignalService,
        final UserReadRepository userReadRepository,
        final UserAccessor userAccessor,
        final InboxGenerationService inboxGenerationService,
        final de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository handoverRepository,
        final de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository
                handoverTargetRepository,
        final de.brasi.nova.taskmanagement.task.TaskHistoryService taskHistoryService,
        final de.brasi.nova.taskmanagement.security.TaskMembershipGuard membershipGuard,
        final io.keel.framework.api.security.PermissionEvaluator permissionEvaluator,
        final JdbcTemplate jdbcTemplate) {
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
          permissionEvaluator,
          jdbcTemplate);
    }
  }
}
