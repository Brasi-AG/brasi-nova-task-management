package de.brasi.nova.taskmanagement.flyway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Flyway-Migrations-IT: beweist gegen echte MSSQL-Wegwerf-DB (fresh + preseeded), dass V1 alle
 * Tabellen anlegt, die Seeds korrekt vorhanden sind, Kollisionen fail-loud scheitern und
 * Enum-CHECKs greifen.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = FlywayMigrationIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      // Flyway in ITs einschalten (Baseline 0 + V1 laufen gegen Wegwerf-DB)
      "spring.flyway.enabled=true",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0",
      "spring.flyway.locations=classpath:db/migration",
      // KSF darf das Schema NICHT anfassen (Flyway ist allein zustaendig)
      "ksf.persistence.ddl-auto=none",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class FlywayMigrationIT {

  // ---- Alle DIC_*- und Fach-Tabellen, die V1 anlegt ----
  private static final List<String> EXPECTED_DIC_TABLES =
      List.of(
          "DIC_TASK_STATUS",
          "DIC_TASK_PRIORITY",
          "DIC_TASK_DIRECTION",
          "DIC_NOTIFICATION_TYPE",
          "DIC_NOTIFICATION_SEVERITY",
          "DIC_TAG_SEVERITY");

  private static final List<String> EXPECTED_FACT_TABLES =
      List.of(
          "TASK",
          "TASK_PARTICIPANT",
          "TASK_USER_STATE",
          "TASK_COMMENT",
          "TASK_HISTORY_ENTRY",
          "TASK_NOTIFICATION",
          "TASK_TAG",
          "TASK_TAG_ASSIGNMENT",
          "TASK_REMINDER_RECEIPT");

  private static HikariDataSource master;
  private static String dbName;

  @DynamicPropertySource
  static void configureDb(final DynamicPropertyRegistry registry) {
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
    // Fremd-Daten in PROD_FIRMA/PROD_BENUTZER (preseeded-Szenario): V1 darf sie nicht veraendern.
    // Sentinel-Zeilen mit festen Werten; nach V1 werden die vollstaendigen relevanten Werte geprueft.
    try (HikariDataSource throwaway = RefSchemaItDb.throwawayDataSource(dbName, 1)) {
      final JdbcTemplate jdbc = new JdbcTemplate(throwaway);
      jdbc.update(
          "INSERT INTO [dbo].[PROD_FIRMA] "
              + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, NAME, GUELTIG_VON) "
              + "VALUES (500, 'ACTIVE', '99999999-0000-0000-0000-000000000001', 7, 500,"
              + " 'SENTINEL-FIRMA', CAST(GETDATE() AS DATE))");
      jdbc.update(
          "INSERT INTO [dbo].[PROD_BENUTZER] "
              + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, LOGIN_NAME, VOLLER_NAME) "
              + "VALUES (700, 'ACTIVE', '99999999-0000-0000-0000-000000000002', 9, 500,"
              + " 'sentinel@brasi.de', 'Sentinel Benutzer')");
    }
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

  @Autowired
  private JdbcTemplate jdbcTemplate;

  // ---- 1. Alle Tabellen vorhanden ---

  @Test
  @DisplayName("v1_legt_alle_6_dic_tabellen_an")
  void v1_legt_alle_6_dic_tabellen_an() {
    for (final String table : EXPECTED_DIC_TABLES) {
      assertTableExists(table);
    }
  }

  @Test
  @DisplayName("v1_legt_alle_9_fach_tabellen_an")
  void v1_legt_alle_9_fach_tabellen_an() {
    for (final String table : EXPECTED_FACT_TABLES) {
      assertTableExists(table);
    }
  }

  @Test
  @DisplayName("flyway_schema_history_enthaelt_baseline0_und_v1")
  void flyway_schema_history_enthaelt_baseline0_und_v1() {
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT installed_rank, version, type FROM flyway_schema_history"
                + " ORDER BY installed_rank");
    // Baseline (version=0) + V1 (version=1)
    assertThat(rows).hasSizeGreaterThanOrEqualTo(2);
    final boolean hasBaseline =
        rows.stream().anyMatch(r -> "0".equals(String.valueOf(r.get("version"))));
    final boolean hasV1 =
        rows.stream().anyMatch(r -> "1".equals(String.valueOf(r.get("version"))));
    assertThat(hasBaseline).as("Baseline 0 in flyway_schema_history").isTrue();
    assertThat(hasV1).as("V1 in flyway_schema_history").isTrue();
  }

  // ---- 2. Preseeded-Sentinel in PROD_FIRMA + PROD_BENUTZER vollstaendig unveraendert ---

  @Test
  @DisplayName("prod_firma_sentinel_vollstaendig_unveraendert_nach_v1")
  void prod_firma_sentinel_vollstaendig_unveraendert_nach_v1() {
    final Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT ENTITY_STATUS, CONVERT(nvarchar(36), EXTERNALID) AS EXTERNALID, VERSION,"
                + " TENANT_ID, NAME FROM [dbo].[PROD_FIRMA] WHERE ID = 500");
    assertThat(row.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(String.valueOf(row.get("EXTERNALID")))
        .isEqualTo("99999999-0000-0000-0000-000000000001");
    assertThat(((Number) row.get("VERSION")).intValue()).isEqualTo(7);
    assertThat(((Number) row.get("TENANT_ID")).longValue()).isEqualTo(500L);
    assertThat(row.get("NAME")).isEqualTo("SENTINEL-FIRMA");
  }

  @Test
  @DisplayName("prod_benutzer_sentinel_vollstaendig_unveraendert_nach_v1")
  void prod_benutzer_sentinel_vollstaendig_unveraendert_nach_v1() {
    final Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT ENTITY_STATUS, CONVERT(nvarchar(36), EXTERNALID) AS EXTERNALID, VERSION,"
                + " TENANT_ID, LOGIN_NAME, VOLLER_NAME FROM [dbo].[PROD_BENUTZER] WHERE ID = 700");
    assertThat(row.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(String.valueOf(row.get("EXTERNALID")))
        .isEqualTo("99999999-0000-0000-0000-000000000002");
    assertThat(((Number) row.get("VERSION")).intValue()).isEqualTo(9);
    assertThat(((Number) row.get("TENANT_ID")).longValue()).isEqualTo(500L);
    assertThat(row.get("LOGIN_NAME")).isEqualTo("sentinel@brasi.de");
    assertThat(row.get("VOLLER_NAME")).isEqualTo("Sentinel Benutzer");
  }

  // ---- 2b. Enum-Spaltenlaenge = KSF-Default 255 (Code=Wahrheit; @NovaColumn ohne length) ---

  @Test
  @DisplayName("participant_kind_und_exit_kind_sind_nvarchar_255")
  void participant_kind_und_exit_kind_sind_nvarchar_255() {
    assertThat(characterMaxLength("TASK_PARTICIPANT", "PARTICIPANT_KIND")).isEqualTo(255);
    assertThat(characterMaxLength("TASK_PARTICIPANT", "EXIT_KIND")).isEqualTo(255);
  }

  // ---- 3. Seed-Assertions ---

  @Nested
  @DisplayName("Seed-Assertions je Dictionary")
  class SeedAssertions {

    @Test
    @DisplayName("dic_task_status_seeds_korrekt: OPEN DONE CANCELLED TENANT_ID=0 SYSTEM_PUBLIC")
    void dic_task_status_seeds_korrekt() {
      assertSeedCodes("DIC_TASK_STATUS", List.of("OPEN", "DONE", "CANCELLED"));
      assertAllGlobal("DIC_TASK_STATUS");
    }

    @Test
    @DisplayName("dic_task_priority_seeds_korrekt_mit_sort_order")
    void dic_task_priority_seeds_korrekt_mit_sort_order() {
      assertSeedCodes("DIC_TASK_PRIORITY", List.of("LOW", "NORMAL", "HIGH", "URGENT"));
      assertAllGlobal("DIC_TASK_PRIORITY");
      // SORT_ORDER: LOW=1, NORMAL=2, HIGH=3, URGENT=4
      assertSortOrder("LOW", 1);
      assertSortOrder("NORMAL", 2);
      assertSortOrder("HIGH", 3);
      assertSortOrder("URGENT", 4);
    }

    @Test
    @DisplayName("dic_task_direction_seeds_korrekt")
    void dic_task_direction_seeds_korrekt() {
      assertSeedCodes(
          "DIC_TASK_DIRECTION",
          List.of("BRASI_CUSTOMER", "CUSTOMER_INTERNAL", "INTER_COMPANY"));
      assertAllGlobal("DIC_TASK_DIRECTION");
    }

    @Test
    @DisplayName("dic_notification_type_seeds_korrekt_8_eintraege")
    void dic_notification_type_seeds_korrekt() {
      assertSeedCodes(
          "DIC_NOTIFICATION_TYPE",
          List.of("ASSIGN", "REPLY", "DUE", "MENTION", "DONE", "CHANGE", "HANDOVER", "SYSTEM"));
      assertAllGlobal("DIC_NOTIFICATION_TYPE");
    }

    @Test
    @DisplayName("dic_notification_severity_seeds_korrekt")
    void dic_notification_severity_seeds_korrekt() {
      assertSeedCodes("DIC_NOTIFICATION_SEVERITY", List.of("INFO", "SUCCESS", "WARN", "ERROR"));
      assertAllGlobal("DIC_NOTIFICATION_SEVERITY");
    }

    @Test
    @DisplayName("dic_tag_severity_seeds_korrekt_5_eintraege")
    void dic_tag_severity_seeds_korrekt() {
      assertSeedCodes(
          "DIC_TAG_SEVERITY", List.of("PRIMARY", "SUCCESS", "WARN", "DANGER", "SECONDARY"));
      assertAllGlobal("DIC_TAG_SEVERITY");
    }
  }

  // ---- 4. Kollisions-fail-loud ---

  @Test
  @DisplayName("doppelter_tenant_code_insert_scheitert_mit_unique_violation")
  void doppelter_tenant_code_insert_scheitert_mit_unique_violation() {
    // Zweiter INSERT mit gleichem (TENANT_ID=0, CODE='OPEN') muss hart scheitern
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO [dbo].[DIC_TASK_STATUS] "
                        + "(ID, ENTITY_STATUS, BEZEICHNUNG, ENTRY_TYPE, GUELTIG_VON, "
                        + " ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, CODE) "
                        + "VALUES (9991, 'ACTIVE', 'OPEN', 'SYSTEM_PUBLIC', "
                        + "  CONVERT(date, getdate()), 'test', getdate(), "
                        + "  NEWID(), 1, 0, 'OPEN')"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("UNIQUE");
  }

  // ---- Hilfsmethoden ---

  private void assertTableExists(final String tableName) {
    final Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
            Integer.class,
            tableName);
    assertThat(count).as("Tabelle " + tableName + " muss existieren").isEqualTo(1);
  }

  private void assertSeedCodes(final String table, final List<String> expectedCodes) {
    // CODE-Menge exakt
    final List<String> actualCodes =
        jdbcTemplate.queryForList(
            "SELECT CODE FROM [dbo].[" + table + "] ORDER BY CODE", String.class);
    assertThat(actualCodes)
        .as("Seed-CODEs in " + table)
        .containsExactlyInAnyOrderElementsOf(expectedCodes);
    // CODE/BEZEICHNUNG-Paare: Label = CODE-Wert (arch-v3 §2.1); je CODE genau ein Treffer
    for (final String code : expectedCodes) {
      final String bezeichnung =
          jdbcTemplate.queryForObject(
              "SELECT BEZEICHNUNG FROM [dbo].[" + table + "] WHERE CODE = ?", String.class, code);
      assertThat(bezeichnung)
          .as("BEZEICHNUNG (Label) fuer CODE %s in %s", code, table)
          .isEqualTo(code);
    }
  }

  private int characterMaxLength(final String table, final String column) {
    final Integer len =
        jdbcTemplate.queryForObject(
            "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
            Integer.class,
            table,
            column);
    assertThat(len).as("CHARACTER_MAXIMUM_LENGTH %s.%s", table, column).isNotNull();
    return len;
  }

  private void assertAllGlobal(final String table) {
    final Integer nonGlobal =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[" + table + "] WHERE TENANT_ID <> 0", Integer.class);
    assertThat(nonGlobal)
        .as(table + ": alle Seeds muessen TENANT_ID=0 haben")
        .isZero();
    final Integer notSystemPublic =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[" + table + "] WHERE ENTRY_TYPE <> 'SYSTEM_PUBLIC'",
            Integer.class);
    assertThat(notSystemPublic)
        .as(table + ": alle Seeds muessen ENTRY_TYPE=SYSTEM_PUBLIC haben")
        .isZero();
    final Integer notActive =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[" + table + "] WHERE ENTITY_STATUS <> 'ACTIVE'",
            Integer.class);
    assertThat(notActive)
        .as(table + ": alle Seeds muessen ENTITY_STATUS=ACTIVE haben")
        .isZero();
  }

  private void assertSortOrder(final String code, final int expectedOrder) {
    final Integer actual =
        jdbcTemplate.queryForObject(
            "SELECT SORT_ORDER FROM [dbo].[DIC_TASK_PRIORITY] WHERE CODE = ?",
            Integer.class,
            code);
    assertThat(actual).as("SORT_ORDER fuer PRIORITY " + code).isEqualTo(expectedOrder);
  }

  // ---- IT-Konfiguration (minimaler Spring-Context; kein KSF, kein Web) ---

  @SpringBootConfiguration
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    FlywayAutoConfiguration.class,
  })
  static class ItConfig {}
}
