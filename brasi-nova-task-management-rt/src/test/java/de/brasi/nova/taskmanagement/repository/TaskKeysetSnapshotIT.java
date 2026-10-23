package de.brasi.nova.taskmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.Task;
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
 * Diskriminator-IT fuer P1: {@link TaskKeysetQueryRepository}-Membership-Praedikate muessen
 * auf {@code asOf} gebunden sein (arch-v2 Snapshot-Watermark-Invariante).
 *
 * <p>Beweist (mutation-after-asOf-Beweis):
 * <ul>
 *   <li>IT 1: findActivePage liefert Task, wenn Benutzer NACH asOf abgemeldet wurde
 *       (bei asOf war er noch aktiv, war auch VOR asOf beigetreten).
 *   <li>IT 2: findArchiveLeftPage liefert Task NICHT, wenn Abmeldung NACH asOf erfolgte
 *       (bei asOf war er noch NICHT abgemeldet).
 *   <li>IT 3: findArchiveLeftPage liefert Task NICHT, wenn Participant-Austritt (EXIT_AT) NACH asOf
 *       (bei asOf hatte der Teilnehmer die Aufgabe noch nicht verlassen).
 *   <li>IT 4 (ANGELEGT_AM-Bound UserState): findActivePage liefert Task NICHT, wenn Benutzer
 *       NACH asOf beigetreten ist (Mitgliedschaft existierte beim Wasserzeichen noch nicht).
 *   <li>IT 5 (EXIT_AT-Null-Fallback): findArchiveLeftPage liefert Task MIT null EXIT_AT
 *       (EXIT_KIND gesetzt + ANGELEGT_AM vor asOf -> als verlassen gewertet, kein Verschlucken).
 *   <li>IT 6 (ANGELEGT_AM-Bound Participant/null EXIT_AT): findArchiveLeftPage liefert Task NICHT
 *       wenn Participant NACH asOf erstellt wurde mit EXIT_KIND und EXIT_AT=null (kein Beitritt
 *       vor Wasserzeichen -> soll nicht im Archiv erscheinen).
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskKeysetSnapshotIT.ItConfig.class,
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
class TaskKeysetSnapshotIT {

  private static final long TENANT_ID = 590L;
  private static final UUID TENANT_UUID =
      UUID.fromString("00000059-0000-0059-0000-000000000590");
  private static final UUID USER_A_REF =
      UUID.fromString("00000059-0001-0059-0001-000000000590");

  // feste Task-IDs (ausserhalb KSF-Sequenz >= 1 000 000 000; Namensraumtrennung von DIC-Seeds)
  private static final long TASK_ACTIVE_ID = 590001L;
  private static final long TASK_LEFT_UNSUB_ID = 590002L;
  private static final long TASK_LEFT_EXIT_ID = 590003L;
  private static final long TASK_ADDED_AFTER_ID = 590004L;
  private static final long TASK_LEFT_EXIT_NULL_AT_ID = 590005L;
  private static final long TASK_PARTICIPANT_AFTER_ASOF_ID = 590006L;
  // Finding #2 (Codex Re-Gate 93781f4): UserState NACH asOf beigetreten (ANGELEGT_AM) aber
  // UNSUBSCRIBED_AT VOR asOf (Backfill/Clock-Skew) -> Membership existierte beim Wasserzeichen
  // noch nicht, darf NICHT als "verlassen" archiviert/gezaehlt werden.
  private static final long TASK_UNSUB_JOIN_AFTER_ID = 590007L;
  private static final UUID TASK_ACTIVE_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590001");
  private static final UUID TASK_LEFT_UNSUB_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590002");
  private static final UUID TASK_LEFT_EXIT_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590003");
  private static final UUID TASK_ADDED_AFTER_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590004");
  private static final UUID TASK_LEFT_EXIT_NULL_AT_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590005");
  private static final UUID TASK_PARTICIPANT_AFTER_ASOF_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590006");
  private static final UUID TASK_UNSUB_JOIN_AFTER_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000590007");

  // Bekannte Seed-IDs aus V1-Migration
  private static final long STATUS_OPEN = 101L;
  private static final long STATUS_DONE = 102L;
  private static final long STATUS_CANCELLED = 103L;
  private static final long PRIORITY_LOW = 201L;
  private static final long DIRECTION_BRASI_CUSTOMER = 301L;

  /**
   * Zeitachse:
   * T_BEFORE (08:00) -> T0 = asOf (10:00) -> T_AFTER (12:00)
   * Mitglieder/Participant-Eintraege mit ANGELEGT_AM = T_BEFORE: existierten vor dem Wasserzeichen.
   * Mitglieder/Participant-Eintraege mit ANGELEGT_AM = T_AFTER: existierten noch nicht beim Wasserzeichen.
   */
  private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
  private static final LocalDateTime T_BEFORE = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
  private static final LocalDateTime T_AFTER = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

  /** Keyset-Cursor fuer erste Seite (groesser als T0, alle Tasks bis asOf enthalten). */
  private static final LocalDateTime CURSOR_LAST_ACTIVITY =
      LocalDateTime.of(9999, 12, 31, 0, 0, 0);
  private static final UUID CURSOR_EXT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

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

    // T1 (IT 1): OPEN-Task, Benutzer bei T_BEFORE beigetreten, bei T_AFTER abgemeldet.
    //            Bei asOf=T0 war er aktiv -> muss in findActivePage erscheinen.
    //            Slice 6 (Membership-Konjunkt): findActivePage verlangt zusaetzlich einen aktiven
    //            PERSON-Participant (WRITE-Member) -> State + Participant koexistieren (ANGELEGT_AM
    //            = T_BEFORE, EXIT_KIND NULL).
    insertTask(jt, TASK_ACTIVE_ID, TASK_ACTIVE_UUID, TENANT_ID, STATUS_OPEN, T0, "IT-P1-Aktiv");
    insertUserState(jt, 590010L, TASK_ACTIVE_ID, TENANT_ID, USER_A_REF, T_AFTER, T_BEFORE);
    insertActiveParticipant(jt, 590011L, TASK_ACTIVE_ID, TENANT_ID, USER_A_REF, T_BEFORE);

    // T2 (IT 2): DONE-Task, Benutzer bei T_BEFORE beigetreten, bei T_AFTER abgemeldet.
    //            UNSUBSCRIBED_AT > asOf -> war bei asOf noch NICHT abgemeldet
    //            -> darf NICHT in findArchiveLeftPage erscheinen.
    //            Slice 6: Archiv-Queries verlangen READ-Member -> aktiver PERSON-Participant
    //            (ANGELEGT_AM = T_BEFORE), damit die DONE-Task in archive-all bleibt.
    insertTask(
        jt, TASK_LEFT_UNSUB_ID, TASK_LEFT_UNSUB_UUID, TENANT_ID, STATUS_DONE, T0,
        "IT-P1-LeftUnsub");
    insertUserState(jt, 590020L, TASK_LEFT_UNSUB_ID, TENANT_ID, USER_A_REF, T_AFTER, T_BEFORE);
    insertActiveParticipant(jt, 590021L, TASK_LEFT_UNSUB_ID, TENANT_ID, USER_A_REF, T_BEFORE);

    // T3 (IT 3): OPEN-Task, Participant bei T_BEFORE hinzugefuegt, EXIT_AT = T_AFTER.
    //            EXIT_AT > asOf -> war bei asOf noch NICHT ausgetreten
    //            -> darf NICHT in findArchiveLeftPage erscheinen.
    insertTask(
        jt, TASK_LEFT_EXIT_ID, TASK_LEFT_EXIT_UUID, TENANT_ID, STATUS_OPEN, T0,
        "IT-P1-LeftExit");
    insertParticipant(jt, 590030L, TASK_LEFT_EXIT_ID, TENANT_ID, USER_A_REF, T_AFTER, T_BEFORE);

    // T4 (IT 4 - ANGELEGT_AM-Bound UserState): OPEN-Task, Benutzer erst bei T_AFTER beigetreten.
    //            ANGELEGT_AM > asOf -> Mitgliedschaft existierte beim Wasserzeichen noch nicht
    //            -> darf NICHT in findActivePage erscheinen.
    insertTask(
        jt, TASK_ADDED_AFTER_ID, TASK_ADDED_AFTER_UUID, TENANT_ID, STATUS_OPEN, T0,
        "IT-P1-AddedAfter");
    insertUserState(jt, 590040L, TASK_ADDED_AFTER_ID, TENANT_ID, USER_A_REF, null, T_AFTER);

    // T5 (IT 5 - EXIT_AT-Null-Fallback positiv): Participant mit EXIT_KIND + EXIT_AT=null,
    //            aber ANGELEGT_AM = T_BEFORE (VOR asOf beigetreten).
    //            -> MUSS in findArchiveLeftPage erscheinen (Null-Fallback greift korrekt).
    insertTask(
        jt, TASK_LEFT_EXIT_NULL_AT_ID, TASK_LEFT_EXIT_NULL_AT_UUID, TENANT_ID, STATUS_OPEN, T0,
        "IT-P1-LeftExitNullAt");
    insertParticipantNullExit(
        jt, 590050L, TASK_LEFT_EXIT_NULL_AT_ID, TENANT_ID, USER_A_REF, T_BEFORE);

    // T6 (IT 6 - ANGELEGT_AM-Bound Participant/null EXIT_AT): Participant mit EXIT_KIND + EXIT_AT=null,
    //            aber ANGELEGT_AM = T_AFTER (NACH asOf erstellt).
    //            -> darf NICHT in findArchiveLeftPage erscheinen (Beitritt nach Wasserzeichen).
    insertTask(
        jt, TASK_PARTICIPANT_AFTER_ASOF_ID, TASK_PARTICIPANT_AFTER_ASOF_UUID, TENANT_ID,
        STATUS_OPEN, T0, "IT-P1-ParticipantAfterAsOf");
    insertParticipantNullExit(
        jt, 590060L, TASK_PARTICIPANT_AFTER_ASOF_ID, TENANT_ID, USER_A_REF, T_AFTER);

    // T7 (IT 7-9 - Finding #2): OPEN-Task, UserState UNSUBSCRIBED_AT = T_BEFORE (vor asOf) aber
    //            ANGELEGT_AM = T_AFTER (nach asOf). Anomalie durch Backfill/Clock-Skew:
    //            Mitgliedschaft existierte beim Wasserzeichen T0 noch nicht -> darf NICHT als
    //            "verlassen" in archive-left/archive-all erscheinen oder gezaehlt werden.
    insertTask(
        jt, TASK_UNSUB_JOIN_AFTER_ID, TASK_UNSUB_JOIN_AFTER_UUID, TENANT_ID, STATUS_OPEN, T0,
        "IT-P1-UnsubJoinAfter");
    insertUserState(jt, 590070L, TASK_UNSUB_JOIN_AFTER_ID, TENANT_ID, USER_A_REF, T_BEFORE, T_AFTER);
  }

  @BeforeEach
  void setTenantContext() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  // ---- IT 1: findActivePage – Benutzer war aktiv zum asOf-Wasserzeichen ----------------

  /**
   * Diskriminator: UNSUBSCRIBED_AT > asOf UND ANGELEGT_AM < asOf -> bei asOf aktiv ->
   * Task MUSS in der aktiven Inbox erscheinen.
   */
  @Test
  @DisplayName(
      "findActivePage_liefert_task_bei_unsubscribed_at_nach_asof: "
          + "ANGELEGT_AM < asOf + UNSUBSCRIBED_AT > asOf -> bei asOf aktiv -> sichtbar")
  void findActivePage_liefert_task_bei_unsubscribed_at_nach_asof(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findActivePage(USER_A_REF, STATUS_OPEN, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 10);

    assertThat(page)
        .as("T1 muss erscheinen: ANGELEGT_AM=%s < asOf=%s, UNSUBSCRIBED_AT=%s > asOf",
            T_BEFORE, T0, T_AFTER)
        .extracting(Task::getId)
        .contains(TASK_ACTIVE_ID);
  }

  // ---- IT 2: findArchiveLeftPage – Abmeldung NACH asOf -> Task darf NICHT erscheinen ----

  /**
   * Diskriminator: UNSUBSCRIBED_AT > asOf -> bei asOf noch NICHT abgemeldet ->
   * Task darf NICHT in archive-left erscheinen.
   */
  @Test
  @DisplayName(
      "findArchiveLeftPage_enthaelt_task_nicht_bei_unsubscribed_at_nach_asof: "
          + "UNSUBSCRIBED_AT > asOf -> bei asOf noch NICHT abgemeldet -> NICHT in archive-left")
  void findArchiveLeftPage_enthaelt_task_nicht_bei_unsubscribed_at_nach_asof(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveLeftPage(USER_A_REF, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 10);

    assertThat(page)
        .as("T2 darf NICHT erscheinen: UNSUBSCRIBED_AT=%s > asOf=%s", T_AFTER, T0)
        .extracting(Task::getId)
        .doesNotContain(TASK_LEFT_UNSUB_ID);
  }

  // ---- IT 3: findArchiveLeftPage – Participant-Austritt NACH asOf -> nicht in archive-left --

  /**
   * Diskriminator: EXIT_AT > asOf -> Teilnehmer war bei asOf noch aktiv ->
   * Task darf NICHT in archive-left erscheinen.
   */
  @Test
  @DisplayName(
      "findArchiveLeftPage_enthaelt_task_nicht_bei_exit_at_nach_asof: "
          + "EXIT_AT > asOf -> Teilnehmer noch aktiv bei asOf -> NICHT in archive-left")
  void findArchiveLeftPage_enthaelt_task_nicht_bei_exit_at_nach_asof(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveLeftPage(USER_A_REF, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 10);

    assertThat(page)
        .as("T3 darf NICHT erscheinen: EXIT_AT=%s > asOf=%s", T_AFTER, T0)
        .extracting(Task::getId)
        .doesNotContain(TASK_LEFT_EXIT_ID);
  }

  // ---- IT 4 (ANGELEGT_AM-Bound UserState): nach asOf beigetretener Benutzer ----

  /**
   * Diskriminator: ANGELEGT_AM > asOf -> Mitgliedschaft existierte beim Wasserzeichen noch nicht ->
   * Task darf NICHT in findActivePage erscheinen.
   */
  @Test
  @DisplayName(
      "findActivePage_enthaelt_task_nicht_bei_angelegt_am_nach_asof: "
          + "ANGELEGT_AM > asOf -> Mitgliedschaft existierte noch nicht -> NICHT in active-page")
  void findActivePage_enthaelt_task_nicht_bei_angelegt_am_nach_asof(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findActivePage(USER_A_REF, STATUS_OPEN, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 10);

    assertThat(page)
        .as("T4 darf NICHT erscheinen: ANGELEGT_AM=%s > asOf=%s (Beitritt nach Wasserzeichen)",
            T_AFTER, T0)
        .extracting(Task::getId)
        .doesNotContain(TASK_ADDED_AFTER_ID);
  }

  // ---- IT 5 (EXIT_AT-Null-Fallback positiv): VOR asOf erstellter Participant ----

  /**
   * Diskriminator: EXIT_KIND gesetzt + EXIT_AT = NULL + ANGELEGT_AM < asOf ->
   * Participant existierte beim Wasserzeichen und ist als verlassen gewertet ->
   * Task MUSS in archive-left erscheinen (Null-Fallback greift korrekt, nicht verschluckt).
   */
  @Test
  @DisplayName(
      "findArchiveLeftPage_enthaelt_task_mit_null_exit_at_und_angelegt_vor_asof: "
          + "EXIT_KIND gesetzt + EXIT_AT=null + ANGELEGT_AM < asOf -> erscheint in archive-left")
  void findArchiveLeftPage_enthaelt_task_mit_null_exit_at_und_angelegt_vor_asof(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveLeftPage(USER_A_REF, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 10);

    assertThat(page)
        .as("T5 muss erscheinen: EXIT_KIND=HANDOVER, EXIT_AT=null, ANGELEGT_AM=%s < asOf=%s",
            T_BEFORE, T0)
        .extracting(Task::getId)
        .contains(TASK_LEFT_EXIT_NULL_AT_ID);
  }

  // ---- IT 6 (ANGELEGT_AM-Bound Participant/null EXIT_AT): NACH asOf erstellter Participant ----

  /**
   * Diskriminator: EXIT_KIND gesetzt + EXIT_AT = NULL + ANGELEGT_AM > asOf ->
   * Participant-Beitritt war nach dem Wasserzeichen -> Membership existierte noch nicht ->
   * Task darf NICHT in archive-left erscheinen.
   */
  @Test
  @DisplayName(
      "findArchiveLeftPage_enthaelt_task_nicht_bei_participant_angelegt_nach_asof: "
          + "EXIT_KIND + EXIT_AT=null + ANGELEGT_AM > asOf -> Beitritt nach Wasserzeichen -> NICHT in archive-left")
  void findArchiveLeftPage_enthaelt_task_nicht_bei_participant_angelegt_nach_asof(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveLeftPage(USER_A_REF, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 10);

    assertThat(page)
        .as("T6 darf NICHT erscheinen: EXIT_KIND=HANDOVER, EXIT_AT=null, ANGELEGT_AM=%s > asOf=%s",
            T_AFTER, T0)
        .extracting(Task::getId)
        .doesNotContain(TASK_PARTICIPANT_AFTER_ASOF_ID);
  }

  // ---- IT 7 (Finding #2 - ANGELEGT_AM-Bound unsubscribte UserState): archive-left Seite ----

  /**
   * Diskriminator (Finding #2): UNSUBSCRIBED_AT &lt;= asOf aber ANGELEGT_AM &gt; asOf
   * (Backfill/Clock-Skew) -&gt; Mitgliedschaft existierte beim Wasserzeichen noch nicht -&gt;
   * Task darf NICHT in findArchiveLeftPage erscheinen.
   */
  @Test
  @DisplayName(
      "findArchiveLeftPage_schliesst_nach_asof_beigetretenen_unsubscribed_aus: "
          + "UNSUBSCRIBED_AT <= asOf aber ANGELEGT_AM > asOf -> Beitritt nach Wasserzeichen -> NICHT in archive-left")
  void findArchiveLeftPage_schliesst_nach_asof_beigetretenen_unsubscribed_aus(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveLeftPage(USER_A_REF, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 50);

    assertThat(page)
        .as("T7 darf NICHT erscheinen: ANGELEGT_AM=%s > asOf=%s trotz UNSUBSCRIBED_AT=%s <= asOf",
            T_AFTER, T0, T_BEFORE)
        .extracting(Task::getId)
        .doesNotContain(TASK_UNSUB_JOIN_AFTER_ID);
  }

  // ---- IT 8 (Finding #2 - countArchiveLeft) ----

  /**
   * Zaehler-Deckungsgleichheit (Finding #2): countArchiveLeft darf den nach asOf beigetretenen,
   * vor asOf abgemeldeten UserState nicht mitzaehlen. Zum Wasserzeichen T0 ist genau T5
   * (Participant null-exit, ANGELEGT_AM vor asOf) verlassen -&gt; erwartete Zahl = 1.
   */
  @Test
  @DisplayName(
      "countArchiveLeft_zaehlt_nach_asof_beigetretenen_unsubscribed_nicht: erwartet genau 1 (nur T5)")
  void countArchiveLeft_zaehlt_nach_asof_beigetretenen_unsubscribed_nicht(
      @Autowired TaskKeysetQueryRepository repo) {
    final long count = repo.countArchiveLeft(USER_A_REF, T0);

    assertThat(count)
        .as("archive-left zum Wasserzeichen T0 = {T5}; T7 (ANGELEGT_AM>asOf) darf nicht zaehlen")
        .isEqualTo(1L);
  }

  // ---- IT 9 (Finding #2 - archive-all Seite + Zaehler, s2-Zweig) ----

  /**
   * Diskriminator + Zaehler (Finding #2, s2-Zweig): findArchiveAllPage/countArchiveAll duerfen
   * den nach asOf beigetretenen, vor asOf abgemeldeten UserState nicht enthalten/zaehlen. Zum
   * Wasserzeichen T0 ist archive-all = {T2 (DONE, bei asOf aktiv), T5 (verlassen)} -&gt; erwartet 2.
   */
  @Test
  @DisplayName(
      "findArchiveAllPage_und_count_schliessen_nach_asof_beigetretenen_unsubscribed_aus: erwartet genau 2 (T2+T5)")
  void findArchiveAllPage_und_count_schliessen_nach_asof_beigetretenen_unsubscribed_aus(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveAllPage(
            USER_A_REF, STATUS_DONE, STATUS_CANCELLED, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 50);
    final long count = repo.countArchiveAll(USER_A_REF, STATUS_DONE, STATUS_CANCELLED, T0);

    assertThat(page)
        .as("T7 darf NICHT in archive-all erscheinen (s2-Zweig, ANGELEGT_AM>asOf)")
        .extracting(Task::getId)
        .doesNotContain(TASK_UNSUB_JOIN_AFTER_ID);
    assertThat(count)
        .as("archive-all zum Wasserzeichen T0 = {T2, T5}; T7 darf nicht zaehlen")
        .isEqualTo(2L);
  }

  // ---- Hilfsmethoden -----------------------------------------------------------------------

  private static void seedFirmaIfAbsent(
      final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static void insertTask(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final long tenantId,
      final long statusId,
      final LocalDateTime lastActivityAt,
      final String title) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE, TASK_STATUS_ID, "
            + " PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT, COMMENT_COUNT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?, ?, 0, 'it-seed', GETDATE())",
        id, id, externalId, tenantId, title, statusId,
        PRIORITY_LOW, DIRECTION_BRASI_CUSTOMER, tenantId, lastActivityAt);
  }

  /**
   * Fuegt einen TASK_USER_STATE-Eintrag mit explizitem ANGELEGT_AM ein (Beitrittszeitpunkt).
   * {@code angelegtAm} vor asOf: Mitgliedschaft existierte beim Wasserzeichen.
   * {@code angelegtAm} nach asOf: Mitgliedschaft existierte beim Wasserzeichen noch nicht.
   *
   * @param unsubscribedAt  null = noch aktiv; Wert = Abmeldezeitpunkt
   * @param angelegtAm      Beitrittszeitpunkt (ANGELEGT_AM, nie null)
   */
  private static void insertUserState(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID userRef,
      final LocalDateTime unsubscribedAt,
      final LocalDateTime angelegtAm) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, USER_REF, "
            + " USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, 0, 0, 0, ?, 'it-seed', ?)",
        id, id, tenantId, taskId, userRef, tenantId, unsubscribedAt, angelegtAm);
  }

  /**
   * Aktiver PERSON-Participant (EXIT_KIND NULL) = WRITE-/READ-Member. Slice 6 verlangt diese Zeile
   * als Membership-Beleg koexistierend zur TASK_USER_STATE-Zeile.
   *
   * @param angelegtAm Beitrittszeitpunkt des Participants (ANGELEGT_AM)
   */
  private static void insertActiveParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID participantRef,
      final LocalDateTime angelegtAm) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 1, 1, NULL, NULL, "
            + " 'it-seed', ?)",
        id, id, tenantId, taskId, participantRef, tenantId, angelegtAm);
  }

  /**
   * Participant mit gesetztem EXIT_KIND und EXIT_AT.
   *
   * @param angelegtAm  Beitrittszeitpunkt des Participants (ANGELEGT_AM)
   */
  private static void insertParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID participantRef,
      final LocalDateTime exitAt,
      final LocalDateTime angelegtAm) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 0, 1, 'HANDOVER', ?, "
            + " 'it-seed', ?)",
        id, id, tenantId, taskId, participantRef, tenantId, exitAt, angelegtAm);
  }

  /**
   * Participant mit gesetztem EXIT_KIND aber NULL EXIT_AT (Null-Fallback-Test).
   *
   * @param angelegtAm  Beitrittszeitpunkt des Participants (ANGELEGT_AM);
   *                    vor asOf -> erscheint; nach asOf -> erscheint nicht
   */
  private static void insertParticipantNullExit(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID participantRef,
      final LocalDateTime angelegtAm) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 0, 1, 'HANDOVER', NULL, "
            + " 'it-seed', ?)",
        id, id, tenantId, taskId, participantRef, tenantId, angelegtAm);
  }

  // ---- IT-Konfigurations-Context --------------------------------------------------------

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
      return () -> "keyset-snapshot-it";
    }
  }
}
