package de.brasi.nova.taskmanagement.flyway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TagSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TagSeverityColors;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.FileCopyUtils;

/**
 * Upgrade-Pfad-IT (Intake #133): beweist auf EINER Wegwerf-DB den echten Staged-Upgrade. Erst wird
 * bis V6 migriert (Zwischenstand wie eine bestehende dev-ui-DB vor V7: BEZEICHNUNG == CODE), dann
 * auf V7. Beim V7-Schritt validiert Flyway die Checksums von V1..V6 (validateOnMigrate ist Default
 * true).
 *
 * <p>Danach wird geprueft: deutsche Labels gesetzt, CODE und EXTERNALID der Seed-Zeilen
 * unveraendert, V7 in {@code flyway_schema_history}. Das adressiert den Codex-IMPL-MINOR, dass ein
 * reiner Fresh-DB-Lauf den Heilungs-Upgrade nicht separat vom V6-Stand belegt.
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
      assertThat(bezeichnung(jt, "DIC_TASK_DIRECTION", "BRASI_CUSTOMER"))
          .isEqualTo("Brasi ↔ Kunde");

      // CODE unveraendert (Zeile weiter per CODE auffindbar) + EXTERNALID identisch zum V6-Stand.
      assertThat(externalId(jt, "DIC_TASK_STATUS", "OPEN")).isEqualToIgnoringCase(openExtId);
      assertThat(externalId(jt, "DIC_NOTIFICATION_TYPE", "DUE")).isEqualToIgnoringCase(dueExtId);
      assertThat(externalId(jt, "DIC_TASK_DIRECTION", "BRASI_CUSTOMER"))
          .isEqualToIgnoringCase(dirExtId);
    }
  }

  // ===========================================================================================
  // Intake #135 Phase 1 - V8 tag-color: AC6 (V7->V8-Upgrade + Backfill-Idempotenz), FK-Erhalt,
  // AC10-Preflight-Negativmatrix (P1 bidirektional + P2) + Positivfall.
  // Jeder Fall laeuft auf einer EIGENEN Wegwerf-DB (Isolation der Preflight-Zustaende).
  // ===========================================================================================

  @Test
  @DisplayName("ac6_v7_zu_v8_backfill_befuellt_color_aus_severity_und_ist_idempotent")
  void ac6_v7_zu_v8_backfill_und_idempotenz() {
    withFreshDbMigratedTo(
        "7",
        jt -> {
          // (2) je ein severity-behafteter TASK_TAG OHNE color pro kanonischem CODE (601..605;
          //     601=PRIMARY, 602=SUCCESS, 603=WARN, 604=DANGER, 605=SECONDARY).
          insertTag(jt, 9001, "T-PRIMARY", 601L, null);
          insertTag(jt, 9002, "T-SUCCESS", 602L, null);
          insertTag(jt, 9003, "T-WARN", 603L, null);
          insertTag(jt, 9004, "T-DANGER", 604L, null);
          insertTag(jt, 9005, "T-SECONDARY", 605L, null);

          // (3) V8 anwenden -> COLOR-Spalte + Nullable + einmaliger Backfill.
          migrateTo(jt, "8");
          assertThat(historyVersions(jt)).contains("8");

          // Parity: der DB-Backfill faerbt ALLE 5 exakt nach TagSeverityColors.BY_CODE
          // (Java-Mapping
          // = EINE Wahrheit, identisch zum V8-CASE).
          assertThat(colorOf(jt, 9001))
              .isEqualTo(TagSeverityColors.BY_CODE.get(TagSeverityCodes.PRIMARY));
          assertThat(colorOf(jt, 9002))
              .isEqualTo(TagSeverityColors.BY_CODE.get(TagSeverityCodes.SUCCESS));
          assertThat(colorOf(jt, 9003))
              .isEqualTo(TagSeverityColors.BY_CODE.get(TagSeverityCodes.WARN));
          assertThat(colorOf(jt, 9004))
              .isEqualTo(TagSeverityColors.BY_CODE.get(TagSeverityCodes.DANGER));
          assertThat(colorOf(jt, 9005))
              .isEqualTo(TagSeverityColors.BY_CODE.get(TagSeverityCodes.SECONDARY));

          // (4) eine Zeile auf custom COLOR + eine neue severity-Zeile mit COLOR NULL (Alt-Writer).
          jt.update("UPDATE [dbo].[TASK_TAG] SET [COLOR] = N'#123ABC' WHERE [ID] = 9001");
          insertTag(jt, 9006, "Alt-Writer-SUCCESS", 602L, null); // COLOR NULL

          // (5) Echtes V8-Backfill-Statement SEPARAT erneut ausfuehren (nicht Flyway.migrate()).
          runBackfillStatement(jt);

          // custom-Zeile UNVERAENDERT (WHERE COLOR IS NULL schuetzt), Alt-Writer-Zeile befuellt.
          assertThat(colorOf(jt, 9001)).isEqualTo("#123ABC");
          assertThat(colorOf(jt, 9006))
              .isEqualTo(TagSeverityColors.BY_CODE.get(TagSeverityCodes.SUCCESS));
        });
  }

  @Test
  @DisplayName("ac6_v8_setzt_severity_id_nullable_und_stellt_fk_identisch_wieder_her")
  void ac6_v8_fk_erhalt_und_nullable() {
    withFreshDbMigratedTo(
        "7",
        jt -> {
          // FK-Metadaten VOR V8 (nach V1..V7) festhalten.
          final Map<String, Object> fkBefore = foreignKeyMeta(jt);

          // V8 anwenden: SEVERITY_ID nullable + FK identisch wiederhergestellt.
          migrateTo(jt, "8");
          assertThat(historyVersions(jt)).contains("8");

          // FK-Metadaten NACH V8 - muessen in allen relevanten Attributen identisch sein.
          final Map<String, Object> fkAfter = foreignKeyMeta(jt);
          assertThat(fkAfter.get("fk_name"))
              .isEqualTo(fkBefore.get("fk_name"))
              .isEqualTo("FK_TASK_TAG_Severity");
          assertThat(fkAfter.get("ref_table"))
              .isEqualTo(fkBefore.get("ref_table"))
              .isEqualTo("DIC_TAG_SEVERITY");
          assertThat(fkAfter.get("ref_col")).isEqualTo(fkBefore.get("ref_col")).isEqualTo("ID");
          assertThat(fkAfter.get("parent_col"))
              .isEqualTo(fkBefore.get("parent_col"))
              .isEqualTo("SEVERITY_ID");
          // Referenzaktionen unveraendert (NO ACTION == 0) und FK ist trusted (WITH CHECK).
          // Hinweis: sys.foreign_keys liefert die Aktionen als tinyint -> JDBC Short; der
          // Vor/Nach-Vergleich ist typkonsistent, der Soll-Wert 0 wird numerisch (intValue)
          // geprueft.
          assertThat(fkAfter.get("del_action")).isEqualTo(fkBefore.get("del_action"));
          assertThat(((Number) fkAfter.get("del_action")).intValue()).isZero();
          assertThat(fkAfter.get("upd_action")).isEqualTo(fkBefore.get("upd_action"));
          assertThat(((Number) fkAfter.get("upd_action")).intValue()).isZero();
          assertThat(fkAfter.get("is_not_trusted")).isEqualTo(fkBefore.get("is_not_trusted"));
          assertThat(((Number) fkAfter.get("is_not_trusted")).intValue()).isZero();

          // NULL-SEVERITY_ID ist nach V8 erlaubt (color-only / neutraler Tag).
          insertTag(jt, 9100, "Neutral-Tag", null, "#60A5FA");
          assertThat(colorOf(jt, 9100)).isEqualTo("#60A5FA");

          // FK bleibt scharf: ungueltige (nicht existierende) SEVERITY_ID -> FK-Verletzung.
          assertThatThrownBy(() -> insertTag(jt, 9101, "Bad-FK", 999999L, null))
              .isInstanceOf(DataIntegrityViolationException.class);
        });
  }

  @Test
  @DisplayName("ac10_p1a_zusaetzlicher_aktiver_code_vor_v8_laesst_migration_scheitern")
  void ac10_p1a_extra_active_code_fails() {
    withFreshDbMigratedTo(
        "7",
        jt -> {
          // Zusaetzlicher aktiver+gueltiger, nicht-kanonischer Severity-Code (6.).
          insertSeverity(jt, 606, "INFO", "ACTIVE", null);
          assertV8Fails(jt, "P1");
        });
  }

  @Test
  @DisplayName("ac10_p1b_fehlender_kanonischer_code_vor_v8_laesst_migration_scheitern")
  void ac10_p1b_missing_canonical_code_fails() {
    withFreshDbMigratedTo(
        "7",
        jt -> {
          // Ein kanonischer Code (SECONDARY=605) deaktiviert -> P1 (Vollstaendigkeit) verletzt.
          jt.update(
              "UPDATE [dbo].[DIC_TAG_SEVERITY] SET [ENTITY_STATUS] = 'DEACTIVE' WHERE [ID] = 605");
          assertV8Fails(jt, "P1");
        });
  }

  @Test
  @DisplayName(
      "ac10_p2_task_tag_referenziert_inaktiven_nicht_kanonischen_eintrag_laesst_v8_scheitern")
  void ac10_p2_reference_to_non_canonical_fails() {
    withFreshDbMigratedTo(
        "7",
        jt -> {
          // Ein INAKTIVER nicht-kanonischer Dictionary-Eintrag (P1 sieht ihn nicht, da nicht
          // aktiv)...
          insertSeverity(jt, 607, "LEGACY", "DEACTIVE", null);
          // ...aber TASK_TAG referenziert ihn -> P2 (Referenz-Totalitaet) verletzt.
          insertTag(jt, 9200, "Legacy-Ref", 607L, null);
          assertV8Fails(jt, "P2");
        });
  }

  // ---- FIX 1 (BLOCKER): byte-exakter CODE-Vergleich (fail-closed, Java-Map-Key-Parity) ---------
  // Die Server-Default-Kollation ist case-INSENSITIV und '=' / IN PADDEN nachgestellte Leerzeichen
  // weg. Ohne Byte-Genauigkeit zaehlte ein aktiver+gueltiger CODE 'PRIMARY ' (trailing space) oder
  // 'primary' (lower) faelschlich als kanonisch (P1 saehe 5/0) und wuerde gefaerbt, waere aber KEIN
  // TagSeverityColors.BY_CODE-Key -> SQL- und Java-Domaene divergierten. Live gegen die reale DB
  // belegt: der UNIQUE(TENANT_ID,CODE)-Index (ci/pad) blockiert das INSERT eines solchen Near-Miss
  // neben dem exakten Seed, die Entartung ist daher nur durch UMWIDMEN des kanonischen Seeds selbst
  // erreichbar - und genau DAS muss P1 fail-closed fangen. Mit naivem IN liefe die Migration durch.

  @Test
  @DisplayName("ac10_p1_fix1_trailing_space_near_miss_code_laesst_v8_scheitern")
  void ac10_p1_fix1_trailing_space_near_miss_fails() {
    withFreshDbMigratedTo(
        "7",
        jt -> {
          // Kanonischen PRIMARY-Seed (601) auf trailing-space Near-Miss umwidmen (kein Duplikat,
          // da einzige PRIMARY-Zeile). Mit naivem IN zaehlte er weiter als 'PRIMARY' -> P1 gruen.
          jt.update("UPDATE [dbo].[DIC_TAG_SEVERITY] SET [CODE] = N'PRIMARY ' WHERE [ID] = 601");
          assertV8Fails(jt, "P1");
        });
  }

  @Test
  @DisplayName("ac10_p1_fix1_lowercase_near_miss_code_laesst_v8_scheitern")
  void ac10_p1_fix1_lowercase_near_miss_fails() {
    withFreshDbMigratedTo(
        "7",
        jt -> {
          // Kanonischen PRIMARY-Seed (601) auf lowercase Near-Miss umwidmen. Case-insensitive '='
          // wuerde ihn als 'PRIMARY' sehen (P1 gruen); byte-exakt (BIN2) faengt ihn fail-closed.
          jt.update("UPDATE [dbo].[DIC_TAG_SEVERITY] SET [CODE] = N'primary' WHERE [ID] = 601");
          assertV8Fails(jt, "P1");
        });
  }

  @Test
  @DisplayName("ac10_p2_fix1_whitespace_near_miss_referenz_laesst_v8_scheitern")
  void ac10_p2_fix1_whitespace_near_miss_reference_fails() {
    withFreshDbMigratedTo(
        "7",
        jt -> {
          // Alle 5 kanonischen bleiben exakt+aktiv (P1 gruen). Ein INAKTIVER Eintrag mit leading-
          // space Near-Miss ' PRIMARY' (unter dem UNIQUE-Index distinct, da leading space nicht
          // gepaddet wird) wird von P1 nicht gesehen (inaktiv) - ein TASK_TAG referenziert ihn.
          // Der byte-exakte P2-JOIN muss ihn als nicht-kanonisch werten -> P2 fail-closed.
          insertSeverity(jt, 609, " PRIMARY", "DEACTIVE", null);
          insertTag(jt, 9210, "Whitespace-Ref", 609L, null);
          assertV8Fails(jt, "P2");
        });
  }

  @Test
  @DisplayName("ac10_positiv_genau_5_kanonisch_und_alle_referenzen_kanonisch_migration_ok")
  void ac10_positive_clean_domain_ok() {
    withFreshDbMigratedTo(
        "7",
        jt -> {
          insertTag(jt, 9300, "Clean-PRIMARY", 601L, null);
          // Genau 5 kanonische aktiv+gueltig + alle Referenzen kanonisch -> V8 geht durch.
          migrateTo(jt, "8");
          assertThat(historyVersions(jt)).contains("8");
          assertThat(colorOf(jt, 9300)).isEqualTo("#60A5FA"); // PRIMARY -> blue-400
        });
  }

  // ===========================================================================================
  // Intake #136 - V9 notification-type COMMENT: I2 (frischer V1->V9), I2b (Heal abweichende
  // Schreibweise + stale Metadaten), V9-Skript-Idempotenz (zweiter Lauf des heal-or-insert).
  // ===========================================================================================

  @Test
  @DisplayName("i2_frisch_v1_zu_v9_genau_ein_aktiver_comment_typ_resolverfaehig")
  void i2_fresh_v1_to_v9_exactly_one_active_comment() {
    withFreshDbMigratedTo(
        "9",
        jt -> {
          assertThat(historyVersions(jt)).contains("9");
          // GENAU EINE aktive+gueltige COMMENT-Zeile im globalen Katalog.
          assertThat(activeCommentCount(jt)).isEqualTo(1);
          // Resolver-Beleg: die Zeile ist aktiv+gueltig (findByCode-Praedikat) auffindbar und
          // traegt die kanonischen Werte.
          final Map<String, Object> row = canonicalCommentRow(jt);
          assertThat(row.get("CODE")).isEqualTo("COMMENT");
          assertThat(row.get("BEZEICHNUNG")).isEqualTo("Neuer Kommentar");
          assertThat(row.get("ENTRY_TYPE")).isEqualTo("SYSTEM_PUBLIC");
          assertThat(row.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
          assertThat(((Number) row.get("TENANT_ID")).longValue()).isZero();
          // Fester Seed (frische DB ohne Vor-Zeile): ID=409, feste UUIDv4-EXTERNALID.
          assertThat(((Number) row.get("ID")).longValue()).isEqualTo(409L);
          assertThat(String.valueOf(row.get("EXTERNALID")))
              .isEqualToIgnoringCase("9c4e1f82-7a3d-4b6e-8f05-2d9a1c7e4b60");
        });
  }

  @Test
  @DisplayName(
      "i2b_heal_abweichende_schreibweise_und_stale_metadaten_wird_kanonisch_id_externalid_erhalten")
  void i2b_heal_variant_spelling_and_stale_metadata() {
    withFreshDbMigratedTo(
        "8",
        jt -> {
          // Vor V9 eine Zeile mit ABWEICHENDER Schreibweise 'comment' + stale Metadaten einfuegen:
          // DEACTIVE, abgelaufenes GUELTIG_BIS, veraltete BEZEICHNUNG, abweichender ENTRY_TYPE.
          // Feste ID/EXTERNALID, um den Erhalt (heal) zu beweisen. UNIQUE(TENANT_ID,CODE) laesst
          // bei
          // case-insensitiver Server-Kollation nur EINE COMMENT-Zeile zu.
          jt.update(
              "INSERT INTO [dbo].[DIC_NOTIFICATION_TYPE]"
                  + " (ID, ENTITY_STATUS, BEZEICHNUNG, ENTRY_TYPE, GUELTIG_VON, GUELTIG_BIS,"
                  + "  ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, CODE)"
                  + " VALUES (770, 'DEACTIVE', N'Alter Kommentar', 'SYSTEM_PRIVATE',"
                  + "  '2020-01-01', '2020-12-31', 'legacy', GETDATE(),"
                  + "  '44444409-0000-0000-0000-00000000aaaa', 3, 0, 'comment')");

          // V9 anwenden -> heal.
          migrateTo(jt, "9");
          assertThat(historyVersions(jt)).contains("9");

          // Genau EINE aktive COMMENT-Zeile, kanonisch; ID + EXTERNALID der Vor-Zeile UNVERAENDERT.
          assertThat(activeCommentCount(jt)).isEqualTo(1);
          final Map<String, Object> row = canonicalCommentRow(jt);
          assertThat(row.get("CODE")).isEqualTo("COMMENT");
          assertThat(row.get("BEZEICHNUNG")).isEqualTo("Neuer Kommentar");
          assertThat(row.get("ENTRY_TYPE")).isEqualTo("SYSTEM_PUBLIC");
          assertThat(row.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
          assertThat(((Number) row.get("ID")).longValue()).isEqualTo(770L); // ID erhalten (heal)
          assertThat(String.valueOf(row.get("EXTERNALID")))
              .isEqualToIgnoringCase("44444409-0000-0000-0000-00000000aaaa"); // EXTERNALID erhalten
          // Gueltig: GUELTIG_VON<=heute, GUELTIG_BIS=NULL (stale-Ablauf geheilt).
          assertThat(row.get("GUELTIG_BIS")).isNull();
          // Zustands-Idempotenz: ein bereits gueltiges GUELTIG_VON (2020-01-01 <= heute) wird vom
          // Heal BEWAHRT, NICHT auf GETDATE() gesetzt (sonst Datums-Drift bei Re-Run an spaeterem
          // Tag).
          assertThat(String.valueOf(row.get("GUELTIG_VON"))).isEqualTo("2020-01-01");

          // Der Resolver-Code (Reverse) ist EXAKT 'COMMENT' (nicht die Alt-Schreibweise 'comment').
          final String storedCode =
              jt.queryForObject(
                  "SELECT CODE FROM [dbo].[DIC_NOTIFICATION_TYPE]"
                      + " WHERE TENANT_ID = 0 AND ID = 770",
                  String.class);
          assertThat(storedCode).isEqualTo("COMMENT");

          // V9-Skript-Idempotenz: das ECHTE heal-or-insert aus V9 ein ZWEITES Mal ausfuehren
          // (nicht Flyway-Re-Migrate) -> weiterhin genau eine aktive kanonische Zeile,
          // unveraendert.
          runV9HealOrInsert(jt);
          assertThat(activeCommentCount(jt)).isEqualTo(1);
          final Map<String, Object> rowAgain = canonicalCommentRow(jt);
          assertThat(((Number) rowAgain.get("ID")).longValue()).isEqualTo(770L);
          assertThat(rowAgain.get("CODE")).isEqualTo("COMMENT");
          assertThat(rowAgain.get("BEZEICHNUNG")).isEqualTo("Neuer Kommentar");
          assertThat(rowAgain.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
          // Zustands-Idempotenz-Kern: der zweite Lauf aendert GUELTIG_VON NICHT (bewahrt
          // 2020-01-01).
          assertThat(rowAgain.get("GUELTIG_VON")).isEqualTo(row.get("GUELTIG_VON"));
        });
  }

  // ---- Helper (Intake #136) -----------------------------------------------------------------

  /**
   * Anzahl der aktiven+gueltigen COMMENT-Zeilen (identisches Praedikat wie
   * NotificationType.findByCode).
   */
  private static int activeCommentCount(final JdbcTemplate jt) {
    final Integer n =
        jt.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[DIC_NOTIFICATION_TYPE]"
                + " WHERE TENANT_ID = 0 AND ENTITY_STATUS = 'ACTIVE' AND CODE = 'COMMENT'"
                + " AND GUELTIG_VON <= CAST(GETDATE() AS DATE)"
                + " AND (GUELTIG_BIS IS NULL OR GUELTIG_BIS >= CAST(GETDATE() AS DATE))",
            Integer.class);
    return n == null ? 0 : n;
  }

  /** Die eine COMMENT-Zeile (Unique-Key TENANT_ID=0, CODE='COMMENT') mit relevanten Spalten. */
  private static Map<String, Object> canonicalCommentRow(final JdbcTemplate jt) {
    return jt.queryForMap(
        "SELECT ID, CODE, BEZEICHNUNG, ENTRY_TYPE, ENTITY_STATUS, TENANT_ID,"
            + " CONVERT(nvarchar(36), EXTERNALID) AS EXTERNALID,"
            + " CONVERT(varchar(10), GUELTIG_VON, 120) AS GUELTIG_VON, GUELTIG_BIS"
            + " FROM [dbo].[DIC_NOTIFICATION_TYPE] WHERE TENANT_ID = 0 AND CODE = 'COMMENT'");
  }

  private static final String V9_RESOURCE = "db/migration/V9__notification_type_comment.sql";

  /**
   * Fuehrt das TATSAECHLICHE heal-or-insert aus {@code V9__notification_type_comment.sql} separat
   * erneut aus (SQL-Idempotenz-Beweis). Die V9-Ressource wird geladen, Kommentarzeilen entfernt und
   * an {@code GO} in Batches zerlegt; genau der UPDATE- und der INSERT-Batch werden erneut
   * ausgefuehrt (nicht Flyway-Re-Migrate). Driftet V9 strukturell, schlaegt der Sentinel an.
   */
  private static void runV9HealOrInsert(final JdbcTemplate jt) {
    for (final String batch : extractV9Batches()) {
      jt.update(batch);
    }
  }

  /**
   * Laedt V9, zerlegt an {@code GO} und liefert den UPDATE- + INSERT-Batch (in Dateireihenfolge).
   */
  static List<String> extractV9Batches() {
    final String raw;
    try {
      raw =
          new String(
              FileCopyUtils.copyToByteArray(new ClassPathResource(V9_RESOURCE).getInputStream()),
              StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("V9-Ressource nicht lesbar: " + V9_RESOURCE, e);
    }
    final StringBuilder codeOnly = new StringBuilder();
    for (final String line : raw.split("\\R")) {
      final String trimmed = line.trim();
      if (!trimmed.startsWith("--")) {
        codeOnly.append(line).append('\n');
      }
    }
    final List<String> batches = new ArrayList<>();
    for (final String part : codeOnly.toString().split("(?im)^\\s*GO\\s*$")) {
      final String batch = part.trim();
      if (batch.toUpperCase().startsWith("UPDATE") || batch.toUpperCase().startsWith("INSERT")) {
        batches.add(batch);
      }
    }
    // Sentinel: genau EIN UPDATE (heal) + EIN INSERT (insert) in V9.
    assertThat(batches)
        .as("genau ein UPDATE-Batch (heal) und ein INSERT-Batch (insert) in " + V9_RESOURCE)
        .hasSize(2);
    assertThat(batches.get(0)).as("erster V9-Batch = heal-UPDATE").containsIgnoringCase("UPDATE");
    assertThat(batches.get(1))
        .as("zweiter V9-Batch = INSERT ... WHERE NOT EXISTS")
        .containsIgnoringCase("INSERT")
        .containsIgnoringCase("WHERE NOT EXISTS");
    return batches;
  }

  // ---- Helper (Intake #135) -----------------------------------------------------------------

  /** Legt eine frische Wegwerf-DB an, migriert bis {@code target} und fuehrt den Body aus. */
  private static void withFreshDbMigratedTo(
      final String target, final Consumer<JdbcTemplate> body) {
    final HikariDataSource localMaster = RefSchemaItDb.masterDataSource();
    final String localDb = RefSchemaItDb.createThrowawayDb(localMaster);
    try (HikariDataSource ds = RefSchemaItDb.throwawayDataSource(localDb, 2)) {
      final JdbcTemplate jt = new JdbcTemplate(ds);
      migrateTo(jt, target);
      body.accept(jt);
    } finally {
      try {
        RefSchemaItDb.dropThrowawayDb(localMaster, localDb);
      } finally {
        localMaster.close();
      }
    }
  }

  private static void migrateTo(final JdbcTemplate jt, final String target) {
    Flyway.configure()
        .dataSource(jt.getDataSource())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .target(target)
        .load()
        .migrate();
  }

  /** Erwartet, dass die V8-Migration fail-closed scheitert (Preflight-Verletzung). */
  private static void assertV8Fails(final JdbcTemplate jt, final String preflightHint) {
    assertThatThrownBy(() -> migrateTo(jt, "8"))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining(preflightHint);
    // V8 darf NICHT in der History landen (Migration fehlgeschlagen).
    assertThat(historyVersions(jt)).doesNotContain("8");
    // Fail-closed: COLOR-Spalte wurde nicht hinzugefuegt (Preflight vor jeder Aenderung).
    assertThat(columnExists(jt, "TASK_TAG", "COLOR")).isFalse();
  }

  private static final String V8_RESOURCE = "db/migration/V8__tag_color.sql";

  /**
   * Fuehrt das TATSAECHLICHE Backfill-UPDATE aus {@code V8__tag_color.sql} separat erneut aus
   * (Idempotenz-Beweis). Kein handgepflegtes Zweit-SQL mehr: die V8-Ressource wird geladen, an
   * {@code GO} in Batches zerlegt und genau der Backfill-UPDATE-Batch extrahiert und ausgefuehrt.
   * Damit kann der Test nicht stillschweigend von V8 driften (Codex-IMPL-MAJOR FIX 2).
   */
  private static void runBackfillStatement(final JdbcTemplate jt) {
    jt.update(extractV8BackfillBatch());
  }

  /** Laedt V8, zerlegt an {@code GO} und liefert exakt den einen Backfill-UPDATE-Batch zurueck. */
  static String extractV8BackfillBatch() {
    final String raw;
    try {
      raw =
          new String(
              FileCopyUtils.copyToByteArray(new ClassPathResource(V8_RESOURCE).getInputStream()),
              StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("V8-Ressource nicht lesbar: " + V8_RESOURCE, e);
    }
    // Kommentarzeilen entfernen (ein 'GO' in einem Kommentar darf NICHT als Batch-Trenner wirken),
    // dann an einer alleinstehenden GO-Zeile (MSSQL-Batch-Trenner) zerlegen.
    final StringBuilder codeOnly = new StringBuilder();
    for (final String line : raw.split("\\R")) {
      final String trimmed = line.trim();
      if (!trimmed.startsWith("--")) {
        codeOnly.append(line).append('\n');
      }
    }
    final List<String> backfillBatches = new ArrayList<>();
    for (final String part : codeOnly.toString().split("(?im)^\\s*GO\\s*$")) {
      final String batch = part.trim();
      // Der Backfill ist der einzige UPDATE-Batch in V8.
      if (batch.toUpperCase().startsWith("UPDATE")) {
        backfillBatches.add(batch);
      }
    }
    // Sentinel: genau EIN Backfill-UPDATE-Batch - sonst ist V8 strukturell anders als erwartet.
    assertThat(backfillBatches).as("genau ein Backfill-UPDATE-Batch in " + V8_RESOURCE).hasSize(1);
    final String backfill = backfillBatches.get(0);
    // Die FROM/JOIN/WHERE-Form, auf die sich der Idempotenz-Beweis stuetzt, muss vorhanden sein.
    assertThat(backfill)
        .as("echter V8-Backfill (JOIN DIC_TAG_SEVERITY, WHERE COLOR IS NULL)")
        .containsIgnoringCase("JOIN [dbo].[DIC_TAG_SEVERITY]")
        .containsIgnoringCase("WHERE t.[COLOR] IS NULL");
    return backfill;
  }

  private static void insertTag(
      final JdbcTemplate jt,
      final long id,
      final String name,
      final Long severityId,
      final String color) {
    jt.update(
        "INSERT INTO [dbo].[TASK_TAG]"
            + " (ID, ENTITY_STATUS, BEZEICHNUNG, ENTRY_TYPE, GUELTIG_VON, ANGELEGT_VON,"
            + "  ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, SEVERITY_ID"
            + (color != null ? ", COLOR" : "")
            + ") VALUES (?, 'ACTIVE', ?, 'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), 'it',"
            + " getdate(), NEWID(), 1, 0, ?"
            + (color != null ? ", ?" : "")
            + ")",
        color != null
            ? new Object[] {id, name, severityId, color}
            : new Object[] {id, name, severityId});
  }

  private static void insertSeverity(
      final JdbcTemplate jt,
      final long id,
      final String code,
      final String entityStatus,
      final java.time.LocalDate gueltigBis) {
    jt.update(
        "INSERT INTO [dbo].[DIC_TAG_SEVERITY]"
            + " (ID, ENTITY_STATUS, BEZEICHNUNG, ENTRY_TYPE, GUELTIG_VON, GUELTIG_BIS,"
            + "  ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, CODE)"
            + " VALUES (?, ?, ?, 'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), ?, 'it', getdate(),"
            + " NEWID(), 1, 0, ?)",
        id,
        entityStatus,
        code,
        gueltigBis,
        code);
  }

  private static String colorOf(final JdbcTemplate jt, final long id) {
    return jt.queryForObject("SELECT COLOR FROM [dbo].[TASK_TAG] WHERE ID = ?", String.class, id);
  }

  /**
   * Liest die Metadaten des FK {@code FK_TASK_TAG_Severity} aus den Systemkatalogen: Name, Ziel-
   * Tabelle+Spalte, delete/update-Referenzaktion, is_not_trusted. Dient dem Beweis, dass V8 den FK
   * identisch wiederherstellt (Codex-IMPL-MAJOR FIX 3). Leere Map, wenn der FK nicht existiert.
   */
  private static Map<String, Object> foreignKeyMeta(final JdbcTemplate jt) {
    final List<Map<String, Object>> rows =
        jt.queryForList(
            "SELECT fk.name AS fk_name,"
                + "       OBJECT_NAME(fk.referenced_object_id) AS ref_table,"
                + "       COL_NAME(fkc.referenced_object_id, fkc.referenced_column_id) AS ref_col,"
                + "       COL_NAME(fkc.parent_object_id, fkc.parent_column_id) AS parent_col,"
                + "       fk.delete_referential_action AS del_action,"
                + "       fk.update_referential_action AS upd_action,"
                + "       CAST(fk.is_not_trusted AS int) AS is_not_trusted"
                + "  FROM sys.foreign_keys fk"
                + "  JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id"
                + " WHERE fk.name = 'FK_TASK_TAG_Severity'"
                + "   AND fk.parent_object_id = OBJECT_ID('dbo.TASK_TAG')");
    assertThat(rows).as("genau eine FK-Spalte fuer FK_TASK_TAG_Severity").hasSize(1);
    return rows.get(0);
  }

  private static boolean columnExists(
      final JdbcTemplate jt, final String table, final String column) {
    final Integer count =
        jt.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
            Integer.class,
            table,
            column);
    return count != null && count > 0;
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
