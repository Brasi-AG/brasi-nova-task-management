package de.brasi.nova.taskmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
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
 * Diskriminator-IT fuer P1: {@link TaskKeysetQueryRepository}-Membership-Praedikate muessen
 * auf {@code asOf} gebunden sein (arch-v2 Snapshot-Watermark-Invariante).
 *
 * <p>Beweist (mutation-after-asOf-Beweis):
 * <ul>
 *   <li>findActivePage liefert Task, wenn Benutzer NACH asOf abgemeldet wurde
 *       (bei asOf war er noch aktiv).
 *   <li>findArchiveLeftPage liefert Task NICHT, wenn Abmeldung NACH asOf erfolgte
 *       (bei asOf war er noch NICHT abgemeldet).
 *   <li>findArchiveLeftPage liefert Task NICHT, wenn Participant-Austritt (EXIT_AT) NACH asOf
 *       (bei asOf hatte der Teilnehmer die Aufgabe noch nicht verlassen).
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskKeysetSnapshotIT.ItConfig.class,
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
class TaskKeysetSnapshotIT {

  private static final long TENANT_ID = 590L;
  private static final UUID TENANT_UUID =
      UUID.fromString("00000059-0000-0059-0000-000000000590");
  private static final UUID USER_A_REF =
      UUID.fromString("00000059-0001-0059-0001-000000000590");

  // feste Task-IDs (ausserhalb KSF-Sequenz >= 1 000 000 000; Namensraumtrennung von DIC-Seeds)
  private static final long TASK_ACTIVE_ID = 590001L;
  private static final long TASK_LEFT_UNSUB_ID = 590002L;
  private static final long TASK_LEFT_EXIT_ID = 590003L;
  private static final UUID TASK_ACTIVE_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590001");
  private static final UUID TASK_LEFT_UNSUB_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590002");
  private static final UUID TASK_LEFT_EXIT_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590003");

  // Bekannte Seed-IDs aus V1-Migration
  private static final long STATUS_OPEN = 101L;
  private static final long STATUS_DONE = 102L;
  private static final long PRIORITY_LOW = 201L;
  private static final long DIRECTION_BRASI_CUSTOMER = 301L;

  /**
   * asOf-Wasserzeichen: alle Test-Tasks haben {@code LAST_ACTIVITY_AT = T0 = asOf}.
   * Mutation (Abmeldung / Austritt) tritt bei {@code T_AFTER > T0} auf.
   */
  private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
  private static final LocalDateTime T_AFTER = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

  /** Keyset-Cursor fuer erste Seite (groesser als T0, alle Tasks bis asOf enthalten). */
  private static final LocalDateTime CURSOR_LAST_ACTIVITY =
      LocalDateTime.of(9999, 12, 31, 0, 0, 0);
  private static final UUID CURSOR_EXT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static HikariDataSource master;
  private static String dbName;

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
  static void seedData(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_ID, TENANT_UUID);

    // T1: OPEN-Task, Benutzer hat sich NACH asOf abgemeldet (findActivePage-Diskriminator).
    insertTask(jt, TASK_ACTIVE_ID, TASK_ACTIVE_UUID, TENANT_ID, STATUS_OPEN, T0, "IT-P1-Aktiv");
    insertUserState(jt, 590010L, TASK_ACTIVE_ID, TENANT_ID, USER_A_REF, T_AFTER);

    // T2: DONE-Task, Benutzer hat sich NACH asOf abgemeldet (archive-left-Unsub-Diskriminator).
    insertTask(
        jt, TASK_LEFT_UNSUB_ID, TASK_LEFT_UNSUB_UUID, TENANT_ID, STATUS_DONE, T0,
        "IT-P1-LeftUnsub");
    insertUserState(jt, 590020L, TASK_LEFT_UNSUB_ID, TENANT_ID, USER_A_REF, T_AFTER);

    // T3: OPEN-Task, Participant-Austritt EXIT_AT NACH asOf (archive-left-Exit-Diskriminator).
    insertTask(
        jt, TASK_LEFT_EXIT_ID, TASK_LEFT_EXIT_UUID, TENANT_ID, STATUS_OPEN, T0,
        "IT-P1-LeftExit");
    insertParticipant(jt, 590030L, TASK_LEFT_EXIT_ID, TENANT_ID, USER_A_REF, T_AFTER);
  }

  @BeforeEach
  void setTenantContext() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  // ---- IT 1: findActivePage – Benutzer war aktiv zum asOf-Wasserzeichen ----------------

  /**
   * Diskriminator: UNSUBSCRIBED_AT > asOf bedeutet "bei asOf noch aktiv" -> Task MUSS in der
   * aktiven Inbox erscheinen. Vor dem Fix (UNSUBSCRIBED_AT IS NULL) fehlt der Task (Abmeldung
   * setzt IS NULL auf false). Nach dem Fix erscheint er.
   */
  @Test
  @DisplayName(
      "findActivePage_liefert_task_bei_unsubscribed_at_nach_asof: "
          + "UNSUBSCRIBED_AT > asOf -> Benutzer war bei asOf aktiv -> Task sichtbar")
  void findActivePage_liefert_task_bei_unsubscribed_at_nach_asof(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findActivePage(USER_A_REF, STATUS_OPEN, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 10);

    assertThat(page)
        .as("T1 muss erscheinen: Abmeldung (UNSUBSCRIBED_AT=%s) NACH asOf (%s)", T_AFTER, T0)
        .extracting(Task::getId)
        .contains(TASK_ACTIVE_ID);
  }

  // ---- IT 2: findArchiveLeftPage – Abmeldung NACH asOf -> Task darf NICHT erscheinen ----

  /**
   * Diskriminator: UNSUBSCRIBED_AT > asOf bedeutet "bei asOf noch NICHT abgemeldet" -> Task darf
   * NICHT in archive-left erscheinen. Vor dem Fix (UNSUBSCRIBED_AT IS NOT NULL) erscheint er
   * faelschlicherweise. Nach dem Fix bleibt er unsichtbar.
   */
  @Test
  @DisplayName(
      "findArchiveLeftPage_enthaelt_task_nicht_bei_unsubscribed_at_nach_asof: "
          + "UNSUBSCRIBED_AT > asOf -> bei asOf noch NICHT abgemeldet -> NICHT in archive-left")
  void findArchiveLeftPage_enthaelt_task_nicht_bei_unsubscribed_at_nach_asof(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveLeftPage(USER_A_REF, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 10);

    assertThat(page)
        .as("T2 darf NICHT erscheinen: Abmeldung (UNSUBSCRIBED_AT=%s) NACH asOf (%s)", T_AFTER, T0)
        .extracting(Task::getId)
        .doesNotContain(TASK_LEFT_UNSUB_ID);
  }

  // ---- IT 3: findArchiveLeftPage – Participant-Austritt NACH asOf -> nicht in archive-left --

  /**
   * Diskriminator: EXIT_AT > asOf bedeutet "bei asOf noch nicht ausgetreten" -> Task darf NICHT
   * in archive-left erscheinen. Vor dem Fix (EXIT_KIND IS NOT NULL) erscheint er
   * faelschlicherweise. Nach dem Fix bleibt er unsichtbar.
   */
  @Test
  @DisplayName(
      "findArchiveLeftPage_enthaelt_task_nicht_bei_exit_at_nach_asof: "
          + "EXIT_AT > asOf -> Teilnehmer war bei asOf noch aktiv -> NICHT in archive-left")
  void findArchiveLeftPage_enthaelt_task_nicht_bei_exit_at_nach_asof(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveLeftPage(USER_A_REF, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 10);

    assertThat(page)
        .as("T3 darf NICHT erscheinen: EXIT_AT=%s NACH asOf=%s", T_AFTER, T0)
        .extracting(Task::getId)
        .doesNotContain(TASK_LEFT_EXIT_ID);
  }

  // ---- Hilfsmethoden -----------------------------------------------------------------------

  private static void seedFirmaIfAbsent(
      final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static void insertTask(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final long tenantId,
      final long statusId,
      final LocalDateTime lastActivityAt,
      final String title) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE, TASK_STATUS_ID, "
            + " PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT, COMMENT_COUNT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?, ?, 0, 'it-seed', GETDATE())",
        id, id, externalId, tenantId, title, statusId,
        PRIORITY_LOW, DIRECTION_BRASI_CUSTOMER, tenantId, lastActivityAt);
  }

  private static void insertUserState(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID userRef,
      final LocalDateTime unsubscribedAt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, USER_REF, "
            + " USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, 0, 0, 0, ?, 'it-seed', GETDATE())",
        id, id, tenantId, taskId, userRef, tenantId, unsubscribedAt);
  }

  private static void insertParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID participantRef,
      final LocalDateTime exitAt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 0, 1, 'HANDOVER', ?, "
            + " 'it-seed', GETDATE())",
        id, id, tenantId, taskId, participantRef, tenantId, exitAt);
  }

  // ---- IT-Konfigurations-Context --------------------------------------------------------

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
      return () -> "keyset-snapshot-it";
    }
  }
}
