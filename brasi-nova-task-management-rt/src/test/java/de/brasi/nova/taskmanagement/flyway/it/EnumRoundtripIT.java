package de.brasi.nova.taskmanagement.flyway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Enum-Roundtrip-IT (Codex-Auflage): beweist, dass ParticipantKind und ExitKind korrekt als String
 * (name()) in der DB gespeichert werden koennen und ungueltige Werte am CHECK-Constraint scheitern.
 *
 * <p>Ansatz: direkte JDBC-Inserts mit den Enum-Namen (PERSON, TEAM, HANDOVER, UNSUBSCRIBED) +
 * Ruecklesepruefung der gespeicherten String-Werte. KSF persistiert Enums per name() (kein Ordinal,
 * kein Custom-Konverter), d.h. PARTICIPANT_KIND='PERSON' ist exakt das, was KSF schreibt.
 * Damit beweist dieser IT den vollstaendigen DB-Kontrakt: gueltige Enum-Namen werden gespeichert,
 * ungueltige String-Werte scheitern am CHECK.
 *
 * <p>SCOPE-GRENZE Task 5 (Owner-Entscheidung 2026-10-17, Codex-BLOCK-Remediation): Dieser IT
 * beweist den DB-SCHEMA-Kontrakt (CHECK + exakte name()-Strings speicherbar), NICHT den KSF-
 * save()-Pfad. KSF save() via Repository setzt eine generierte Q-Klasse voraus, die erst Scaffold
 * in Task 6 erzeugt. Der echte KSF-save()+Readback-Roundtrip (Beweis, dass KSF Enums via name()
 * schreibt) ist als verbindliches Akzeptanzkriterium in plan-v4 Task 6 verankert (nach Scaffold,
 * gegen die realen Q-Klassen/Repositories) - bewusste, geplante Verlagerung, kein Wegwerf-Testcode.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = EnumRoundtripIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      // Flyway in ITs einschalten
      "spring.flyway.enabled=true",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0",
      "spring.flyway.locations=classpath:db/migration",
      // KSF darf das Schema NICHT anfassen (Flyway ist allein zustaendig)
      "ksf.persistence.ddl-auto=none",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class EnumRoundtripIT {

  /** Mandant fuer Participant (FK PROD_FIRMA). */
  private static final long TENANT_ID = 600L;

  /** Feste Task-ID (kein KSF-Save fuer TASK; direkt eingefuegt, FK-Vorbedingung). */
  private static final long TASK_ID = 1L;

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

  @Autowired
  private JdbcTemplate jdbcTemplate;

  // ---- Vorbedingungen (PROD_FIRMA + TASK fuer FKs) ---

  private void ensurePrerequisites() {
    // PROD_FIRMA (FK fuer TASK_PARTICIPANT.TENANT_ID)
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, CAST(GETDATE() AS DATE))",
        TENANT_ID, TENANT_ID, TENANT_ID);

    // TASK-Zeile direkt einfuegen (FKs auf DIC_TASK_*; Seeds via Flyway V1 vorhanden: ID 101/201/301)
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, "
            + " TITLE, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, "
            + " LAST_ACTIVITY_AT, COMMENT_COUNT) "
            + "VALUES (?, 'ACTIVE', 'it-test', getdate(), NEWID(), 1, ?, "
            + " 'IT-Test-Task', 101, 201, 301, ?, getdate(), 0)",
        TASK_ID, TASK_ID, TENANT_ID, TENANT_ID);
  }

  // ---- 1. Roundtrip ParticipantKind.PERSON als String 'PERSON' gespeichert ---

  @Test
  @DisplayName("roundtrip_participant_kind_person_string_name_in_db")
  void roundtrip_participant_kind_person_string_name_in_db() {
    ensurePrerequisites();
    final long id = 10001L;
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG) "
            + "VALUES (?, 'ACTIVE', 'it-test', getdate(), NEWID(), 1, ?,"
            + " ?, NEWID(), ?, ?, 0, 0, 1)",
        id, id, TENANT_ID, TASK_ID, ParticipantKind.PERSON.name(), TENANT_ID);
    final String stored =
        jdbcTemplate.queryForObject(
            "SELECT PARTICIPANT_KIND FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?",
            String.class,
            id);
    assertThat(stored).isEqualTo(ParticipantKind.PERSON.name());
  }

  // ---- 2. Roundtrip ParticipantKind.TEAM als String 'TEAM' gespeichert ---

  @Test
  @DisplayName("roundtrip_participant_kind_team_string_name_in_db")
  void roundtrip_participant_kind_team_string_name_in_db() {
    ensurePrerequisites();
    final long id = 10002L;
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG) "
            + "VALUES (?, 'ACTIVE', 'it-test', getdate(), NEWID(), 1, ?,"
            + " ?, NEWID(), ?, ?, 0, 0, 1)",
        id, id, TENANT_ID, TASK_ID, ParticipantKind.TEAM.name(), TENANT_ID);
    final String stored =
        jdbcTemplate.queryForObject(
            "SELECT PARTICIPANT_KIND FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?",
            String.class,
            id);
    assertThat(stored).isEqualTo(ParticipantKind.TEAM.name());
  }

  // ---- 3. Roundtrip ExitKind.HANDOVER als String 'HANDOVER' gespeichert ---

  @Test
  @DisplayName("roundtrip_exit_kind_handover_string_name_in_db")
  void roundtrip_exit_kind_handover_string_name_in_db() {
    ensurePrerequisites();
    final long id = 10003L;
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT) "
            + "VALUES (?, 'ACTIVE', 'it-test', getdate(), NEWID(), 1, ?,"
            + " ?, NEWID(), ?, ?, 0, 0, 1, ?, getdate())",
        id, id, TENANT_ID, TASK_ID, ParticipantKind.PERSON.name(), TENANT_ID,
        ExitKind.HANDOVER.name());
    final String stored =
        jdbcTemplate.queryForObject(
            "SELECT EXIT_KIND FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?", String.class, id);
    assertThat(stored).isEqualTo(ExitKind.HANDOVER.name());
  }

  // ---- 4. Roundtrip ExitKind.UNSUBSCRIBED als String 'UNSUBSCRIBED' gespeichert ---

  @Test
  @DisplayName("roundtrip_exit_kind_unsubscribed_string_name_in_db")
  void roundtrip_exit_kind_unsubscribed_string_name_in_db() {
    ensurePrerequisites();
    final long id = 10004L;
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT) "
            + "VALUES (?, 'ACTIVE', 'it-test', getdate(), NEWID(), 1, ?,"
            + " ?, NEWID(), ?, ?, 0, 0, 1, ?, getdate())",
        id, id, TENANT_ID, TASK_ID, ParticipantKind.TEAM.name(), TENANT_ID,
        ExitKind.UNSUBSCRIBED.name());
    final String stored =
        jdbcTemplate.queryForObject(
            "SELECT EXIT_KIND FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?", String.class, id);
    assertThat(stored).isEqualTo(ExitKind.UNSUBSCRIBED.name());
  }

  // ---- 5. Ungueltige PARTICIPANT_KIND scheitert am CHECK ---

  @Test
  @DisplayName("ungueltige_participant_kind_scheitert_am_check_constraint")
  void ungueltige_participant_kind_scheitert_am_check_constraint() {
    ensurePrerequisites();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO [dbo].[TASK_PARTICIPANT] "
                        + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION,"
                        + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND,"
                        + " PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG)"
                        + " VALUES (9998, 'ACTIVE', 'test', getdate(), NEWID(), 1,"
                        + " ?, ?, NEWID(), 'INVALID_KIND', ?, 0, 0, 1)",
                    TENANT_ID, TASK_ID, TENANT_ID))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ---- 6. Ungueltige EXIT_KIND scheitert am CHECK ---

  @Test
  @DisplayName("ungueltige_exit_kind_scheitert_am_check_constraint")
  void ungueltige_exit_kind_scheitert_am_check_constraint() {
    ensurePrerequisites();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO [dbo].[TASK_PARTICIPANT] "
                        + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION,"
                        + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND,"
                        + " PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG,"
                        + " EXIT_KIND)"
                        + " VALUES (9997, 'ACTIVE', 'test', getdate(), NEWID(), 1,"
                        + " ?, ?, NEWID(), 'PERSON', ?, 0, 0, 1, 'INVALID_EXIT')",
                    TENANT_ID, TASK_ID, TENANT_ID))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ---- IT-Konfiguration (kein KSF-Context noetig; nur DataSource + Flyway) ---

  @SpringBootConfiguration
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    FlywayAutoConfiguration.class,
  })
  static class ItConfig {}
}
