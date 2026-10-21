package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.participant.model.ExitInput;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import io.keel.framework.api.exception.BusinessRuleException;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Real-MSSQL-Nebenlaeufigkeits-IT fuer {@code AssigneeService.exit()} Handover (BE-7, PA4/PA4+).
 *
 * <ul>
 *   <li><b>IT6 Ziel-Race ({@link #it6_ziel_race_pures_teilnehmer_add_rollback}):</b> Zwischen
 *       Vorab-Read und save des Handover-Ziels wird ueber eine UNABHAENGIGE Verbindung eine bereits
 *       committete REINE-Teilnehmer-Zeile (ASSIGNEE_FLAG=0) desselben Ziels eingefuegt. Der
 *       {@code ensureAssignee}-save trifft die TASK_PARTICIPANT-UNIQUE -> Re-Read findet den
 *       Gewinner mit ASSIGNEE_FLAG=0 -> das Assignee-Gewinnerpraedikat ist NICHT erfuellt -> Konflikt
 *       wird weitergeworfen -> die ganze Handover-Tx rollt zurueck. Beweis: NIE 204 mit
 *       ASSIGNEE_FLAG=0; KEIN TASK_HANDOVER-Ereignis/-Target, KEINE Notification, KEIN Austritt des
 *       Akteurs, TASK-VERSION unveraendert (voller Rollback).
 *   <li><b>IT7 Versions-Verlierer ({@link #it7_version_verlierer_rollback}):</b> Zwei
 *       latch-synchronisierte Handover-Threads auf dieselbe Aufgabe. Der TASK-VERSION-Guard laesst
 *       genau einen gewinnen; der Verlierer bekommt {@link BusinessRuleException} (409) und committet
 *       NICHTS. Beweis: genau 1 Ereignis, genau 1 Target je Gewinner, Gewinner-Ziel Assignee,
 *       Verlierer-Ziel NICHT Assignee.
 * </ul>
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = HandoverConcurrencyIT.ItConfig.class,
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
class HandoverConcurrencyIT {

  private static final long TENANT_ID = 594L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000594");
  private static final UUID ACTOR_REF = UUID.fromString("00000059-00a0-0059-0000-000000000594");
  private static final UUID ACTOR2_REF = UUID.fromString("00000059-00a2-0059-0000-000000000594");

  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_NORMAL = 202L;
  private static final long DIRECTION = 301L;

  // IT6 Ziel-Race.
  private static final long TASK_RACE_ID = 594101L;
  private static final UUID TASK_RACE_UUID = UUID.fromString("00000059-0000-0059-aaaa-000000594101");
  private static final UUID TARGET_RACE = UUID.fromString("00000059-00b0-0059-0000-000000000594");
  private static final long TARGET_RACE_DB_ID = 594201L;
  private static final long ACTOR_PART_ID = 594301L;
  private static final long INJECTED_TARGET_PART_ID = 594401L;

  // IT7 Versions-Verlierer.
  private static final long TASK_VER_ID = 594102L;
  private static final UUID TASK_VER_UUID = UUID.fromString("00000059-0000-0059-aaaa-000000594102");
  private static final UUID TARGET_WIN = UUID.fromString("00000059-00c1-0059-0000-000000000594");
  private static final UUID TARGET_LOSE = UUID.fromString("00000059-00c2-0059-0000-000000000594");
  private static final long TARGET_WIN_DB_ID = 594211L;
  private static final long TARGET_LOSE_DB_ID = 594212L;
  private static final long ACTOR_VER_PART_ID = 594311L;
  private static final long ACTOR2_VER_PART_ID = 594312L;

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
    seedBenutzer(jt, 594501L, ACTOR_REF, "actor@brasi.de");
    seedBenutzer(jt, 594502L, ACTOR2_REF, "actor2@brasi.de");
    seedBenutzer(jt, TARGET_RACE_DB_ID, TARGET_RACE, "target-race@brasi.de");
    seedBenutzer(jt, TARGET_WIN_DB_ID, TARGET_WIN, "target-win@brasi.de");
    seedBenutzer(jt, TARGET_LOSE_DB_ID, TARGET_LOSE, "target-lose@brasi.de");

    // IT6: Aufgabe + aktiver Nicht-Creator-Assignee ACTOR (darf uebergeben).
    insertTask(jt, TASK_RACE_ID, TASK_RACE_UUID, "IT6-Ziel-Race");
    insertActiveAssignee(jt, ACTOR_PART_ID, TASK_RACE_ID, ACTOR_REF, false);

    // IT7: Aufgabe + zwei aktive Nicht-Creator-Assignees (ACTOR, ACTOR2) fuer den Race.
    insertTask(jt, TASK_VER_ID, TASK_VER_UUID, "IT7-Version");
    insertActiveAssignee(jt, ACTOR_VER_PART_ID, TASK_VER_ID, ACTOR_REF, false);
    insertActiveAssignee(jt, ACTOR2_VER_PART_ID, TASK_VER_ID, ACTOR2_REF, false);
  }

  // ---- IT6: Ziel-Race -> voller Rollback, nie 204 mit ASSIGNEE_FLAG=0 -------------------------

  @Test
  @DisplayName(
      "it6_ziel_race_pures_teilnehmer_add_rollback: committeter reiner-Teilnehmer-Insert des Ziels"
          + " zwischen Read und save -> UNIQUE -> Gewinnerpraedikat verfehlt -> voller Rollback")
  void it6_ziel_race_pures_teilnehmer_add_rollback() {
    service.armTargetInjector(
        () ->
            injectCommittedPureParticipant(
                dataSource, INJECTED_TARGET_PART_ID, TASK_RACE_ID, TARGET_RACE),
        TARGET_RACE);

    final int versionBefore = taskVersion(TASK_RACE_UUID);

    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    try {
      // Der Handover muss scheitern (voller Rollback) - kein 204, weil das Ziel kein Assignee werden
      // kann (Gewinner-Zeile hat ASSIGNEE_FLAG=0).
      assertThatThrownBy(
              () ->
                  service.exit(
                      TASK_RACE_UUID,
                      new ExitInput(ExitKind.HANDOVER, List.of(TARGET_RACE), false)))
          .isInstanceOf(RuntimeException.class);
    } finally {
      TenantContextHolder.clear();
    }

    // Voller Rollback: kein Ereignis, kein Target, keine Notification, Akteur NICHT ausgetreten,
    // injizierte Zeile bleibt ASSIGNEE_FLAG=0, TASK-VERSION unveraendert.
    assertThat(handoverEventCount(TASK_RACE_ID)).as("kein TASK_HANDOVER-Ereignis").isZero();
    assertThat(handoverTargetCount(TASK_RACE_ID)).as("kein TASK_HANDOVER_TARGET").isZero();
    assertThat(notificationCount(TASK_RACE_ID)).as("keine Notification").isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?"
                    + " AND EXIT_KIND IS NOT NULL",
                TASK_RACE_ID, ACTOR_REF))
        .as("Akteur NICHT ausgetreten (voller Rollback)").isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?"
                    + " AND ASSIGNEE_FLAG=1",
                TASK_RACE_ID, TARGET_RACE))
        .as("Ziel wurde NICHT Assignee (nie 204 mit ASSIGNEE_FLAG=1 ueber den Gewinner)").isZero();
    assertThat(taskVersion(TASK_RACE_UUID)).as("TASK-VERSION unveraendert").isEqualTo(versionBefore);
  }

  // ---- IT7: Versions-Verlierer -> 409, committet nichts (deterministische Seam-Injektion) -----

  /**
   * Deterministischer Versions-Verlierer-Beweis (PA4+): zwischen allen Handover-/Austritts-Mutationen
   * und dem versionierten {@code taskRepository.save} wird ueber eine UNABHAENGIGE, bereits committete
   * Verbindung die TASK-VERSION fremd-gebumpt (simuliert einen nebenlaeufigen Gewinner PUT/exit). Der
   * Service-save trifft dann die stale VERSION -> Optimistic-Lock -> {@link BusinessRuleException}
   * (409); die GANZE Handover-Tx rollt zurueck (kein Ereignis/Target/Assignee-Wechsel/Austritt). Die
   * Beweisart ist bewusst einthreadig (kein SYS_SEQUENCE-Contention-Confound, vgl.
   * {@code ParticipantAddConcurrencyIT}-Doku).
   */
  @Test
  @DisplayName(
      "it7_version_verlierer_rollback: fremder TASK-VERSION-Bump vor dem Service-save -> 409 +"
          + " voller Rollback (kein Ereignis/Target/Assignee/Austritt committet)")
  void it7_version_verlierer_rollback() {
    service.armTaskVersionInjector(() -> bumpTaskVersion(dataSource, TASK_VER_UUID));

    final int versionBefore = taskVersion(TASK_VER_UUID);

    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ACTOR_REF);
    try {
      assertThatThrownBy(
              () ->
                  service.exit(
                      TASK_VER_UUID, new ExitInput(ExitKind.HANDOVER, List.of(TARGET_WIN), false)))
          .isInstanceOf(BusinessRuleException.class);
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
    }

    // Voller Rollback der Handover-Tx: nur der Fremd-Bump (+1) blieb; keine Handover-Artefakte.
    assertThat(taskVersion(TASK_VER_UUID))
        .as("nur der committete Fremd-Bump wirkt (+1), die verlierende Tx committet nichts")
        .isEqualTo(versionBefore + 1);
    assertThat(handoverEventCount(TASK_VER_ID)).as("kein TASK_HANDOVER-Ereignis").isZero();
    assertThat(handoverTargetCount(TASK_VER_ID)).as("kein TASK_HANDOVER_TARGET").isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?"
                    + " AND ASSIGNEE_FLAG=1",
                TASK_VER_ID, TARGET_WIN))
        .as("Ziel wurde NICHT Assignee (Rollback)").isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?"
                    + " AND EXIT_KIND IS NOT NULL",
                TASK_VER_ID, ACTOR_REF))
        .as("Akteur NICHT ausgetreten (Rollback)").isZero();
  }

  /** Bumpt die TASK-VERSION ueber eine UNABHAENGIGE, committete Verbindung (simuliert Fremd-Gewinner). */
  private static void bumpTaskVersion(final DataSource ds, final UUID taskUuid) {
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(true);
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE [dbo].[TASK] SET VERSION = VERSION + 1 WHERE EXTERNALID = ?")) {
        ps.setString(1, taskUuid.toString());
        ps.executeUpdate();
      }
    } catch (final java.sql.SQLException e) {
      throw new IllegalStateException("TASK-VERSION-Fremd-Bump fehlgeschlagen", e);
    }
  }

  // ---- Helfer ---------------------------------------------------------------------------------

  /** Thread-lokaler Akteur (kein geteiltes Mock-Re-Stubbing). */
  private static final ThreadLocal<UUID> ACTOR_PER_THREAD = ThreadLocal.withInitial(() -> ACTOR_REF);

  private static void injectCommittedPureParticipant(
      final DataSource ds, final long id, final long taskId, final UUID ref) {
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
        ps.setLong(2, TENANT_ID);
        ps.setLong(3, taskId);
        ps.setString(4, ref.toString());
        ps.setLong(5, TENANT_ID);
        ps.executeUpdate();
      }
    } catch (final java.sql.SQLException e) {
      throw new IllegalStateException("Ziel-Konflikt-Injektion fehlgeschlagen", e);
    }
  }

  private Integer count(final String sql, final Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private int handoverEventCount(final long taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER] WHERE TASK_ID=? AND TENANT_ID=?",
        Integer.class, taskId, TENANT_ID);
  }

  private int handoverTargetCount(final long taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER_TARGET] tgt"
            + " JOIN [dbo].[TASK_HANDOVER] h ON tgt.HANDOVER_ID=h.ID"
            + " WHERE h.TASK_ID=? AND h.TENANT_ID=?",
        Integer.class, taskId, TENANT_ID);
  }

  private int notificationCount(final long taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] WHERE TASK_ID=? AND TENANT_ID=?",
        Integer.class, taskId, TENANT_ID);
  }

  private int taskVersion(final UUID taskUuid) {
    return jdbcTemplate.queryForObject(
        "SELECT VERSION FROM [dbo].[TASK] WHERE EXTERNALID=?", Integer.class, taskUuid);
  }

  // ---- Seed-Helfer ---

  private static void seedFirma(final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " GUELTIG_VON) VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static void seedBenutzer(
      final JdbcTemplate jt, final long id, final UUID externalId, final String loginName) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " LOGIN_NAME, VOLLER_NAME) VALUES (?, 'ACTIVE', ?, 1, ?, ?, 'IT Test User')",
        id, id, externalId, TENANT_ID, loginName);
  }

  private static void insertTask(
      final JdbcTemplate jt, final long id, final UUID externalId, final String title) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE,"
            + " TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT,"
            + " COMMENT_COUNT, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?, GETDATE(), 0, 'it-seed', GETDATE())",
        id, id, externalId, TENANT_ID, title, STATUS_OPEN, PRIORITY_NORMAL, DIRECTION, TENANT_ID);
  }

  private static void insertActiveAssignee(
      final JdbcTemplate jt, final long id, final long taskId, final UUID ref, final boolean creator) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, ?, 1, 1, 'it-seed', GETDATE())",
        id, id, TENANT_ID, taskId, ref, TENANT_ID, creator ? 1 : 0);
  }

  // ---- IT-Konfigurations-Context ---

  /**
   * Test-Subklasse: injiziert einmalig eine committete Konflikt-Zeile am Ziel-Save-Seam
   * ({@link #onBeforeHandoverAssigneeSaveForTest}) ODER synchronisiert zwei Handover-Threads VOR dem
   * versionierten TASK-save; zusaetzlich steuerbarer Akteur je Thread.
   */
  static class TestAssigneeService extends AssigneeService {

    private final AtomicReference<Runnable> targetInjector = new AtomicReference<>();
    private volatile UUID targetInjectorRef;
    private final AtomicReference<Runnable> taskVersionInjector = new AtomicReference<>();

    TestAssigneeService(
        final BaseTaskRepository taskRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final UserStateService userStateService,
        final de.brasi.nova.taskmanagement.notification.NotificationService notificationService,
        final de.brasi.nova.taskmanagement.inbox.InboxSignalService inboxSignalService,
        final UserReadRepository userReadRepository,
        final UserAccessor userAccessor,
        final InboxGenerationService inboxGenerationService,
        final TaskHandoverQueryRepository handoverRepository,
        final BaseTaskHandoverTargetRepository handoverTargetRepository,
        final TaskHistoryService taskHistoryService) {
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
          taskHistoryService);
    }

    void armTargetInjector(final Runnable injector, final UUID targetRef) {
      this.targetInjector.set(injector);
      this.targetInjectorRef = targetRef;
      this.taskVersionInjector.set(null);
    }

    void armTaskVersionInjector(final Runnable injector) {
      this.taskVersionInjector.set(injector);
      this.targetInjector.set(null);
    }

    @Override
    protected void onBeforeHandoverAssigneeSaveForTest(final Long taskId, final UUID targetRef) {
      if (targetRef.equals(targetInjectorRef)) {
        final Runnable inj = targetInjector.getAndSet(null);
        if (inj != null) {
          inj.run();
        }
      }
    }

    @Override
    protected void onBeforeTaskVersionSaveForTest(final Long taskId) {
      // Einmalig den committeten Fremd-VERSION-Bump injizieren (deterministischer 409-Beweis).
      final Runnable inj = taskVersionInjector.getAndSet(null);
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
      return () -> "handover-concurrency-it";
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
    de.brasi.nova.taskmanagement.notification.NotificationService notificationService() {
      return mock(de.brasi.nova.taskmanagement.notification.NotificationService.class);
    }

    @Bean
    de.brasi.nova.taskmanagement.inbox.InboxSignalService inboxSignalService() {
      return mock(de.brasi.nova.taskmanagement.inbox.InboxSignalService.class);
    }

    @Bean
    UserAccessor userAccessor() {
      // Thread-lokaler Akteur (kein geteiltes Mock-Re-Stubbing im 2-Thread-Race).
      return () -> {
        final UUID ref = ACTOR_PER_THREAD.get();
        return new AuthUser(ref, ref + "@brasi.de");
      };
    }

    @Bean
    TestAssigneeService testAssigneeService(
        final BaseTaskRepository taskRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final UserStateService userStateService,
        final de.brasi.nova.taskmanagement.notification.NotificationService notificationService,
        final de.brasi.nova.taskmanagement.inbox.InboxSignalService inboxSignalService,
        final UserReadRepository userReadRepository,
        final UserAccessor userAccessor,
        final InboxGenerationService inboxGenerationService,
        final TaskHandoverQueryRepository handoverRepository,
        final BaseTaskHandoverTargetRepository handoverTargetRepository,
        final TaskHistoryService taskHistoryService) {
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
          taskHistoryService);
    }
  }
}
