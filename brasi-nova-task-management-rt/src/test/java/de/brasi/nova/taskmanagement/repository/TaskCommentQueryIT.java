package de.brasi.nova.taskmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.TaskComment;
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
 * P1-Repo-IT (BE-2): {@link TaskCommentQueryRepository#findActiveByTaskIdOrdered} liefert genau die
 * aktiven Kommentare EINER Aufgabe chronologisch - tenant-gebunden, aktiv-gefiltert, auf die
 * {@code TASK_ID} beschraenkt (Kommentar einer anderen Aufgabe desselben Mandanten erscheint NICHT).
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskCommentQueryIT.ItConfig.class,
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
class TaskCommentQueryIT {

  private static final long TENANT_ID = 590L;
  private static final long FOREIGN_TENANT_ID = 591L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000590");
  private static final UUID FOREIGN_TENANT_UUID =
      UUID.fromString("00000059-0000-0059-0000-000000000591");
  private static final UUID AUTHOR = UUID.fromString("00000059-000a-0059-0000-00000000059a");

  private static final long TASK_A = 590601L;
  private static final long TASK_B = 590602L;
  private static final long TASK_FOREIGN = 591601L;
  private static final UUID TASK_A_UUID = UUID.fromString("00000059-0601-0059-0000-000000590601");
  private static final UUID TASK_B_UUID = UUID.fromString("00000059-0602-0059-0000-000000590602");
  private static final UUID TASK_FOREIGN_UUID =
      UUID.fromString("00000059-1601-0059-0000-000000591601");

  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_LOW = 201L;
  private static final long DIRECTION_BRASI_CUSTOMER = 301L;

  private static final LocalDateTime T1 = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
  private static final LocalDateTime T2 = LocalDateTime.of(2026, 1, 1, 9, 0, 0);
  private static final LocalDateTime T3 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

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
    seedFirma(jt, TENANT_ID, TENANT_UUID);
    seedFirma(jt, FOREIGN_TENANT_ID, FOREIGN_TENANT_UUID);

    insertTask(jt, TASK_A, TASK_A_UUID, TENANT_ID);
    insertTask(jt, TASK_B, TASK_B_UUID, TENANT_ID);
    insertTask(jt, TASK_FOREIGN, TASK_FOREIGN_UUID, FOREIGN_TENANT_ID);

    // TASK_A: zwei aktive (T1 top-level, T3 reply) + eine DEACTIVE (T2) -> Reihenfolge C1, C3
    insertComment(jt, 590701L, TASK_A, TENANT_ID, null, "A-top-1", T1, "ACTIVE");
    insertComment(jt, 590702L, TASK_A, TENANT_ID, 590701L, "A-reply", T3, "ACTIVE");
    insertComment(jt, 590703L, TASK_A, TENANT_ID, null, "A-deactive", T2, "DEACTIVE");
    // andere Aufgabe B desselben Mandanten -> darf bei TASK_A NICHT erscheinen (TASK_ID-Filter)
    insertComment(jt, 590704L, TASK_B, TENANT_ID, null, "B-top", T1, "ACTIVE");
    // fremder Mandant -> darf NICHT erscheinen (Tenant-Binding)
    insertComment(jt, 590705L, TASK_FOREIGN, FOREIGN_TENANT_ID, null, "F-top", T1, "ACTIVE");
  }

  @BeforeEach
  void setTenantContext() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  @Test
  @DisplayName(
      "findActiveByTaskIdOrdered: nur aktive Kommentare DIESER Aufgabe, chronologisch, kein "
          + "anderer-Task/fremder-Mandant/DEACTIVE")
  void findActiveByTaskIdOrdered_nur_eigene_aktive_chronologisch(
      @Autowired TaskCommentQueryRepository repo) {
    final List<TaskComment> rows = repo.findActiveByTaskIdOrdered(TASK_A);

    assertThat(rows)
        .as("nur aktive TASK_A-Kommentare, chronologisch (T1 vor T3); DEACTIVE/B/fremd ausgeschlossen")
        .extracting(TaskComment::getId)
        .containsExactly(590701L, 590702L);
    assertThat(rows).extracting(TaskComment::getId).doesNotContain(590703L, 590704L, 590705L);
    // Reply traegt die interne Eltern-ID
    assertThat(rows)
        .filteredOn(c -> c.getId() == 590702L)
        .singleElement()
        .satisfies(c -> assertThat(c.getParentCommentId()).isEqualTo(590701L));

    // Isoliert den :tenantId-Filter (Codex-IMPL-Gate): Abfrage der FREMDEN Aufgabe-ID (TASK_FOREIGN)
    // unter Mandant 590. Der Kommentar 590705 existiert auf TASK_FOREIGN, aber mit TENANT_ID=591 ->
    // ohne :tenantId-Bindung wuerde er erscheinen; mit Bindung (590) ist das Ergebnis leer.
    assertThat(repo.findActiveByTaskIdOrdered(TASK_FOREIGN))
        .as(":tenantId=590 schliesst den TASK_FOREIGN-Kommentar (TENANT_ID=591) aus")
        .isEmpty();
  }

  // ---- Seed-Hilfen ---------------------------------------------------------------------------

  private static void seedFirma(final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static void insertTask(
      final JdbcTemplate jt, final long id, final UUID externalId, final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE, TASK_STATUS_ID, "
            + " PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT, COMMENT_COUNT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, 'IT-Task', ?, ?, ?, ?, GETDATE(), 0, 'it-seed', GETDATE())",
        id, id, externalId, tenantId, STATUS_OPEN, PRIORITY_LOW, DIRECTION_BRASI_CUSTOMER, tenantId);
  }

  private static void insertComment(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final Long parentCommentId,
      final String text,
      final LocalDateTime angelegtAm,
      final String status) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_COMMENT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_COMMENT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, AUTHOR_REF, "
            + " PARENT_COMMENT_ID, TEXT, IS_EDITED, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, ?, NEWID(), 1, ?, ?, ?, ?, ?, 0, 'it-seed', ?)",
        id, id, status, tenantId, taskId, AUTHOR, parentCommentId, text, angelegtAm);
  }

  // ---- IT-Konfigurations-Context -------------------------------------------------------------

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
      return () -> "comment-query-it";
    }
  }
}
