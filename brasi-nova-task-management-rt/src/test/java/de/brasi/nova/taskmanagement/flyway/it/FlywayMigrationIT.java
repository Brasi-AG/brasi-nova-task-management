package de.brasi.nova.taskmanagement.flyway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
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

  /** Nova-Basisspalten (Vorlage V6 {@code TASK_SOURCE_CREATE_RECEIPT}). */
  private static final List<String> NOVA_BASE_COLUMNS =
      List.of(
          "ID",
          "ENTITY_STATUS",
          "ANGELEGT_VON",
          "AKTUALISIERT_VON",
          "GELOESCHT_VON",
          "ANGELEGT_AM",
          "AKTUALISIERT_AM",
          "GELOESCHT_AM",
          "EXTERNALID",
          "VERSION",
          "TENANT_ID");

  /** Gueltiger SHA-256-Hexwert (64 Zeichen) fuer Anhang-Inserts. */
  private static final String SHA256_SAMPLE = "a".repeat(64);

  /** Naechste freie ID fuer TASK_ANHANG_GRENZE-Inserts (Instanz je Testmethode, daher static). */
  private static long nextLimitId = 8501L;

  /** Tabellen der Abteilungs-Projektion aus V15 (Intake #190). */
  private static final List<String> V15_TABLES =
      List.of("TASK_ABTEILUNG", "TASK_ABTEILUNG_MITGLIED", "TASK_EREIGNIS_QUITTUNG");

  /**
   * Naechste freie ID fuer TASK_EREIGNIS_QUITTUNG-Inserts (Instanz je Testmethode, daher static).
   */
  private static long nextReceiptId = 8801L;

  private static HikariDataSource master;
  private static String dbName;

  @DynamicPropertySource
  static void configureDb(final DynamicPropertyRegistry registry) {
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
    // Fremd-Daten in PROD_FIRMA/PROD_BENUTZER (preseeded-Szenario): V1 darf sie nicht veraendern.
    // Sentinel-Zeilen mit festen Werten; nach V1 werden die vollstaendigen relevanten Werte
    // geprueft.
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

  @Autowired private JdbcTemplate jdbcTemplate;

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
    final boolean hasV1 = rows.stream().anyMatch(r -> "1".equals(String.valueOf(r.get("version"))));
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
      assertSeedCodes(
          "DIC_TASK_STATUS",
          Map.of("OPEN", "Offen", "DONE", "Erledigt", "CANCELLED", "Abgebrochen"));
      assertAllGlobal("DIC_TASK_STATUS");
    }

    @Test
    @DisplayName("dic_task_priority_seeds_korrekt_mit_sort_order")
    void dic_task_priority_seeds_korrekt_mit_sort_order() {
      assertSeedCodes(
          "DIC_TASK_PRIORITY",
          Map.of("LOW", "Niedrig", "NORMAL", "Normal", "HIGH", "Hoch", "URGENT", "Dringend"));
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
          Map.of(
              "BRASI_CUSTOMER", "Brasi ↔ Kunde",
              "CUSTOMER_INTERNAL", "Kundenintern",
              "INTER_COMPANY", "Firmenübergreifend"));
      assertAllGlobal("DIC_TASK_DIRECTION");
    }

    @Test
    @DisplayName("dic_notification_type_seeds_korrekt_12_eintraege")
    void dic_notification_type_seeds_korrekt() {
      // Intake #136: COMMENT als 9. Typ (V9 heal-or-insert) ergaenzt; Intake #157: CANCELLED als
      // 10. Typ (V10); Intake #147: PARTICIPANT als 11. Typ (V11); Intake #145: REMOVED als 12.
      // Typ (V12). Map.of ist auf 10 Paare begrenzt -> ofEntries verwenden.
      assertSeedCodes(
          "DIC_NOTIFICATION_TYPE",
          Map.ofEntries(
              Map.entry("ASSIGN", "Zuweisung"),
              Map.entry("REPLY", "Antwort"),
              Map.entry("DUE", "Fällig"),
              Map.entry("MENTION", "Erwähnung"),
              Map.entry("DONE", "Erledigt"),
              Map.entry("CHANGE", "Änderung"),
              Map.entry("HANDOVER", "Übergabe"),
              Map.entry("SYSTEM", "System"),
              Map.entry("COMMENT", "Neuer Kommentar"),
              Map.entry("CANCELLED", "Abgebrochen"),
              Map.entry("PARTICIPANT", "Als Beteiligter hinzugefügt"),
              Map.entry("REMOVED", "Aus der Aufgabe entfernt")));
      assertAllGlobal("DIC_NOTIFICATION_TYPE");
    }

    @Test
    @DisplayName("dic_notification_severity_seeds_korrekt")
    void dic_notification_severity_seeds_korrekt() {
      assertSeedCodes(
          "DIC_NOTIFICATION_SEVERITY",
          Map.of("INFO", "Information", "SUCCESS", "Erfolg", "WARN", "Warnung", "ERROR", "Fehler"));
      assertAllGlobal("DIC_NOTIFICATION_SEVERITY");
    }

    @Test
    @DisplayName("dic_tag_severity_seeds_korrekt_5_eintraege")
    void dic_tag_severity_seeds_korrekt() {
      assertSeedCodes(
          "DIC_TAG_SEVERITY",
          Map.of(
              "PRIMARY", "Primär",
              "SUCCESS", "Erfolg",
              "WARN", "Warnung",
              "DANGER", "Gefahr",
              "SECONDARY", "Sekundär"));
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

  // ---- 5. V3: Participant-UNIQUE + Audit-Indizes (Final-Review P2-6 + Pre-Release-Audit) ---

  @Test
  @DisplayName("flyway_schema_history_enthaelt_v3")
  void flyway_schema_history_enthaelt_v3() {
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
    final boolean hasV3 = rows.stream().anyMatch(r -> "3".equals(String.valueOf(r.get("version"))));
    assertThat(hasV3).as("V3 in flyway_schema_history").isTrue();
  }

  @Test
  @DisplayName(
      "task_participant_unique_verhindert_doppelte_teilnehmerzeile:"
          + " zweiter INSERT mit gleichem (TASK_ID, PARTICIPANT_TENANT_ID, PARTICIPANT_REF)"
          + " scheitert hart")
  void task_participant_unique_verhindert_doppelte_teilnehmerzeile() {
    final String participantRef = "77777777-0000-0000-0000-000000000009";
    // Erster Teilnehmer-Datensatz (TENANT_ID=500 referenziert die Sentinel-PROD_FIRMA)
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT]"
            + " (ID, TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND,"
            + "  PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG)"
            + " VALUES (8001, 500, 9001, ?, 'PERSON', 500, 0, 1, 1)",
        participantRef);

    // Zweiter Datensatz, gleiche (TASK_ID, PARTICIPANT_TENANT_ID, PARTICIPANT_REF), andere ID
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO [dbo].[TASK_PARTICIPANT]"
                        + " (ID, TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND,"
                        + "  PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG)"
                        + " VALUES (8002, 500, 9001, ?, 'PERSON', 500, 0, 1, 1)",
                    participantRef))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("UNIQUE");
  }

  @Test
  @DisplayName("v3_legt_audit_indizes_und_participant_unique_an")
  void v3_legt_audit_indizes_und_participant_unique_an() {
    assertIndexExists("TASK_PARTICIPANT", "UQ_TASK_PARTICIPANT_TASK_USER");
    assertIndexExists("TASK_NOTIFICATION", "IX_TASK_NOTIFICATION_RECIPIENT");
    assertIndexExists("TASK_HISTORY_ENTRY", "IX_TASK_HISTORY_ENTRY_TASK");
  }

  // ---- 6. V4: TASK_INBOX_GENERATION (Finding 2 / Option B) ---

  @Test
  @DisplayName("flyway_schema_history_enthaelt_v4")
  void flyway_schema_history_enthaelt_v4() {
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
    final boolean hasV4 = rows.stream().anyMatch(r -> "4".equals(String.valueOf(r.get("version"))));
    assertThat(hasV4).as("V4 in flyway_schema_history").isTrue();
  }

  @Test
  @DisplayName("v4_legt_task_inbox_generation_mit_unique_tenant_user_an")
  void v4_legt_task_inbox_generation_an() {
    assertTableExists("TASK_INBOX_GENERATION");
    assertIndexExists("TASK_INBOX_GENERATION", "UQ_TASK_INBOX_GENERATION_TENANT_USER");
  }

  @Test
  @DisplayName(
      "task_inbox_generation_unique_verhindert_doppelte_zeile:"
          + " zweiter INSERT mit gleichem (TENANT_ID, USER_REF) scheitert hart")
  void task_inbox_generation_unique_verhindert_doppelte_zeile() {
    final String userRef = "77777777-0000-0000-0000-00000000000a";
    // Erste Generationszeile (TENANT_ID=500 referenziert die Sentinel-PROD_FIRMA)
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_INBOX_GENERATION]"
            + " (ID, TENANT_ID, USER_REF, GENERATION) VALUES (8101, 500, ?, 0)",
        userRef);

    // Zweite Zeile, gleiche (TENANT_ID, USER_REF), andere ID -> UNIQUE-Verletzung
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO [dbo].[TASK_INBOX_GENERATION]"
                        + " (ID, TENANT_ID, USER_REF, GENERATION) VALUES (8102, 500, ?, 0)",
                    userRef))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("UNIQUE");
  }

  // ---- 7. V5: TASK_HANDOVER + TASK_HANDOVER_TARGET (BE-7) ---

  @Test
  @DisplayName("flyway_schema_history_enthaelt_v5")
  void flyway_schema_history_enthaelt_v5() {
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
    final boolean hasV5 = rows.stream().anyMatch(r -> "5".equals(String.valueOf(r.get("version"))));
    assertThat(hasV5).as("V5 in flyway_schema_history").isTrue();
  }

  @Test
  @DisplayName("v5_legt_task_handover_und_target_mit_index_und_uniques_an")
  void v5_legt_task_handover_und_target_an() {
    assertTableExists("TASK_HANDOVER");
    assertTableExists("TASK_HANDOVER_TARGET");
    assertIndexExists("TASK_HANDOVER", "IX_TASK_HANDOVER_READ");
    assertIndexExists("TASK_HANDOVER", "UQ_TASK_HANDOVER_EXTERNALID");
    assertIndexExists("TASK_HANDOVER_TARGET", "UQ_TASK_HANDOVER_TARGET_EXTERNALID");
    assertIndexExists("TASK_HANDOVER_TARGET", "UQ_TASK_HANDOVER_TARGET_EVENT_TO");
  }

  @Test
  @DisplayName(
      "v5_handover_target_fk_auf_handover: INSERT mit unbekannter HANDOVER_ID scheitert hart (FK)")
  void v5_handover_target_fk_auf_handover() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO [dbo].[TASK_HANDOVER_TARGET]"
                        + " (ID, TENANT_ID, HANDOVER_ID, TO_REF)"
                        + " VALUES (8301, 500, 999999, '77777777-0000-0000-0000-00000000000b')"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName(
      "v5_handover_target_unique_event_to: zweiter INSERT mit gleichem (HANDOVER_ID, TO_REF)"
          + " scheitert hart")
  void v5_handover_target_unique_event_to() {
    // Zuerst ein Handover-Ereignis (FK TENANT_ID=500 -> Sentinel-PROD_FIRMA).
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_HANDOVER] (ID, TENANT_ID, TASK_ID, FROM_REF, SHOULD_STAY)"
            + " VALUES (8310, 500, 9500, '77777777-0000-0000-0000-00000000000c', 0)");
    final String toRef = "77777777-0000-0000-0000-00000000000d";
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_HANDOVER_TARGET] (ID, TENANT_ID, HANDOVER_ID, TO_REF)"
            + " VALUES (8311, 500, 8310, ?)",
        toRef);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO [dbo].[TASK_HANDOVER_TARGET]"
                        + " (ID, TENANT_ID, HANDOVER_ID, TO_REF) VALUES (8312, 500, 8310, ?)",
                    toRef))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("UNIQUE");
  }

  @Test
  @DisplayName(
      "v5_handover_defaults: INSERT ohne ENTITY_STATUS/EXTERNALID/VERSION -> Defaults greifen")
  void v5_handover_defaults_greifen() {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_HANDOVER] (ID, TENANT_ID, TASK_ID, FROM_REF, SHOULD_STAY)"
            + " VALUES (8320, 500, 9501, '77777777-0000-0000-0000-00000000000e', 1)");
    final Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT ENTITY_STATUS, VERSION, CAST(SHOULD_STAY AS INT) AS SHOULD_STAY,"
                + " CASE WHEN EXTERNALID IS NULL THEN 0 ELSE 1 END AS HAS_EXTERNALID"
                + " FROM [dbo].[TASK_HANDOVER] WHERE ID = 8320");
    assertThat(row.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(((Number) row.get("VERSION")).intValue()).isEqualTo(1);
    assertThat(((Number) row.get("SHOULD_STAY")).intValue()).isEqualTo(1);
    assertThat(((Number) row.get("HAS_EXTERNALID")).intValue()).isEqualTo(1);
  }

  // ---- 8. V7: Deutsche Dictionary-Labels (Intake #133) ---
  // Hinweis: Dieser Fresh-DB-Lauf wendet V1..V7 der Reihe nach an und prueft den Zielzustand
  // (deutsche Labels, V7 in History, EXTERNALID unveraendert). Den expliziten Staged-Upgrade von
  // einem bereits gespeicherten V6-Stand auf V7 (inkl. Checksum-Validierung V1..V6) belegt separat
  // {@link FlywayDictionaryUpgradeIT}.

  @Test
  @DisplayName("flyway_schema_history_enthaelt_v7")
  void flyway_schema_history_enthaelt_v7() {
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
    final boolean hasV7 = rows.stream().anyMatch(r -> "7".equals(String.valueOf(r.get("version"))));
    assertThat(hasV7).as("V7 in flyway_schema_history").isTrue();
  }

  @Test
  @DisplayName(
      "v7_bewahrt_externalid_der_dictionary_seeds: V7 heilt nur BEZEICHNUNG, nicht EXTERNALID")
  void v7_bewahrt_externalid_der_dictionary_seeds() {
    assertExternalIdUnchanged("DIC_TASK_STATUS", "OPEN", "11111101-0000-0000-0000-000000000000");
    assertExternalIdUnchanged("DIC_TASK_PRIORITY", "LOW", "22222201-0000-0000-0000-000000000000");
    assertExternalIdUnchanged(
        "DIC_TASK_DIRECTION", "BRASI_CUSTOMER", "33333301-0000-0000-0000-000000000000");
    assertExternalIdUnchanged(
        "DIC_NOTIFICATION_TYPE", "ASSIGN", "44444401-0000-0000-0000-000000000000");
    assertExternalIdUnchanged(
        "DIC_NOTIFICATION_SEVERITY", "INFO", "55555501-0000-0000-0000-000000000000");
    assertExternalIdUnchanged(
        "DIC_TAG_SEVERITY", "PRIMARY", "66666601-0000-0000-0000-000000000000");
  }

  // ---- 9. V13: Gesehen-Marker SEEN_AT (Intake #150) ---

  @Test
  @DisplayName(
      "v13_legt_seen_at_nullable_datetime2_an: V13 in der History, Spalte SEEN_AT nullable"
          + " datetime2(7), bestehende Zeile behaelt NULL")
  void v13_legt_seen_at_nullable_datetime2_an() {
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(rows.stream().anyMatch(r -> "13".equals(String.valueOf(r.get("version")))))
        .as("V13 in flyway_schema_history")
        .isTrue();

    final Map<String, Object> col =
        jdbcTemplate.queryForMap(
            "SELECT DATA_TYPE, IS_NULLABLE, DATETIME_PRECISION FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_NAME = 'TASK_USER_STATE' AND COLUMN_NAME = 'SEEN_AT'");
    assertThat(col.get("DATA_TYPE")).isEqualTo("datetime2");
    assertThat(col.get("IS_NULLABLE")).isEqualTo("YES");
    assertThat(((Number) col.get("DATETIME_PRECISION")).intValue()).isEqualTo(7);

    // Insert ohne SEEN_AT (wie jeder Alt-Schreiber) bleibt gueltig und liefert NULL.
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_USER_STATE] (ID, ANGELEGT_VON, TENANT_ID, TASK_ID, USER_REF,"
            + " USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED)"
            + " VALUES (8401, 'it', 500, 9601, '77777777-0000-0000-0000-0000000000a1', 500, 0, 0,"
            + " 0)");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT SEEN_AT FROM [dbo].[TASK_USER_STATE] WHERE ID = 8401", Object.class))
        .isNull();
  }

  // ---- 10. V14: Anhang-Tabellen TASK_ANHANG und TASK_ANHANG_GRENZE (Intake #152) ---
  // Fremde Tabellen bleiben unberuehrt: die Sentinel-Pruefungen fuer PROD_FIRMA und PROD_BENUTZER
  // (Abschnitt 2) laufen nach allen Migrationen einschliesslich V14 und vergleichen feldweise.

  @Test
  @DisplayName(
      "i152_v14_task_anhang_columns: V14 in der History, Fachspalten mit Typ, Laenge und"
          + " Nullbarkeit, Nova-Basisspalten wie V6")
  void i152_v14_task_anhang_columns() {
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(rows.stream().anyMatch(r -> "14".equals(String.valueOf(r.get("version")))))
        .as("V14 in flyway_schema_history")
        .isTrue();

    // INFORMATION_SCHEMA.COLUMNS: Typ, Laenge, Nullbarkeit
    assertThat(column("TASK_ANHANG", "TASK_ID")).isEqualTo(col("bigint", null, false));
    assertThat(column("TASK_ANHANG", "KOMMENTAR_ID")).isEqualTo(col("bigint", null, true));
    assertThat(column("TASK_ANHANG", "DATEINAME")).isEqualTo(col("nvarchar", 255, false));
    assertThat(column("TASK_ANHANG", "MIME_TYP")).isEqualTo(col("nvarchar", 255, false));
    assertThat(column("TASK_ANHANG", "DATEIENDUNG")).isEqualTo(col("nvarchar", 10, false));
    assertThat(column("TASK_ANHANG", "GROESSE_BYTES")).isEqualTo(col("bigint", null, false));
    assertThat(column("TASK_ANHANG", "SHA256")).isEqualTo(col("char", 64, false));
    assertThat(column("TASK_ANHANG", "SPEICHERSCHLUESSEL")).isEqualTo(col("nvarchar", 400, true));
    assertThat(column("TASK_ANHANG", "HAT_VORSCHAU")).isEqualTo(col("bit", null, false));
    assertThat(column("TASK_ANHANG", "HOCHGELADEN_VON_REF"))
        .isEqualTo(col("uniqueidentifier", null, false));
    assertThat(columnNames("TASK_ANHANG")).containsAll(NOVA_BASE_COLUMNS);
    assertThat(columnNames("TASK_ANHANG_GRENZE"))
        .containsAll(NOVA_BASE_COLUMNS)
        .contains("TARIF", "MAX_DATEI_BYTES");
    assertThat(column("TASK_ANHANG_GRENZE", "TARIF")).isEqualTo(col("nvarchar", 255, false));
    assertThat(column("TASK_ANHANG_GRENZE", "MAX_DATEI_BYTES"))
        .isEqualTo(col("bigint", null, true));

    // Basisspalten exakt wie TASK_SOURCE_CREATE_RECEIPT (V6): Typ, Laenge, Nullbarkeit
    for (final String base : NOVA_BASE_COLUMNS) {
      final ColumnSpec reference = column("TASK_SOURCE_CREATE_RECEIPT", base);
      assertThat(column("TASK_ANHANG", base))
          .as("TASK_ANHANG.%s wie V6", base)
          .isEqualTo(reference);
      assertThat(column("TASK_ANHANG_GRENZE", base))
          .as("TASK_ANHANG_GRENZE.%s wie V6", base)
          .isEqualTo(reference);
    }
  }

  @Test
  @DisplayName(
      "i152_v14_foreign_keys_and_indexes: FKs auf PROD_FIRMA, TASK, TASK_COMMENT und die"
          + " Indizes mit Spaltenreihenfolge")
  void i152_v14_foreign_keys_and_indexes() {
    assertThat(foreignKeys("TASK_ANHANG"))
        .containsExactlyInAnyOrder(
            fk("TENANT_ID", "PROD_FIRMA", "ID"),
            fk("TASK_ID", "TASK", "ID"),
            fk("KOMMENTAR_ID", "TASK_COMMENT", "ID"));
    assertThat(indexColumns("TASK_ANHANG", "IX_TASK_ANHANG_TASK"))
        .containsExactly("TENANT_ID", "TASK_ID", "ENTITY_STATUS");
    assertThat(indexColumns("TASK_ANHANG", "IX_TASK_ANHANG_KOMMENTAR"))
        .containsExactly("TENANT_ID", "KOMMENTAR_ID");
    assertIndexExists("TASK_ANHANG", "UQ_TASK_ANHANG_EXTERNALID");

    assertThat(foreignKeys("TASK_ANHANG_GRENZE"))
        .containsExactly(fk("TENANT_ID", "PROD_FIRMA", "ID"));
    assertIndexExists("TASK_ANHANG_GRENZE", "UQ_TASK_ANHANG_GRENZE_EXTERNALID");
    assertThat(indexColumns("TASK_ANHANG_GRENZE", "UX_TASK_ANHANG_GRENZE_TENANT_ACTIVE"))
        .containsExactly("TENANT_ID");
  }

  @Test
  @DisplayName(
      "i152_v14_grenze_check_and_one_active_row_per_tenant: unbekannter Tarif und"
          + " MAX_DATEI_BYTES 0 scheitern am CHECK, zweite aktive Zeile je Mandant am gefilterten"
          + " Unique-Index, DEACTIVE plus ACTIVE erlaubt")
  void i152_v14_grenze_check_and_one_active_row_per_tenant() {
    // Mandanten anlegen, damit kein Insert am Firmen-FK scheitert (Grund wird per Name gepinnt)
    ensureFirm(560L);
    ensureFirm(561L);

    assertThatThrownBy(() -> insertLimit(560L, "GOLD", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("CHK_TASK_ANHANG_GRENZE_TARIF");
    assertThatThrownBy(() -> insertLimit(560L, "EXPERT", 0L))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("CHK_TASK_ANHANG_GRENZE_MAX");
    insertLimit(561L, "EXPERT", null);
    assertThatThrownBy(() -> insertLimit(561L, "ENTERPRISE", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("UX_TASK_ANHANG_GRENZE_TENANT_ACTIVE");
    deactivateLimits(561L);
    insertLimit(561L, "ENTERPRISE", null); // DEACTIVE + ACTIVE erlaubt

    final Integer active =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_ANHANG_GRENZE]"
                + " WHERE TENANT_ID = 561 AND ENTITY_STATUS = 'ACTIVE'",
            Integer.class);
    assertThat(active).as("genau eine aktive Grenze fuer Mandant 561").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i152_v14_hat_vorschau_defaults_to_0: Insert ohne HAT_VORSCHAU und ohne Basiswerte ->"
          + " Defaults greifen; GROESSE_BYTES 0 scheitert am CHECK")
  void i152_v14_hat_vorschau_defaults_to_0() {
    ensureTask(9701L);

    // Insert ohne HAT_VORSCHAU, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION,
    // KOMMENTAR_ID und SPEICHERSCHLUESSEL
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_ANHANG] (ID, TENANT_ID, TASK_ID, DATEINAME, MIME_TYP,"
            + " DATEIENDUNG, GROESSE_BYTES, SHA256, HOCHGELADEN_VON_REF)"
            + " VALUES (8601, 500, 9701, N'bericht.pdf', 'application/pdf', 'pdf', 1024, ?,"
            + " '77777777-0000-0000-0000-0000000000b1')",
        SHA256_SAMPLE);

    assertThat(readBit("TASK_ANHANG", "HAT_VORSCHAU", 8601L)).isFalse();
    final Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT ENTITY_STATUS, VERSION, KOMMENTAR_ID, SPEICHERSCHLUESSEL,"
                + " CASE WHEN EXTERNALID IS NULL THEN 0 ELSE 1 END AS HAS_EXTERNALID"
                + " FROM [dbo].[TASK_ANHANG] WHERE ID = 8601");
    assertThat(row.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(((Number) row.get("VERSION")).intValue()).isEqualTo(1);
    assertThat(((Number) row.get("HAS_EXTERNALID")).intValue()).isEqualTo(1);
    assertThat(row.get("KOMMENTAR_ID")).isNull();
    assertThat(row.get("SPEICHERSCHLUESSEL")).isNull();

    // CHK_TASK_ANHANG_GROESSE: leere Datei (0 Bytes) ist kein gueltiger Anhang
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO [dbo].[TASK_ANHANG] (ID, TENANT_ID, TASK_ID, DATEINAME, MIME_TYP,"
                        + " DATEIENDUNG, GROESSE_BYTES, SHA256, HOCHGELADEN_VON_REF)"
                        + " VALUES (8602, 500, 9701, N'leer.txt', 'text/plain', 'txt', 0, ?,"
                        + " '77777777-0000-0000-0000-0000000000b2')",
                    SHA256_SAMPLE))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("CHK_TASK_ANHANG_GROESSE");
  }

  // ---- 11. V15: Abteilungs-Projektion TASK_ABTEILUNG, TASK_ABTEILUNG_MITGLIED und
  // TASK_EREIGNIS_QUITTUNG (Intake #190) ---

  @Test
  @DisplayName(
      "i190_v15_columns: V15 in der History, Fachspalten mit Typ, Laenge und Nullbarkeit,"
          + " Nova-Basisspalten wie V6")
  void i190_v15_columns() {
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(rows.stream().anyMatch(r -> "15".equals(String.valueOf(r.get("version")))))
        .as("V15 in flyway_schema_history")
        .isTrue();

    // TASK_ABTEILUNG: Kopf der Projektion je Mandant und Abteilung
    assertThat(column("TASK_ABTEILUNG", "ABTEILUNG_REF"))
        .isEqualTo(col("uniqueidentifier", null, false));
    assertThat(column("TASK_ABTEILUNG", "NAME")).isEqualTo(col("nvarchar", 255, true));
    assertThat(column("TASK_ABTEILUNG", "REVISION")).isEqualTo(col("char", 64, true));
    assertThat(column("TASK_ABTEILUNG", "ABGERUFEN_AM")).isEqualTo(col("datetime2", null, false));
    assertThat(datetimePrecision("TASK_ABTEILUNG", "ABGERUFEN_AM")).isEqualTo(7);

    // TASK_ABTEILUNG_MITGLIED: Mitglieder einer Abteilung
    assertThat(column("TASK_ABTEILUNG_MITGLIED", "ABTEILUNG_ID"))
        .isEqualTo(col("bigint", null, false));
    assertThat(column("TASK_ABTEILUNG_MITGLIED", "MITARBEITER_REF"))
        .isEqualTo(col("uniqueidentifier", null, false));
    assertThat(column("TASK_ABTEILUNG_MITGLIED", "KERNMITGLIED"))
        .isEqualTo(col("bit", null, false));
    for (final String name : List.of("ANZEIGENAME", "VORNAME", "NACHNAME", "LOGIN_NAME")) {
      assertThat(column("TASK_ABTEILUNG_MITGLIED", name))
          .as("TASK_ABTEILUNG_MITGLIED.%s", name)
          .isEqualTo(col("nvarchar", 255, true));
    }

    // TASK_EREIGNIS_QUITTUNG: Inbox der verarbeiteten Ereignisse
    assertThat(column("TASK_EREIGNIS_QUITTUNG", "MANDANT_UUID"))
        .isEqualTo(col("uniqueidentifier", null, false));
    assertThat(column("TASK_EREIGNIS_QUITTUNG", "EREIGNIS_ID"))
        .isEqualTo(col("uniqueidentifier", null, false));
    assertThat(column("TASK_EREIGNIS_QUITTUNG", "ABTEILUNG_REF"))
        .isEqualTo(col("uniqueidentifier", null, true));
    assertThat(column("TASK_EREIGNIS_QUITTUNG", "STATUS")).isEqualTo(col("nvarchar", 255, false));
    assertThat(column("TASK_EREIGNIS_QUITTUNG", "FEHLER_MELDUNG"))
        .isEqualTo(col("nvarchar", 900, true));

    // Basisspalten exakt wie TASK_SOURCE_CREATE_RECEIPT (V6): Typ, Laenge, Nullbarkeit
    for (final String table : V15_TABLES) {
      assertThat(columnNames(table)).as("Basisspalten in %s", table).containsAll(NOVA_BASE_COLUMNS);
      for (final String base : NOVA_BASE_COLUMNS) {
        assertThat(column(table, base))
            .as("%s.%s wie V6", table, base)
            .isEqualTo(column("TASK_SOURCE_CREATE_RECEIPT", base));
      }
    }
  }

  @Test
  @DisplayName(
      "i190_v15_keys_and_indexes: FKs auf PROD_FIRMA und TASK_ABTEILUNG, Unique-Schluessel und"
          + " Indizes mit Spaltenreihenfolge")
  void i190_v15_keys_and_indexes() {
    assertThat(foreignKeys("TASK_ABTEILUNG")).containsExactly(fk("TENANT_ID", "PROD_FIRMA", "ID"));
    assertThat(foreignKeys("TASK_ABTEILUNG_MITGLIED"))
        .containsExactlyInAnyOrder(
            fk("TENANT_ID", "PROD_FIRMA", "ID"), fk("ABTEILUNG_ID", "TASK_ABTEILUNG", "ID"));
    assertThat(foreignKeys("TASK_EREIGNIS_QUITTUNG"))
        .containsExactly(fk("TENANT_ID", "PROD_FIRMA", "ID"));

    assertThat(uniqueColumns("TASK_ABTEILUNG", "UQ_TASK_ABTEILUNG_REF"))
        .containsExactly("TENANT_ID", "ABTEILUNG_REF");
    assertThat(uniqueColumns("TASK_ABTEILUNG_MITGLIED", "UQ_TASK_ABTEILUNG_MITGLIED"))
        .containsExactly("ABTEILUNG_ID", "MITARBEITER_REF");
    assertThat(uniqueColumns("TASK_EREIGNIS_QUITTUNG", "UQ_TASK_EREIGNIS_QUITTUNG_EREIGNIS"))
        .containsExactly("MANDANT_UUID", "EREIGNIS_ID");
    for (final String table : V15_TABLES) {
      assertThat(uniqueColumns(table, "UQ_" + table + "_EXTERNALID"))
          .as("UQ_%s_EXTERNALID wie V14", table)
          .containsExactly("EXTERNALID");
    }

    assertThat(indexColumns("TASK_ABTEILUNG", "IX_TASK_ABTEILUNG_STATUS"))
        .containsExactly("TENANT_ID", "ENTITY_STATUS");
    assertThat(indexColumns("TASK_ABTEILUNG_MITGLIED", "IX_TASK_ABTEILUNG_MITGLIED_ABT"))
        .containsExactly("TENANT_ID", "ABTEILUNG_ID");
    assertThat(indexColumns("TASK_EREIGNIS_QUITTUNG", "IX_TASK_EREIGNIS_QUITTUNG_ANGELEGT"))
        .containsExactly("ANGELEGT_AM");
  }

  @Test
  @DisplayName(
      "i190_v15_receipt_status_check: PROCESSED und DEAD gueltig, unbekannter Status scheitert"
          + " am CHECK")
  void i190_v15_receipt_status_check() {
    // Mandant anlegen, damit kein Insert am Firmen-FK scheitert (Grund wird per Name gepinnt)
    ensureFirm(560L);

    insertReceipt(560L, "PROCESSED");
    insertReceipt(560L, "DEAD");
    assertThatThrownBy(() -> insertReceipt(560L, "OTHER"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("CHK_TASK_EREIGNIS_QUITTUNG_STATUS");
  }

  // ---- Hilfsmethoden ---

  /** Spaltendefinition aus INFORMATION_SCHEMA.COLUMNS (Typ, Zeichenlaenge, Nullbarkeit). */
  private record ColumnSpec(String dataType, Integer maxLength, boolean nullable) {}

  /** Fremdschluessel-Spalte mit Zieltabelle und Zielspalte. */
  private record ForeignKey(String column, String refTable, String refColumn) {}

  private static ColumnSpec col(
      final String dataType, final Integer maxLength, final boolean nullable) {
    return new ColumnSpec(dataType, maxLength, nullable);
  }

  private static ForeignKey fk(final String column, final String refTable, final String refColumn) {
    return new ForeignKey(column, refTable, refColumn);
  }

  /** Liefert die Spaltendefinition oder {@code null}, wenn die Spalte fehlt. */
  private ColumnSpec column(final String table, final String column) {
    final List<ColumnSpec> specs =
        jdbcTemplate.query(
            "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE"
                + " FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
            // Laenge per getObject: NULL (bigint, bit, uniqueidentifier) bleibt null statt 0
            (rs, i) ->
                new ColumnSpec(
                    rs.getString("DATA_TYPE"),
                    rs.getObject("CHARACTER_MAXIMUM_LENGTH", Integer.class),
                    "YES".equals(rs.getString("IS_NULLABLE"))),
            table,
            column);
    return specs.isEmpty() ? null : specs.get(0);
  }

  private List<String> columnNames(final String table) {
    return jdbcTemplate.queryForList(
        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ?",
        String.class,
        table);
  }

  private List<ForeignKey> foreignKeys(final String table) {
    return jdbcTemplate.query(
        "SELECT c.name AS COL, rt.name AS REF_TABLE, rc.name AS REF_COL"
            + " FROM sys.foreign_key_columns fkc"
            + " JOIN sys.tables t ON t.object_id = fkc.parent_object_id"
            + " JOIN sys.columns c ON c.object_id = fkc.parent_object_id"
            + "  AND c.column_id = fkc.parent_column_id"
            + " JOIN sys.tables rt ON rt.object_id = fkc.referenced_object_id"
            + " JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id"
            + "  AND rc.column_id = fkc.referenced_column_id"
            + " WHERE t.name = ?",
        (rs, i) ->
            new ForeignKey(rs.getString("COL"), rs.getString("REF_TABLE"), rs.getString("REF_COL")),
        table);
  }

  /** Schluesselspalten eines Index in Reihenfolge (ohne INCLUDE-Spalten). */
  private List<String> indexColumns(final String table, final String index) {
    return jdbcTemplate.queryForList(
        "SELECT c.name FROM sys.indexes i"
            + " JOIN sys.tables t ON t.object_id = i.object_id"
            + " JOIN sys.index_columns ic ON ic.object_id = i.object_id"
            + "  AND ic.index_id = i.index_id"
            + " JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id"
            + " WHERE t.name = ? AND i.name = ? AND ic.is_included_column = 0"
            + " ORDER BY ic.key_ordinal",
        String.class,
        table,
        index);
  }

  /**
   * Schluesselspalten eines Unique-Schluessels in Reihenfolge; ein UNIQUE-Constraint legt einen
   * gleichnamigen Unique-Index an, daher genuegt der Blick auf {@code sys.indexes}.
   */
  private List<String> uniqueColumns(final String table, final String uniqueKey) {
    return jdbcTemplate.queryForList(
        "SELECT c.name FROM sys.indexes i"
            + " JOIN sys.tables t ON t.object_id = i.object_id"
            + " JOIN sys.index_columns ic ON ic.object_id = i.object_id"
            + "  AND ic.index_id = i.index_id"
            + " JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id"
            + " WHERE t.name = ? AND i.name = ? AND i.is_unique = 1"
            + "  AND ic.is_included_column = 0"
            + " ORDER BY ic.key_ordinal",
        String.class,
        table,
        uniqueKey);
  }

  /** Nachkommastellen einer datetime2-Spalte aus INFORMATION_SCHEMA.COLUMNS. */
  private Integer datetimePrecision(final String table, final String column) {
    return jdbcTemplate.queryForObject(
        "SELECT DATETIME_PRECISION FROM INFORMATION_SCHEMA.COLUMNS"
            + " WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
        Integer.class,
        table,
        column);
  }

  /**
   * Legt eine Ereignis-Quittung mit zufaelliger Mandanten-UUID und Ereignis-ID an; Basisspalten
   * kommen aus den Defaults.
   */
  private void insertReceipt(final long tenantId, final String status) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_EREIGNIS_QUITTUNG] (ID, TENANT_ID, MANDANT_UUID, EREIGNIS_ID,"
            + " STATUS) VALUES (?, ?, ?, ?, ?)",
        nextReceiptId++,
        tenantId,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        status);
  }

  private Boolean readBit(final String table, final String column, final long id) {
    return jdbcTemplate.queryForObject(
        "SELECT " + column + " FROM [dbo].[" + table + "] WHERE ID = ?", Boolean.class, id);
  }

  /** Legt eine Firma als Mandanten an, falls sie fehlt (FK-Ziel fuer TENANT_ID). */
  private void ensureFirm(final long id) {
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, NAME, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, 'IT-FIRMA', CAST(GETDATE() AS DATE))",
        id,
        id,
        id);
  }

  /** Legt eine Aufgabe des Sentinel-Mandanten 500 an, falls sie fehlt (FK-Ziel fuer TASK_ID). */
  private void ensureTask(final long id) {
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, TENANT_ID, TITLE, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID,"
            + " LAST_ACTIVITY_AT) "
            + "VALUES (?, 500, 'IT-Anhang-Aufgabe', 101, 201, 301, 500, GETDATE())",
        id,
        id);
  }

  private void insertLimit(final long tenantId, final String tariff, final Long maxFileBytes) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_ANHANG_GRENZE] (ID, TENANT_ID, TARIF, MAX_DATEI_BYTES)"
            + " VALUES (?, ?, ?, ?)",
        nextLimitId++,
        tenantId,
        tariff,
        new SqlParameterValue(Types.BIGINT, maxFileBytes));
  }

  private void deactivateLimits(final long tenantId) {
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_ANHANG_GRENZE] SET ENTITY_STATUS = 'DEACTIVE'"
            + " WHERE TENANT_ID = ? AND ENTITY_STATUS = 'ACTIVE'",
        tenantId);
  }

  private void assertIndexExists(final String tableName, final String indexName) {
    final Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys.indexes i"
                + " JOIN sys.tables t ON i.object_id = t.object_id"
                + " WHERE t.name = ? AND i.name = ?",
            Integer.class,
            tableName,
            indexName);
    assertThat(count)
        .as("Index " + indexName + " auf " + tableName + " muss existieren")
        .isEqualTo(1);
  }

  private void assertTableExists(final String tableName) {
    final Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
            Integer.class,
            tableName);
    assertThat(count).as("Tabelle " + tableName + " muss existieren").isEqualTo(1);
  }

  private void assertSeedCodes(final String table, final Map<String, String> codeToLabel) {
    // CODE-Menge exakt
    final List<String> actualCodes =
        jdbcTemplate.queryForList(
            "SELECT CODE FROM [dbo].[" + table + "] ORDER BY CODE", String.class);
    assertThat(actualCodes)
        .as("Seed-CODEs in " + table)
        .containsExactlyInAnyOrderElementsOf(codeToLabel.keySet());
    // CODE/BEZEICHNUNG-Paare: Label = deutscher Begriff (Intake #133 / V7); je CODE ein Treffer
    for (final Map.Entry<String, String> e : codeToLabel.entrySet()) {
      final String bezeichnung =
          jdbcTemplate.queryForObject(
              "SELECT BEZEICHNUNG FROM [dbo].[" + table + "] WHERE CODE = ?",
              String.class,
              e.getKey());
      assertThat(bezeichnung)
          .as("BEZEICHNUNG (Label) fuer CODE %s in %s", e.getKey(), table)
          .isEqualTo(e.getValue());
    }
  }

  /** Prueft, dass V7 die EXTERNALID eines Seed-Eintrags NICHT veraendert hat. */
  private void assertExternalIdUnchanged(
      final String table, final String code, final String expectedExternalId) {
    final String actual =
        jdbcTemplate.queryForObject(
            "SELECT CONVERT(nvarchar(36), EXTERNALID) FROM [dbo].[" + table + "] WHERE CODE = ?",
            String.class,
            code);
    assertThat(actual)
        .as("EXTERNALID fuer %s.%s muss durch V7 unveraendert bleiben", table, code)
        .isEqualToIgnoringCase(expectedExternalId);
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
    assertThat(nonGlobal).as(table + ": alle Seeds muessen TENANT_ID=0 haben").isZero();
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
    assertThat(notActive).as(table + ": alle Seeds muessen ENTITY_STATUS=ACTIVE haben").isZero();
  }

  private void assertSortOrder(final String code, final int expectedOrder) {
    final Integer actual =
        jdbcTemplate.queryForObject(
            "SELECT SORT_ORDER FROM [dbo].[DIC_TASK_PRIORITY] WHERE CODE = ?", Integer.class, code);
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
