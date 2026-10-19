package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
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
 * Real-MSSQL-IT fuer den Inbox-Generationszaehler (Finding 2 / Option B, Slice 1/4): beweist gegen
 * echtes MSSQL, dass die Migration V4 ({@code TASK_INBOX_GENERATION}) greift und der atomare
 * {@code @NovaQuery(modifying=true)}-Bump mit automatischer {@code :tenantId}-Bindung korrekt
 * arbeitet (lazy Insert bei Erstzugriff, atomarer +1, Rowcount-Enforcement, UNIQUE je Benutzer).
 *
 * <p>Die Snapshot-Rejection-Logik (Cursor-Vergleich -> 410, Bracket-Retry) ist in
 * {@code InboxSnapshotServiceTest} unit-bewiesen; die HTTP-410-Abbildung im
 * {@code InboxSnapshotControllerIT}. Dieser IT deckt gezielt das NEUE DB-Verhalten ab.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = InboxGenerationIT.ItConfig.class,
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
class InboxGenerationIT {

  private static final long TENANT_ID = 591L;
  private static final UUID TENANT_UUID =
      UUID.fromString("00000059-0000-0059-0000-000000000591");
  private static final UUID USER_A_REF =
      UUID.fromString("00000059-0001-0059-0001-000000000591");
  private static final UUID USER_B_REF =
      UUID.fromString("00000059-0002-0059-0002-000000000591");

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
  }

  @BeforeEach
  void setTenantContext() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  // ---- IT 1: lazy Insert + atomarer Bump gegen echtes MSSQL -----------------------------------

  @Test
  @DisplayName("bump_legt_zeile_an_und_erhoeht_atomar_gegen_mssql")
  void bump_legt_zeile_an_und_erhoeht_atomar(
      @Autowired InboxGenerationService service,
      @Autowired TaskInboxGenerationQueryRepository repo) {
    // Noch keine Zeile -> Generation 0.
    assertThat(service.currentGeneration(USER_A_REF)).as("Start ohne Zeile = 0").isZero();

    // Erster Bump legt die Zeile an (lazy ensure, Start 0) und erhoeht atomar -> 1.
    service.bump(TENANT_ID, USER_A_REF);
    assertThat(service.currentGeneration(USER_A_REF)).as("Nach 1. Bump = 1").isEqualTo(1L);
    assertThat(repo.findByUserRef(USER_A_REF)).as("genau eine Zeile angelegt").hasSize(1);

    // Zweiter Bump erhoeht die vorhandene Zeile -> 2 (atomarer +1, kein Doppel-Insert).
    service.bump(TENANT_ID, USER_A_REF);
    assertThat(service.currentGeneration(USER_A_REF)).as("Nach 2. Bump = 2").isEqualTo(2L);
    assertThat(repo.findByUserRef(USER_A_REF)).as("weiterhin genau eine Zeile").hasSize(1);
  }

  // ---- IT 2: Generation ist je (TENANT_ID, USER_REF) isoliert ---------------------------------

  @Test
  @DisplayName("bump_je_benutzer_isoliert_unique_tenant_user")
  void bump_je_benutzer_isoliert(@Autowired InboxGenerationService service) {
    service.bump(TENANT_ID, USER_B_REF);
    assertThat(service.currentGeneration(USER_B_REF)).isEqualTo(1L);
    // USER_A wurde in IT 1 zweimal gebumpt; USER_B ist davon unberuehrt (eigene Zeile, UNIQUE).
    assertThat(service.currentGeneration(USER_B_REF)).as("USER_B eigener Zaehler").isEqualTo(1L);
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private static void seedFirmaIfAbsent(
      final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  // ---- IT-Konfigurations-Context --------------------------------------------------------------

  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
      })
  @EnableTransactionManagement
  @EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
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
      return () -> "inbox-generation-it";
    }

    @Bean
    InboxGenerationService inboxGenerationService(final TaskInboxGenerationQueryRepository repo) {
      return new InboxGenerationService(repo);
    }
  }
}
