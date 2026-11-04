package de.brasi.nova.taskmanagement.flyway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.FileCopyUtils;

/**
 * Transition-Detektions-IT (Intake #135, Phase 1 -&gt; Phase 3 GO-Gate, AC10 (v)-(viii)).
 *
 * <p>Fuehrt die DREI read-only Skalar-Checks aus der getrackten Ressource {@code
 * db/checks/tag-color-transition-check.sql} (D1 Alt-Writer, D2a Extra-Code, D2b Vollstaendigkeit)
 * gegen eine real-MSSQL-Wegwerf-DB aus, die auf V8 migriert ist. So ist die GO-Gate-Query-Logik
 * getestet (nicht nur dokumentiert); die Ausfuehrung gegen die Live-DB bleibt owner-gated.
 *
 * <p>GO zu Phase 3 GENAU DANN, wenn D1 == 0 UND D2a == 0 UND D2b == 5.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class TagTransitionDetectionIT {

  private static final String CHECK_RESOURCE = "db/checks/tag-color-transition-check.sql";

  @Test
  @DisplayName("ac10_viii_sauberer_stand_d1_0_d2a_0_d2b_5_bedeutet_go")
  void ac10_viii_clean_is_go() {
    withV8Db(
        jt -> {
          final List<Integer> r = runChecks(jt);
          assertThat(r.get(0)).as("D1 Alt-Writer").isZero();
          assertThat(r.get(1)).as("D2a Extra-Code").isZero();
          assertThat(r.get(2)).as("D2b kanonisch aktiv").isEqualTo(5);
          assertThat(isGo(r)).as("sauberer Stand -> GO").isTrue();
        });
  }

  @Test
  @DisplayName("ac10_v_d1_groesser_0_alt_writer_erkannt_kein_go")
  void ac10_v_d1_detected() {
    withV8Db(
        jt -> {
          // Alt-Writer: TASK_TAG mit COLOR NULL aber SEVERITY_ID gesetzt.
          jt.update(
              "INSERT INTO [dbo].[TASK_TAG]"
                  + " (ID, ENTITY_STATUS, BEZEICHNUNG, ENTRY_TYPE, GUELTIG_VON, ANGELEGT_VON,"
                  + "  ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, SEVERITY_ID, COLOR)"
                  + " VALUES (9400, 'ACTIVE', 'Alt-Writer', 'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE),"
                  + " 'it', getdate(), NEWID(), 1, 0, 604, NULL)");
          final List<Integer> r = runChecks(jt);
          assertThat(r.get(0)).as("D1 Alt-Writer erkannt").isGreaterThan(0);
          assertThat(isGo(r)).as("Alt-Writer -> kein GO").isFalse();
        });
  }

  @Test
  @DisplayName("ac10_vi_d2a_groesser_0_extra_aktiver_code_erkannt_kein_go")
  void ac10_vi_d2a_detected() {
    withV8Db(
        jt -> {
          // Zusaetzlicher aktiver+gueltiger Nicht-Kanon-Code.
          jt.update(
              "INSERT INTO [dbo].[DIC_TAG_SEVERITY]"
                  + " (ID, ENTITY_STATUS, BEZEICHNUNG, ENTRY_TYPE, GUELTIG_VON, ANGELEGT_VON,"
                  + "  ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, CODE)"
                  + " VALUES (608, 'ACTIVE', 'INFO', 'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), 'it',"
                  + " getdate(), NEWID(), 1, 0, 'INFO')");
          final List<Integer> r = runChecks(jt);
          assertThat(r.get(1)).as("D2a Extra-Code erkannt").isGreaterThan(0);
          assertThat(isGo(r)).as("Extra-Code -> kein GO").isFalse();
        });
  }

  @Test
  @DisplayName("ac10_vii_d2b_kleiner_5_kanonischer_code_deaktiviert_erkannt_kein_go")
  void ac10_vii_d2b_detected() {
    withV8Db(
        jt -> {
          // Ein kanonischer Code deaktiviert -> D2b < 5.
          jt.update(
              "UPDATE [dbo].[DIC_TAG_SEVERITY] SET [ENTITY_STATUS] = 'DEACTIVE' WHERE [ID] = 605");
          final List<Integer> r = runChecks(jt);
          assertThat(r.get(2)).as("D2b < 5 erkannt").isLessThan(5);
          assertThat(isGo(r)).as("deaktivierter kanonischer Code -> kein GO").isFalse();
        });
  }

  // ---- FIX 1 (BLOCKER): byte-exakter CODE-Vergleich (D2a/D2b, Java-Map-Key-Parity) ------------

  @Test
  @DisplayName("ac10_fix1_trailing_space_near_miss_d2a_groesser_0_und_d2b_kleiner_5_kein_go")
  void ac10_fix1_near_miss_code_breaks_go() {
    withV8Db(
        jt -> {
          // Kanonischen PRIMARY-Seed (601) auf trailing-space Near-Miss umwidmen. Mit naivem IN
          // bliebe D2a=0 und D2b=5 (faelschlich GO); byte-exakt: D2a>0 (extra) UND D2b<5 (PRIMARY
          // fehlt exakt) -> kein GO. Beweist die Byte-Genauigkeit des GO-Gates (= Java-Map-Key).
          jt.update(
              "UPDATE [dbo].[DIC_TAG_SEVERITY] SET [CODE] = N'PRIMARY ' WHERE [ID] = 601");
          final List<Integer> r = runChecks(jt);
          assertThat(r.get(1)).as("D2a Near-Miss als Extra-Code erkannt").isGreaterThan(0);
          assertThat(r.get(2)).as("D2b < 5 (PRIMARY byte-exakt fehlt)").isLessThan(5);
          assertThat(isGo(r)).as("Near-Miss-Code -> kein GO").isFalse();
        });
  }

  @Test
  @DisplayName("ac10_fix1_echtes_v8_backfill_faerbt_whitespace_near_miss_referenz_nicht")
  void ac10_fix1_real_backfill_skips_near_miss() {
    withV8Db(
        jt -> {
          // Inaktiver leading-space Near-Miss ' PRIMARY' (unter dem UNIQUE-Index distinct) + ein
          // TASK_TAG mit COLOR NULL, das ihn referenziert. Das ECHTE V8-Backfill (aus der Ressource
          // geladen) darf diese Zeile NICHT faerben, da ihr CODE kein byte-exakter Kanon ist.
          insertSeverity(jt, 609, " PRIMARY", "ACTIVE", null, null);
          insertTagWithSeverity(jt, 9500, "Near-Miss-Ref", 609L);
          jt.update(FlywayDictionaryUpgradeIT.extractV8BackfillBatch());
          final String color =
              jt.queryForObject(
                  "SELECT COLOR FROM [dbo].[TASK_TAG] WHERE ID = 9500", String.class);
          assertThat(color).as("Near-Miss-Referenz bleibt ungefaerbt").isNull();
        });
  }

  // ---- FIX 4 (MINOR): Validity-Fenster-Parity (D2a/D2b zaehlen nur aktiv+gueltig) -------------

  @Test
  @DisplayName("ac10_fix4_kanonischer_code_mit_zukuenftigem_gueltig_von_d2b_kleiner_5_kein_go")
  void ac10_fix4_future_gueltig_von_breaks_go() {
    withV8Db(
        jt -> {
          // PRIMARY (601) mit GUELTIG_VON in der Zukunft -> nicht aktiv+gueltig -> D2b < 5.
          jt.update(
              "UPDATE [dbo].[DIC_TAG_SEVERITY] SET [GUELTIG_VON] = DATEADD(day, 7, CAST(GETDATE() AS DATE)) WHERE [ID] = 601");
          final List<Integer> r = runChecks(jt);
          assertThat(r.get(2)).as("D2b < 5 (zukuenftiger GUELTIG_VON)").isLessThan(5);
          assertThat(isGo(r)).as("zukuenftiger kanonischer Code -> kein GO").isFalse();
        });
  }

  @Test
  @DisplayName("ac10_fix4_kanonischer_code_mit_abgelaufenem_gueltig_bis_d2b_kleiner_5_kein_go")
  void ac10_fix4_expired_gueltig_bis_breaks_go() {
    withV8Db(
        jt -> {
          // SECONDARY (605) mit GUELTIG_BIS in der Vergangenheit -> abgelaufen -> D2b < 5.
          jt.update(
              "UPDATE [dbo].[DIC_TAG_SEVERITY] SET [GUELTIG_BIS] = DATEADD(day, -1, CAST(GETDATE() AS DATE)) WHERE [ID] = 605");
          final List<Integer> r = runChecks(jt);
          assertThat(r.get(2)).as("D2b < 5 (abgelaufener GUELTIG_BIS)").isLessThan(5);
          assertThat(isGo(r)).as("abgelaufener kanonischer Code -> kein GO").isFalse();
        });
  }

  @Test
  @DisplayName("ac10_fix4_abgelaufener_extra_eintrag_zaehlt_nicht_als_d2a_alle_5_gueltig_go")
  void ac10_fix4_expired_extra_does_not_count_and_clean_is_go() {
    withV8Db(
        jt -> {
          // Ein NICHT-kanonischer Extra-Eintrag, aber abgelaufen -> darf NICHT als D2a zaehlen
          // (nur aktiv+gueltige zaehlen). Alle 5 kanonischen bleiben aktiv+gueltig -> GO.
          // GUELTIG_BIS DB-relativ (DATEADD/GETDATE) setzen, damit JVM- vs DB-Zeitzone den Test
          // nicht um Mitternacht kippt (konsistent zur SQL-Pruefung, die GETDATE() verwendet).
          insertSeverity(jt, 610, "INFO", "ACTIVE", null, null);
          jt.update(
              "UPDATE [dbo].[DIC_TAG_SEVERITY] SET [GUELTIG_BIS] = DATEADD(day, -1, CAST(GETDATE() AS DATE)) WHERE [ID] = 610");
          final List<Integer> r = runChecks(jt);
          assertThat(r.get(0)).as("D1 Alt-Writer").isZero();
          assertThat(r.get(1)).as("abgelaufener Extra-Code zaehlt NICHT als D2a").isZero();
          assertThat(r.get(2)).as("D2b kanonisch aktiv").isEqualTo(5);
          assertThat(isGo(r)).as("sauberer Stand (abgelaufener Extra ignoriert) -> GO").isTrue();
        });
  }

  // ---- Helper -------------------------------------------------------------------------------

  private static void insertSeverity(
      final JdbcTemplate jt,
      final long id,
      final String code,
      final String entityStatus,
      final java.time.LocalDate gueltigVon,
      final java.time.LocalDate gueltigBis) {
    jt.update(
        "INSERT INTO [dbo].[DIC_TAG_SEVERITY]"
            + " (ID, ENTITY_STATUS, BEZEICHNUNG, ENTRY_TYPE, GUELTIG_VON, GUELTIG_BIS,"
            + "  ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, CODE)"
            + " VALUES (?, ?, ?, 'SYSTEM_PUBLIC', COALESCE(?, CAST(GETDATE() AS DATE)), ?,"
            + " 'it', getdate(), NEWID(), 1, 0, ?)",
        id,
        entityStatus,
        code,
        gueltigVon,
        gueltigBis,
        code);
  }

  private static void insertTagWithSeverity(
      final JdbcTemplate jt, final long id, final String name, final Long severityId) {
    jt.update(
        "INSERT INTO [dbo].[TASK_TAG]"
            + " (ID, ENTITY_STATUS, BEZEICHNUNG, ENTRY_TYPE, GUELTIG_VON, ANGELEGT_VON,"
            + "  ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, SEVERITY_ID)"
            + " VALUES (?, 'ACTIVE', ?, 'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), 'it',"
            + " getdate(), NEWID(), 1, 0, ?)",
        id,
        name,
        severityId);
  }

  private static boolean isGo(final List<Integer> r) {
    return r.get(0) == 0 && r.get(1) == 0 && r.get(2) == 5;
  }

  /** Fuehrt die drei SELECT-Statements aus der getrackten Check-Ressource aus (D1, D2a, D2b). */
  private static List<Integer> runChecks(final JdbcTemplate jt) {
    final List<Integer> results = new ArrayList<>();
    for (final String sql : loadCheckStatements()) {
      results.add(jt.queryForObject(sql, Integer.class));
    }
    assertThat(results).as("drei Count-Checks in der Ressource").hasSize(3);
    return results;
  }

  /** Laedt die Check-Ressource und zerlegt sie in die einzelnen (nicht-leeren) SQL-Statements. */
  private static List<String> loadCheckStatements() {
    final String raw;
    try {
      raw =
          new String(
              FileCopyUtils.copyToByteArray(new ClassPathResource(CHECK_RESOURCE).getInputStream()),
              StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("Check-Ressource nicht lesbar: " + CHECK_RESOURCE, e);
    }
    // Erst saemtliche Kommentarzeilen (-- ...) entfernen (ein ';' in einem Kommentar darf NICHT als
    // Statement-Trenner wirken), DANN an ';' in die einzelnen Statements zerlegen.
    final StringBuilder codeOnly = new StringBuilder();
    for (final String line : raw.split("\\R")) {
      final String trimmed = line.trim();
      if (!trimmed.startsWith("--") && !trimmed.isEmpty()) {
        codeOnly.append(line).append('\n');
      }
    }
    final List<String> statements = new ArrayList<>();
    for (final String part : codeOnly.toString().split(";")) {
      final String stmt = part.trim();
      if (stmt.toUpperCase().startsWith("SELECT")) {
        statements.add(stmt);
      }
    }
    return statements;
  }

  private static void withV8Db(final Consumer<JdbcTemplate> body) {
    final HikariDataSource master = RefSchemaItDb.masterDataSource();
    final String dbName = RefSchemaItDb.createThrowawayDb(master);
    try (HikariDataSource ds = RefSchemaItDb.throwawayDataSource(dbName, 2)) {
      final JdbcTemplate jt = new JdbcTemplate(ds);
      Flyway.configure()
          .dataSource(ds)
          .locations("classpath:db/migration")
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .target("8")
          .load()
          .migrate();
      body.accept(jt);
    } finally {
      try {
        RefSchemaItDb.dropThrowawayDb(master, dbName);
      } finally {
        master.close();
      }
    }
  }
}
