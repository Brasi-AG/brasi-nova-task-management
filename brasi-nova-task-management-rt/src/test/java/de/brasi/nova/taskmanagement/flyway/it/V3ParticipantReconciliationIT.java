package de.brasi.nova.taskmanagement.flyway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Beweist die Upgrade-Reconciliation der V3-Migration (Codex-P1): Auf einer V1/V2-Datenbank, die
 * durch den fruehren check-then-insert-Race Mehrfachzeilen je (TASK_ID, PARTICIPANT_TENANT_ID,
 * PARTICIPANT_REF) enthaelt, bereinigt V3 die Duplikate (eine Zeile je Gruppe bleibt) und legt
 * anschliessend den UNIQUE an - die Migration laeuft also durch, statt am ADD CONSTRAINT zu
 * scheitern.
 *
 * <p>Steuert Flyway programmatisch (V1/V2 anwenden -> Duplikate seeden -> V3 anwenden), weil die
 * normale Spring-Flyway-Autoconfiguration alle Migrationen in einem Rutsch beim Context-Start
 * faehrt und kein Fenster zum Einschleusen von Alt-Duplikaten laesst.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class V3ParticipantReconciliationIT {

  private static final long TENANT_ID = 500L;
  private static final String GROUP_X_REF = "aaaaaaaa-0000-0000-0000-000000000001";
  private static final String GROUP_Y_REF = "bbbbbbbb-0000-0000-0000-000000000002";

  private HikariDataSource master;
  private String dbName;
  private HikariDataSource throwaway;
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
    throwaway = RefSchemaItDb.throwawayDataSource(dbName, 2);
    jdbc = new JdbcTemplate(throwaway);
  }

  @AfterEach
  void tearDown() {
    try {
      if (throwaway != null) {
        throwaway.close();
      }
    } finally {
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
  }

  @Test
  @DisplayName(
      "v3_bereinigt_duplikate_und_legt_unique_an: V1/V2 + Alt-Duplikate -> V3 behaelt je Gruppe"
          + " genau eine Zeile (Creator bevorzugt) und der UNIQUE greift danach")
  void v3_bereinigt_duplikate_und_legt_unique_an() {
    // 1. Flyway bis einschliesslich V2 (ohne V3-UNIQUE)
    migrateTo("2");

    // 2. FK-Ziel fuer TENANT_ID + Alt-Duplikate einschleusen (Zustand vor dem V3-Fix)
    jdbc.update(
        "INSERT INTO [dbo].[PROD_FIRMA] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " GUELTIG_VON) VALUES (?, 'ACTIVE', NEWID(), 1, ?, CAST(GETDATE() AS DATE))",
        TENANT_ID,
        TENANT_ID);

    // Gruppe X: drei Zeilen fuer denselben (TASK_ID, PARTICIPANT_TENANT_ID, PARTICIPANT_REF).
    // Erwartung: der Creator (ID=1) bleibt, ID=2 (aktiv, nicht-Creator) und ID=3 (ausgetreten)
    // werden entfernt.
    insertParticipant(1L, 9001L, GROUP_X_REF, true, true, null); // Creator aktiv
    insertParticipant(2L, 9001L, GROUP_X_REF, false, true, null); // Nicht-Creator aktiv
    insertParticipant(3L, 9001L, GROUP_X_REF, false, true, "UNSUBSCRIBED"); // ausgetreten

    // Gruppe Y: eine einzelne, nicht-duplizierte Zeile -> muss unangetastet bleiben.
    insertParticipant(4L, 9002L, GROUP_Y_REF, false, true, null);

    assertThat(countParticipants()).as("vor V3: 4 Zeilen").isEqualTo(4);

    // 3. Flyway V3 (Reconciliation + UNIQUE + Audit-Indizes)
    migrateTo("3");

    // 4a. Gruppe X auf genau eine Zeile reduziert, und zwar der Creator (ID=1)
    final Integer groupXCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = 9001", Integer.class);
    assertThat(groupXCount).as("Gruppe X auf eine Zeile bereinigt").isEqualTo(1);
    final Long survivorId =
        jdbc.queryForObject(
            "SELECT ID FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = 9001", Long.class);
    assertThat(survivorId).as("Creator-Zeile (ID=1) bleibt erhalten").isEqualTo(1L);

    // 4b. Nicht-Duplikat Gruppe Y unveraendert
    final Integer groupYCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = 9002", Integer.class);
    assertThat(groupYCount).as("Nicht-Duplikat (Gruppe Y) bleibt erhalten").isEqualTo(1);

    assertThat(countParticipants()).as("nach V3: 2 Zeilen").isEqualTo(2);

    // 4c. Der UNIQUE greift jetzt: erneuter Duplikat-INSERT fuer Gruppe X scheitert hart.
    assertThatThrownBy(() -> insertParticipant(99L, 9001L, GROUP_X_REF, false, false, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("UNIQUE");
  }

  // ---- Hilfsmethoden ---------------------------------------------------------------------------

  private void migrateTo(final String version) {
    Flyway.configure()
        .dataSource(
            RefSchemaItDb.throwawayJdbcUrl(dbName), RefSchemaItEnv.user(), RefSchemaItEnv.password())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .target(MigrationVersion.fromVersion(version))
        .load()
        .migrate();
  }

  private void insertParticipant(
      final long id,
      final long taskId,
      final String participantRef,
      final boolean creator,
      final boolean assignee,
      final String exitKind) {
    jdbc.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT]"
            + " (ID, TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + "  CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND)"
            + " VALUES (?, ?, ?, ?, 'PERSON', ?, ?, ?, 1, ?)",
        id,
        TENANT_ID,
        taskId,
        participantRef,
        TENANT_ID,
        creator ? 1 : 0,
        assignee ? 1 : 0,
        exitKind);
  }

  private Integer countParticipants() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT]", Integer.class);
  }
}
