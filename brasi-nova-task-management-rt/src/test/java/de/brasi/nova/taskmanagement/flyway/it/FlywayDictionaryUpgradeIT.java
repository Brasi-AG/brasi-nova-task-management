package de.brasi.nova.taskmanagement.flyway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Upgrade-Pfad-IT (Intake #133): beweist auf EINER Wegwerf-DB den echten Staged-Upgrade. Erst wird
 * bis V6 migriert (Zwischenstand wie eine bestehende dev-ui-DB vor V7: BEZEICHNUNG == CODE), dann auf
 * V7. Beim V7-Schritt validiert Flyway die Checksums von V1..V6 (validateOnMigrate ist Default true).
 *
 * <p>Danach wird geprueft: deutsche Labels gesetzt, CODE und EXTERNALID der Seed-Zeilen unveraendert,
 * V7 in {@code flyway_schema_history}. Das adressiert den Codex-IMPL-MINOR, dass ein reiner
 * Fresh-DB-Lauf den Heilungs-Upgrade nicht separat vom V6-Stand belegt.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class FlywayDictionaryUpgradeIT {

  private static HikariDataSource master;
  private static String dbName;

  @BeforeAll
  static void setup() {
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
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

  @Test
  @DisplayName("upgrade_v6_zu_v7_heilt_labels_und_bewahrt_code_externalid")
  void upgrade_v6_zu_v7_heilt_labels_und_bewahrt_code_externalid() {
    try (HikariDataSource ds = RefSchemaItDb.throwawayDataSource(dbName, 2)) {
      final JdbcTemplate jt = new JdbcTemplate(ds);

      // 1. Migration bis V6 = Zwischenstand wie eine bestehende dev-ui-DB vor V7.
      Flyway.configure()
          .dataSource(ds)
          .locations("classpath:db/migration")
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .target("6")
          .load()
          .migrate();

      // V6-Stand: BEZEICHNUNG == CODE (englisch), V7 noch NICHT in History.
      assertThat(bezeichnung(jt, "DIC_TASK_STATUS", "OPEN")).isEqualTo("OPEN");
      assertThat(bezeichnung(jt, "DIC_NOTIFICATION_TYPE", "DUE")).isEqualTo("DUE");
      assertThat(historyVersions(jt)).contains("6").doesNotContain("7");
      final String openExtId = externalId(jt, "DIC_TASK_STATUS", "OPEN");
      final String dueExtId = externalId(jt, "DIC_NOTIFICATION_TYPE", "DUE");
      final String dirExtId = externalId(jt, "DIC_TASK_DIRECTION", "BRASI_CUSTOMER");

      // 2. Migration auf V7 (gleiche DB) - validiert Checksums V1..V6 und wendet V7 an.
      Flyway.configure()
          .dataSource(ds)
          .locations("classpath:db/migration")
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .target("7")
          .load()
          .migrate();

      // V7-Stand: Labels deutsch, V7 in History.
      assertThat(historyVersions(jt)).contains("7");
      assertThat(bezeichnung(jt, "DIC_TASK_STATUS", "OPEN")).isEqualTo("Offen");
      assertThat(bezeichnung(jt, "DIC_NOTIFICATION_TYPE", "DUE")).isEqualTo("Fällig");
      assertThat(bezeichnung(jt, "DIC_TASK_DIRECTION", "BRASI_CUSTOMER")).isEqualTo("Brasi ↔ Kunde");

      // CODE unveraendert (Zeile weiter per CODE auffindbar) + EXTERNALID identisch zum V6-Stand.
      assertThat(externalId(jt, "DIC_TASK_STATUS", "OPEN")).isEqualToIgnoringCase(openExtId);
      assertThat(externalId(jt, "DIC_NOTIFICATION_TYPE", "DUE")).isEqualToIgnoringCase(dueExtId);
      assertThat(externalId(jt, "DIC_TASK_DIRECTION", "BRASI_CUSTOMER"))
          .isEqualToIgnoringCase(dirExtId);
    }
  }

  private static String bezeichnung(final JdbcTemplate jt, final String table, final String code) {
    return jt.queryForObject(
        "SELECT BEZEICHNUNG FROM [dbo].[" + table + "] WHERE TENANT_ID = 0 AND CODE = ?",
        String.class,
        code);
  }

  private static String externalId(final JdbcTemplate jt, final String table, final String code) {
    return jt.queryForObject(
        "SELECT CONVERT(nvarchar(36), EXTERNALID) FROM [dbo].["
            + table
            + "] WHERE TENANT_ID = 0 AND CODE = ?",
        String.class,
        code);
  }

  private static List<String> historyVersions(final JdbcTemplate jt) {
    return jt.queryForList(
        "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL"
            + " ORDER BY installed_rank",
        String.class);
  }
}
