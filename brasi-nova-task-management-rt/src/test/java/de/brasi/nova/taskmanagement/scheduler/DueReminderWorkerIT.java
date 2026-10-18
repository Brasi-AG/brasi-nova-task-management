package de.brasi.nova.taskmanagement.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.push.InboxSignalPort;
import de.brasi.nova.taskmanagement.push.NoOpNotificationPushConfig;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import io.keel.framework.security.KsfSecurityAutoConfiguration;
import io.keel.framework.security.web.KsfWebSecurityAutoConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Integration-Test fuer Due-Reminder-Emitter/Worker gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist (arch-v2 §5, plan-v3 Task 16):
 *
 * <ul>
 *   <li>{@code worker_cycle_emittiert_due_fuer_faellige_aufgabe}: runAsSystem + {@code @GlobalQuery}
 *       -Kandidatensuche + runWithTenant + Assignee-Aufloesung + emitOnce -> Receipt + DUE-Notification.
 *   <li>{@code zwei_threads_latch_genau_ein_receipt_und_due}: zwei parallele emitOnce fuer dieselbe
 *       (Mandant,Aufgabe,Empfaenger,Tag) -> genau 1 Receipt + 1 DUE-Notification (UNIQUE-Idempotenz).
 *   <li>{@code due_invalidiert_empfaenger_snapshot}: nach Commit wird {@code InboxSignalPort.send}
 *       fuer den Empfaenger aufgerufen (Snapshot-Invalidierung nach Commit).
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = DueReminderWorkerIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
      "ksf.web-security.enabled=true",
      "ksf.web-security.filter-chain.enabled=true",
      "ksf.web-security.principal.profile-claim.enabled=true",
      "ksf.web-security.tenant.context-binding=FILTER",
      "ksf.web-security.tenant.binding.source=JWT",
      "ksf.web-security.tenant.binding.required=false",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.security.jwt.enabled=true",
      "ksf.module-registration.enabled=false",
      "task.scheduler.worker-enabled=true",
      "task.scheduler.enabled=true",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class DueReminderWorkerIT {

  /** Mandant A (Klon-Scope 500-599). */
  private static final long TENANT_A_ID = 560L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final TenantInfo TENANT_A = new TenantInfo(TENANT_A_UUID, TENANT_A_ID);

  private static final UUID USER_CYCLE_REF = UUID.randomUUID();
  private static final String USER_CYCLE_LOGIN = "user-cycle-due@brasi.de";
  private static final long USER_CYCLE_DB_ID = 571L;

  private static final UUID USER_RACE_REF = UUID.randomUUID();
  private static final String USER_RACE_LOGIN = "user-race-due@brasi.de";
  private static final long USER_RACE_DB_ID = 572L;

  private static final UUID USER_INV_REF = UUID.randomUUID();
  private static final String USER_INV_LOGIN = "user-inv-due@brasi.de";
  private static final long USER_INV_DB_ID = 573L;

  /** Deterministischer ID-Block fuer Tasks/Participants/UserStates. */
  private static final long ID_BASE = 5710L;

  private static final String TEST_KID = "due-worker-it-key";

  private static final KeyPair RSA_KEY_PAIR;

  static {
    try {
      final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      RSA_KEY_PAIR = gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static HikariDataSource master;
  private static String dbName;
  private static Path pemTempFile;

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

    final String pemContent = toPem(RSA_KEY_PAIR.getPublic().getEncoded());
    try {
      pemTempFile = Files.createTempFile("due-worker-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Konnte temporaere PEM-Datei nicht erstellen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem", () -> "file:" + pemTempFile.toAbsolutePath());
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
      if (pemTempFile != null) {
        try {
          Files.deleteIfExists(pemTempFile);
        } catch (IOException ignored) {
          // Cleanup best-effort
        }
      }
    }
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  /** Aufgezeichnete Invalidierungs-Signale des Recording-{@link InboxSignalPort} (after-commit). */
  private static final List<RecordedSignal> RECORDED = new CopyOnWriteArrayList<>();

  /** Ein aufgezeichnetes {@code snapshot-invalidated}-Signal. */
  record RecordedSignal(long tenantId, List<UUID> userRefs) {}

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DueReminderEmitter emitter;
  @Autowired private DueReminderWorker worker;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedBenutzerIfAbsent(jt, USER_CYCLE_DB_ID, USER_CYCLE_REF, USER_CYCLE_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_RACE_DB_ID, USER_RACE_REF, USER_RACE_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_INV_DB_ID, USER_INV_REF, USER_INV_LOGIN, TENANT_A_ID);
  }

  // ---- IT 1: voller Worker-Zyklus (GlobalQuery + runAsSystem + runWithTenant + emit) ----------

  @Test
  @DisplayName(
      "worker_cycle_emittiert_due_fuer_faellige_aufgabe:"
          + " runDueReminderCycle -> Receipt + DUE-Notification fuer den aktiven Assignee")
  void worker_cycle_emittiert_due_fuer_faellige_aufgabe() {
    final long taskRowId = ID_BASE + 1;
    final UUID taskUuid = UUID.randomUUID();
    seedDueOpenTask(taskRowId, taskUuid, "Cycle-faellig");
    seedAssignee(ID_BASE + 2, taskRowId, USER_CYCLE_REF);
    seedUserState(ID_BASE + 3, taskRowId, USER_CYCLE_REF);

    worker.runDueReminderCycle();

    assertThat(receiptCount(taskRowId, USER_CYCLE_REF, LocalDate.now()))
        .as("genau eine Idempotenz-Quittung")
        .isEqualTo(1);
    assertThat(dueNotificationCount(taskRowId, USER_CYCLE_REF))
        .as("genau eine DUE-Notification fuer den Assignee")
        .isEqualTo(1);
  }

  // ---- IT 2: Zwei-Thread-Rennen auf dieselbe (task,user,Tag)-Kombi -> genau ein Receipt/DUE ----

  @Test
  @DisplayName(
      "zwei_threads_latch_genau_ein_receipt_und_due:"
          + " zwei parallele emitOnce -> genau 1 Receipt + 1 DUE (UNIQUE-Idempotenz)")
  void zwei_threads_latch_genau_ein_receipt_und_due() throws Exception {
    final long taskRowId = ID_BASE + 10;
    final UUID taskUuid = UUID.randomUUID();
    seedDueOpenTask(taskRowId, taskUuid, "Race-faellig");
    seedAssignee(ID_BASE + 11, taskRowId, USER_RACE_REF);
    seedUserState(ID_BASE + 12, taskRowId, USER_RACE_REF);

    final LocalDate today = LocalDate.now();
    final CountDownLatch startGate = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(2);
    final List<String> outcomes = new CopyOnWriteArrayList<>();
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 2; i++) {
        pool.submit(
            () -> {
              try {
                startGate.await();
                TenantContextHolder.runWithTenant(
                    TENANT_A,
                    () -> {
                      emitter.emitOnce(
                          TENANT_A_ID, taskRowId, USER_RACE_REF, today, "Race-faellig");
                      return null;
                    });
                outcomes.add("ok");
              } catch (BusinessRuleException dup) {
                // KSF meldet den UNIQUE-Konflikt der Idempotenz-Quittung als 409.
                outcomes.add("duplicate");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      startGate.countDown();
      assertThat(done.await(15, TimeUnit.SECONDS)).as("beide Threads fertig").isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(outcomes).as("genau ein Erfolg, ein UNIQUE-Konflikt").containsExactlyInAnyOrder(
        "ok", "duplicate");
    assertThat(receiptCount(taskRowId, USER_RACE_REF, today)).isEqualTo(1);
    assertThat(dueNotificationCount(taskRowId, USER_RACE_REF)).isEqualTo(1);
  }

  // ---- IT 3: Snapshot-Invalidierung nach Commit ----------------------------------------------

  @Test
  @DisplayName(
      "due_invalidiert_empfaenger_snapshot:"
          + " nach Commit wird InboxSignalPort.send fuer den Empfaenger aufgerufen")
  void due_invalidiert_empfaenger_snapshot() {
    RECORDED.clear();
    final long taskRowId = ID_BASE + 20;
    final UUID taskUuid = UUID.randomUUID();
    seedDueOpenTask(taskRowId, taskUuid, "Invalidate-faellig");

    TenantContextHolder.runWithTenant(
        TENANT_A,
        () -> {
          emitter.emitOnce(TENANT_A_ID, taskRowId, USER_INV_REF, LocalDate.now(), "Invalidate-faellig");
          return null;
        });

    // Der afterCommit-Hook feuert synchron waehrend des Commits -> das Signal liegt vor.
    assertThat(RECORDED)
        .as("snapshot-invalidated fuer den Empfaenger nach Commit")
        .anyMatch(r -> r.tenantId() == TENANT_A_ID && r.userRefs().contains(USER_INV_REF));
  }

  // ---- DB-Hilfen -----------------------------------------------------------------------------

  private Integer receiptCount(final long taskId, final UUID recipientRef, final LocalDate date) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_REMINDER_RECEIPT] "
            + "WHERE TASK_ID = ? AND RECIPIENT_REF = ? AND OCCURRENCE_DATE = ? AND TENANT_ID = ?",
        Integer.class,
        taskId,
        recipientRef,
        java.sql.Date.valueOf(date),
        TENANT_A_ID);
  }

  private Integer dueNotificationCount(final long taskId, final UUID recipientRef) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n "
            + "JOIN [dbo].[DIC_NOTIFICATION_TYPE] t ON n.TYPE_ID = t.ID "
            + "WHERE n.TASK_ID = ? AND n.RECIPIENT_REF = ? AND t.CODE = 'DUE'",
        Integer.class,
        taskId,
        recipientRef);
  }

  private long statusId(final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[DIC_TASK_STATUS] WHERE CODE = ?", Long.class, code);
  }

  private long priorityId(final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[DIC_TASK_PRIORITY] WHERE CODE = ?", Long.class, code);
  }

  private long directionId(final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[DIC_TASK_DIRECTION] WHERE CODE = ?", Long.class, code);
  }

  private void seedDueOpenTask(final long id, final UUID externalId, final String title) {
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TITLE, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID,"
            + " DUE_DATE, LAST_ACTIVITY_AT, COMMENT_COUNT) "
            + "VALUES (?, 'ACTIVE', 'due-it', getdate(), ?, 1, ?, ?, ?, ?, ?, ?,"
            + " CAST(GETDATE() AS DATE), getdate(), 0)",
        id, id, externalId, TENANT_A_ID, title, statusId("OPEN"), priorityId("NORMAL"),
        directionId("CUSTOMER_INTERNAL"), TENANT_A_ID);
  }

  private void seedAssignee(final long id, final long taskId, final UUID participantRef) {
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND) "
            + "VALUES (?, 'ACTIVE', 'due-it', getdate(), ?, 1, ?, ?, ?, 'PERSON', ?, 1, 1, 1, NULL)",
        id, id, UUID.randomUUID(), TENANT_A_ID, taskId, participantRef, TENANT_A_ID);
  }

  private void seedUserState(final long id, final long taskId, final UUID userRef) {
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT) "
            + "VALUES (?, 'ACTIVE', 'due-it', getdate(), ?, 1, ?, ?, ?, ?, 0, 0, 0, NULL)",
        id, id, UUID.randomUUID(), TENANT_A_ID, taskId, userRef, TENANT_A_ID);
  }

  private static void seedFirmaIfAbsent(
      final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static void seedBenutzerIfAbsent(
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

  // ---- IT-Konfigurations-Context ------------------------------------------------------------

  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @org.springframework.context.annotation.ComponentScan(
      basePackages = {
        "de.brasi.nova.taskmanagement.resolver",
        "de.brasi.nova.taskmanagement.task",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.comment",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.dictionary",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.time",
        "de.brasi.nova.taskmanagement.paging",
      },
      excludeFilters = {
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.ANNOTATION,
            classes = org.springframework.boot.SpringBootConfiguration.class)
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
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    TaskExecutionAutoConfiguration.class,
    ErrorMvcAutoConfiguration.class,
    SecurityAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class,
    KsfSecurityAutoConfiguration.class,
    KsfIntegrationAutoConfiguration.class,
    AopAutoConfiguration.class,
    io.keel.framework.web.autoconfigure.NovaJacksonInfrastructureAutoConfiguration.class,
    io.keel.framework.web.autoconfigure.NovaExceptionHandlingAutoConfiguration.class,
  })
  @Import({
    KsfWebSecurityAutoConfiguration.class,
    SecurityContextUserAccessor.class,
    WhoAmIProbeController.class,
    NoOpNotificationPushConfig.class,
    DueReminderEmitter.class,
    DueReminderWorker.class,
  })
  static class ItConfig {

    /**
     * Recording-{@link InboxSignalPort} ({@code @Primary}): zeichnet die after-commit zugestellten
     * Invalidierungs-Signale auf, statt sie an Sockets zu pushen. Ersetzt die reale WS-Bean (das
     * {@code push}-Paket wird hier nicht gescannt) und vermeidet {@code @MockBean} (das die
     * Bean-Instanziierungsreihenfolge stoert und die KSF-NovaRepositoryRegistry NPE-en laesst).
     */
    @Bean
    @Primary
    InboxSignalPort recordingInboxSignalPort() {
      return (tenantId, userRefs) ->
          RECORDED.add(new RecordedSignal(tenantId, List.copyOf(userRefs)));
    }

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "due-it";
    }
  }
}
