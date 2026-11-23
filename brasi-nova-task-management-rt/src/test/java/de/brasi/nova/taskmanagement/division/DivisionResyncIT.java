package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.division.DivisionFetchResult.Members;
import de.brasi.nova.taskmanagement.division.DivisionFetchResult.NotFound;
import de.brasi.nova.taskmanagement.division.model.DivisionMemberPayload;
import de.brasi.nova.taskmanagement.division.model.DivisionMembersPayload;
import de.brasi.nova.taskmanagement.refentity.FirmReadRepository;
import de.brasi.nova.taskmanagement.repository.DivisionResyncRow;
import de.brasi.nova.taskmanagement.repository.TaskDivisionQueryRepository;
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
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-MSSQL-IT fuer {@code TaskDivisionQueryRepository#findAllForResync} (Intake #190, Task 7):
 * die mandantenuebergreifende Liste aller bekannten Abteilungen fuer den Resync-Job, mit
 * Mandanten-ID und Mandanten-UUID aus {@code PROD_FIRMA}, auch deaktivierte Abteilungen. Dazu
 * {@code FirmReadRepository#findTenantIdByUuid}, die Aufloesung der Mandanten-UUID im Processor:
 * aktive Firma liefert die ID, unbekannte UUID und nicht aktive Firma liefern leer.
 *
 * <p>Seed ueber {@link DivisionProjectionService}: Mandant 561 mit einer aktiven Abteilung, Mandant
 * 562 mit einem Grabstein (404, {@code DEACTIVE}). Gelesen wird unter {@code runAsSystem}
 * ({@code @GlobalQuery}). Mandant 565 ist als {@code DEACTIVE}-Firma angelegt.
 *
 * <p>Kontext wie {@code DivisionProjectionIT}. Uebersprungen ohne MSSQL-Zugang ({@link
 * RefSchemaItEnabled}).
 *
 * <p>Angenommene Signaturen: {@code record DivisionResyncRow(long tenantId, UUID tenantUuid, UUID
 * divisionRef)} im Paket {@code repository}; {@code List<DivisionResyncRow> findAllForResync()};
 * {@code Optional<Long> FirmReadRepository#findTenantIdByUuid(UUID)}.
 */
@SpringBootTest(
    classes = DivisionResyncIT.ItConfig.class,
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
class DivisionResyncIT {

  private static final long TENANT_A_ID = 561L;
  private static final UUID TENANT_A_UUID = UUID.fromString("00000056-0000-0056-0000-000000000561");
  private static final long TENANT_B_ID = 562L;
  private static final UUID TENANT_B_UUID = UUID.fromString("00000056-0000-0056-0000-000000000562");
  private static final long INACTIVE_TENANT_ID = 565L;
  private static final UUID INACTIVE_TENANT_UUID =
      UUID.fromString("00000056-0000-0056-0000-000000000565");

  private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 20, 10, 0, 0);
  private static final String REV1 = "1".repeat(64);

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private DivisionProjectionService projectionService;
  @Autowired private TaskDivisionQueryRepository divisionRepository;
  @Autowired private FirmReadRepository firmRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

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
  static void seedTenants(@Autowired JdbcTemplate jt) {
    seedTenant(jt, TENANT_A_ID, TENANT_A_UUID, "ACTIVE");
    seedTenant(jt, TENANT_B_ID, TENANT_B_UUID, "ACTIVE");
    seedTenant(jt, INACTIVE_TENANT_ID, INACTIVE_TENANT_UUID, "DEACTIVE");
  }

  private static void seedTenant(
      final JdbcTemplate jt, final long id, final UUID uuid, final String status) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, ?, ?, 1, ?, CAST(GETDATE() AS DATE))",
        id,
        id,
        status,
        uuid.toString(),
        id);
  }

  @Test
  @DisplayName("findAllForResync unter runAsSystem liefert Abteilungen beider Mandanten mit UUID")
  void i190_find_all_for_resync_spans_tenants() {
    final UUID activeRef = UUID.randomUUID();
    final UUID tombstoneRef = UUID.randomUUID();
    apply(
        TENANT_A_ID,
        TENANT_A_UUID,
        activeRef,
        new Members(
            new DivisionMembersPayload(
                activeRef,
                "Vertrieb",
                REV1,
                List.of(
                    new DivisionMemberPayload(
                        UUID.randomUUID(), "Anna Alpha", "Anna", "Alpha", "aalpha", false)))));
    apply(TENANT_B_ID, TENANT_B_UUID, tombstoneRef, new NotFound());

    final List<DivisionResyncRow> rows =
        TenantContextHolder.runAsSystem(() -> divisionRepository.findAllForResync());

    assertThat(rows)
        .contains(
            new DivisionResyncRow(TENANT_A_ID, TENANT_A_UUID, activeRef),
            new DivisionResyncRow(TENANT_B_ID, TENANT_B_UUID, tombstoneRef));
  }

  @Test
  @DisplayName("findTenantIdByUuid: aktive Firma liefert ihre ID, je UUID die eigene")
  void i190_find_tenant_id_by_uuid_returns_id_of_active_firm() {
    assertThat(findTenantId(TENANT_A_UUID)).contains(TENANT_A_ID);
    assertThat(findTenantId(TENANT_B_UUID)).contains(TENANT_B_ID);
  }

  @Test
  @DisplayName("findTenantIdByUuid: unbekannte UUID liefert leer")
  void i190_find_tenant_id_by_uuid_unknown_uuid_is_empty() {
    final UUID unknown = UUID.randomUUID();
    assertThat(firmCount(unknown)).as("Firma existiert nicht").isZero();

    assertThat(findTenantId(unknown)).isEmpty();
  }

  @Test
  @DisplayName("findTenantIdByUuid: nicht aktive Firma liefert leer, obwohl die Zeile existiert")
  void i190_find_tenant_id_by_uuid_inactive_firm_is_empty() {
    assertThat(firmCount(INACTIVE_TENANT_UUID)).as("Zeile ist vorhanden").isEqualTo(1);

    assertThat(findTenantId(INACTIVE_TENANT_UUID)).isEmpty();
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  /** Aufloesung wie im Processor: unter {@code runAsSystem}, ohne Mandanten-Kontext. */
  private Optional<Long> findTenantId(final UUID tenantUuid) {
    return TenantContextHolder.runAsSystem(() -> firmRepository.findTenantIdByUuid(tenantUuid));
  }

  private int firmCount(final UUID tenantUuid) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[PROD_FIRMA] WHERE EXTERNALID = ?",
        Integer.class,
        tenantUuid.toString());
  }

  private void apply(
      final long tenantId,
      final UUID tenantUuid,
      final UUID divisionRef,
      final DivisionFetchResult result) {
    TenantContextHolder.setTenant(new TenantInfo(tenantUuid, tenantId));
    try {
      new TransactionTemplate(transactionManager)
          .execute(status -> projectionService.apply(divisionRef, result, T1));
    } finally {
      TenantContextHolder.clear();
    }
  }

  // ---- IT-Konfigurations-Context --------------------------------------------------------------

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
  @Import(DivisionProjectionService.class)
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "division-resync-it";
    }
  }
}
