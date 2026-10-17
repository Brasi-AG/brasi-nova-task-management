package de.brasi.nova.taskmanagement.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskStatus;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskStatusRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * Repository-IT: beweist gegen echte MSSQL-Wegwerf-DB (Flyway V1), dass:
 * 1. {@code findByCode} je Dictionary die geseedete ID zurueckliefert (OPEN=101, DONE=102, usw.)
 * 2. {@link DictionaryCodeResolver} OPEN/DONE/CANCELLED korrekt aufloest
 * 3. {@link TaskKeysetQueryRepository#findActivePage} benutzer-scoped Tasks nach aufgeloester
 *    Status-Id filtert (seede Task + TASK_USER_STATE)
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = DictionaryAndKeysetQueryIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      // Flyway: Schema via V1 aufbauen
      "spring.flyway.enabled=true",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0",
      "spring.flyway.locations=classpath:db/migration",
      // KSF: Dialekt explizit + kein DDL-Auto
      "ksf.persistence.dialect=MSSQL",
      "ksf.persistence.ddl-auto=none",
      // Modul-Registrierung deaktivieren (kein Gateway)
      "ksf.module-registration.enabled=false",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class DictionaryAndKeysetQueryIT {

  /** Mandant fuer Fach-Tabellen. */
  private static final long TENANT_ID = 700L;

  /** Feste Task-ID fuer Inbox-Pruefung. */
  private static final long TASK_ID_ACTIVE = 10001L;

  /** UUID des Test-Benutzers. */
  private static final UUID USER_REF = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

  private static HikariDataSource master;
  private static String dbName;

  @DynamicPropertySource
  static void configureDb(final DynamicPropertyRegistry registry) {
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
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    // PROD_FIRMA (FK fuer TASK.TENANT_ID + TASK_USER_STATE.TENANT_ID)
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?)"
            + " INSERT INTO [dbo].[PROD_FIRMA]"
            + " (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, CAST(GETDATE() AS DATE))",
        TENANT_ID, TENANT_ID, TENANT_ID);

    // TASK mit TASK_STATUS_ID=101 (OPEN), PRIORITY_ID=202 (NORMAL), DIRECTION_ID=302 (CUSTOMER_INTERNAL)
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?)"
            + " INSERT INTO [dbo].[TASK]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + "  TITLE, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID,"
            + "  LAST_ACTIVITY_AT, COMMENT_COUNT)"
            + " VALUES (?, 'ACTIVE', 'dict-query-it', GETDATE(), NEWID(), 1, ?,"
            + "  'Query-IT-Task', 101, 202, 302, ?, GETDATE(), 0)",
        TASK_ID_ACTIVE, TASK_ID_ACTIVE, TENANT_ID, TENANT_ID);

    // TASK_USER_STATE: Benutzer USER_REF ist Teilnehmer von TASK_ID_ACTIVE (nicht abgemeldet)
    // Parameter: IF NOT EXISTS (TASK_ID, USER_REF), VALUES: TENANT_ID, TASK_ID, USER_REF, USER_TENANT_ID
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID = ? AND USER_REF = ?)"
            + " INSERT INTO [dbo].[TASK_USER_STATE]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + "  TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED)"
            + " VALUES (10001001, 'ACTIVE', 'dict-query-it', GETDATE(), NEWID(), 1, ?,"
            + "  ?, ?, ?, 0, 0, 0)",
        TASK_ID_ACTIVE, USER_REF,   // IF NOT EXISTS-Pruefung
        TENANT_ID, TASK_ID_ACTIVE, USER_REF, TENANT_ID);  // VALUES: TENANT_ID, TASK_ID, USER_REF, USER_TENANT_ID
  }

  @Autowired
  private TaskStatusRepository taskStatusRepository;

  @Autowired
  private DictionaryCodeResolver resolver;

  @Autowired
  private TaskKeysetQueryRepository taskKeysetQueryRepository;

  @BeforeEach
  void setGlobalTenantContext() {
    // Global-Scope fuer Dictionary-Reads (TENANT_ID=0, Sentinel-UUID erforderlich)
    TenantContextHolder.setTenant(TenantInfo.global());
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  // ---- 1. findByCode je Dictionary -> geseedete ID ----------------------------------------

  @Test
  @DisplayName("findByCode_task_status_open_liefert_id_101")
  void findByCode_task_status_open_liefert_id_101() {
    final List<TaskStatus> result = taskStatusRepository.findByCode(TaskStatusCodes.OPEN);

    assertThat(result).as("findByCode(OPEN) muss genau 1 Eintrag liefern").hasSize(1);
    assertThat(result.get(0).getId()).as("ID fuer OPEN muss 101 sein").isEqualTo(101L);
    assertThat(result.get(0).getCode()).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("findByCode_task_status_done_liefert_id_102")
  void findByCode_task_status_done_liefert_id_102() {
    final List<TaskStatus> result = taskStatusRepository.findByCode(TaskStatusCodes.DONE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getId()).isEqualTo(102L);
  }

  @Test
  @DisplayName("findByCode_task_status_cancelled_liefert_id_103")
  void findByCode_task_status_cancelled_liefert_id_103() {
    final List<TaskStatus> result = taskStatusRepository.findByCode(TaskStatusCodes.CANCELLED);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getId()).isEqualTo(103L);
  }

  @Test
  @DisplayName("findByCode_unbekannter_code_liefert_leere_liste")
  void findByCode_unbekannter_code_liefert_leere_liste() {
    final List<TaskStatus> result = taskStatusRepository.findByCode("DOES_NOT_EXIST");

    assertThat(result).as("Unbekannter CODE muss leere Liste liefern").isEmpty();
  }

  // ---- 2. DictionaryCodeResolver OPEN/DONE/CANCELLED ----------------------------------------

  @Test
  @DisplayName("resolver_loest_open_done_cancelled_korrekt_auf")
  void resolver_loest_open_done_cancelled_korrekt_auf() {
    assertThat(resolver.resolveTaskStatusOpen()).isEqualTo(101L);
    assertThat(resolver.resolveTaskStatusDone()).isEqualTo(102L);
    assertThat(resolver.resolveTaskStatusCancelled()).isEqualTo(103L);
  }

  // ---- 3. Keyset-Query: benutzer-scoped Tasks gefiltert nach aufgeloester Status-Id ----------

  @Test
  @DisplayName("keyset_findActivePage_liefert_task_fuer_user_mit_open_status_id")
  void keyset_findActivePage_liefert_task_fuer_user_mit_open_status_id() {
    // Mandanten-Kontext auf TENANT_ID setzen (fuer Task-Read-Scope)
    TenantContextHolder.setTenant(new TenantInfo(UUID.randomUUID(), TENANT_ID));

    final Long openStatusId = resolver.resolveTaskStatusOpen(); // = 101

    // Keyset: sehr weit in der Zukunft -> alle vorhandenen Tasks einschliessen
    final LocalDateTime asOf = LocalDateTime.now().plusYears(10);
    final LocalDateTime lastActivityAt = LocalDateTime.now().plusYears(10);
    final UUID externalId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    final List<Task> page = taskKeysetQueryRepository.findActivePage(
        USER_REF, openStatusId, asOf, lastActivityAt, externalId, 10);

    assertThat(page)
        .as("Inbox-Query muss den geseedeten TASK finden (TASK_STATUS_ID=101, USER_REF gesetzt)")
        .anyMatch(t -> t.getId().equals(TASK_ID_ACTIVE));
  }

  @Test
  @DisplayName("keyset_findActivePage_liefert_keinen_task_fuer_anderen_user")
  void keyset_findActivePage_liefert_keinen_task_fuer_anderen_user() {
    TenantContextHolder.setTenant(new TenantInfo(UUID.randomUUID(), TENANT_ID));

    final Long openStatusId = resolver.resolveTaskStatusOpen();
    final UUID otherUser = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    final LocalDateTime asOf = LocalDateTime.now().plusYears(10);
    final LocalDateTime lastActivityAt = LocalDateTime.now().plusYears(10);
    final UUID externalId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    final List<Task> page = taskKeysetQueryRepository.findActivePage(
        otherUser, openStatusId, asOf, lastActivityAt, externalId, 10);

    assertThat(page)
        .as("Inbox-Query darf fuer anderen Benutzer keine Tasks des geseedeten Users liefern")
        .noneMatch(t -> t.getId().equals(TASK_ID_ACTIVE));
  }

  // ---- IT-Konfigurations-Context -----------------------------------------------------------

  /**
   * Minimaler Spring-Kontext: DataSource + Flyway (Schema) + KSF-Persistence + DictionaryCodeResolver.
   * Kein Web-Layer, keine Security, kein Gateway.
   */
  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.resolver",
      })
  @EnableTransactionManagement
  @EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
      })
  @ImportAutoConfiguration({
    // DataSource + JDBC
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    // Schema via Flyway V1
    FlywayAutoConfiguration.class,
    // KSF-Persistence: NovaRepository-Proxies, SequenceSchemaManager
    KsfPersistenceAutoConfiguration.class,
  })
  static class ItConfig {

    /** Pflichtbean fuer KSF-Audit-Felder. */
    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "dict-query-it";
    }

    /** DictionaryCodeResolver als Spring-Bean (benoetigt die Repository-Beans). */
    @Bean
    DictionaryCodeResolver dictionaryCodeResolver(
        final de.brasi.nova.taskmanagement.repository.dictionary.TaskStatusRepository taskStatusRepo,
        final de.brasi.nova.taskmanagement.repository.dictionary.TaskPriorityRepository taskPriorityRepo,
        final de.brasi.nova.taskmanagement.repository.dictionary.TaskDirectionRepository taskDirectionRepo,
        final de.brasi.nova.taskmanagement.repository.dictionary.NotificationTypeRepository notifTypeRepo,
        final de.brasi.nova.taskmanagement.repository.dictionary.NotificationSeverityRepository notifSeverityRepo,
        final de.brasi.nova.taskmanagement.repository.dictionary.TagSeverityRepository tagSeverityRepo) {
      return new DictionaryCodeResolver(
          taskStatusRepo, taskPriorityRepo, taskDirectionRepo,
          notifTypeRepo, notifSeverityRepo, tagSeverityRepo);
    }
  }
}
