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
 * Slice-6-IT (Auflage 5, kritisch): der state-only Listen/Counts-Leak muss ueber ALLE
 * requester-scoped Sichten + Counts geschlossen sein. Jede Treffer-/Zaehl-Zeile setzt jetzt
 * Membership voraus (REQUIRED {@code EXISTS TASK_PARTICIPANT}-Konjunkt): die aktive Seite verlangt
 * WRITE-Member (PERSON, ACTIVE, {@code EXIT_KIND IS NULL}, beide Tenant-Spalten), die sechs
 * Archiv-Queries verlangen READ-Member (wie WRITE, aber {@code EXIT_KIND} egal -> Ex-Member liest
 * sein Archiv).
 *
 * <p><b>Orphan-State-Gegenproben (ASYMMETRISCH):</b> Eine {@code TASK_USER_STATE}-Zeile OHNE
 * passende {@code TASK_PARTICIPANT}-Row (Orphan-State) wuerde unter dem alten, reinen State-SQL
 * faelschlich erscheinen/gezaehlt. Der neue Membership-Konjunkt schliesst sie aus. Die
 * Gegenproben (echte READ-/WRITE-Member) belegen, dass legitime Mitglieder NICHT faelschlich
 * verschwinden (kein False-Negative).
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskKeysetMembershipLeakIT.ItConfig.class,
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
class TaskKeysetMembershipLeakIT {

  private static final long TENANT_ID = 591L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000591");
  // Mandant eines fremden Teilnehmers (fuer die "fremde Exit-Zeile"-Gegenprobe).
  private static final long FOREIGN_TENANT_ID = 592L;
  private static final UUID FOREIGN_TENANT_UUID =
      UUID.fromString("00000059-0000-0059-0000-000000000592");

  private static final UUID USER_B_REF =
      UUID.fromString("00000059-0002-0059-0002-000000000591");

  // feste Task-IDs (ausserhalb KSF-Sequenz; Namensraum 5910xx fuer diesen IT)
  private static final long TASK_ORPHAN_ACTIVE_ID = 591001L;
  private static final long TASK_ORPHAN_DONE_ID = 591002L;
  private static final long TASK_ORPHAN_LEFT_ID = 591003L;
  private static final long TASK_MEMBER_ACTIVE_ID = 591004L;
  private static final long TASK_EXMEMBER_LEFT_ID = 591005L;
  private static final long TASK_MEMBER_DONE_ID = 591006L;
  private static final long TASK_FOREIGN_EXIT_ONLY_ID = 591007L;

  private static final UUID TASK_ORPHAN_ACTIVE_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000591001");
  private static final UUID TASK_ORPHAN_DONE_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000591002");
  private static final UUID TASK_ORPHAN_LEFT_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000591003");
  private static final UUID TASK_MEMBER_ACTIVE_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000591004");
  private static final UUID TASK_EXMEMBER_LEFT_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000591005");
  private static final UUID TASK_MEMBER_DONE_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000591006");
  private static final UUID TASK_FOREIGN_EXIT_ONLY_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000591007");

  // Bekannte Seed-IDs aus V1-Migration
  private static final long STATUS_OPEN = 101L;
  private static final long STATUS_DONE = 102L;
  private static final long STATUS_CANCELLED = 103L;
  private static final long PRIORITY_LOW = 201L;
  private static final long DIRECTION_BRASI_CUSTOMER = 301L;

  // Zeitachse: alle Seeds liegen VOR asOf=T0 (Membership galt beim Wasserzeichen).
  private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
  private static final LocalDateTime T_BEFORE = LocalDateTime.of(2026, 1, 1, 8, 0, 0);

  /** Keyset-Cursor fuer erste Seite (groesser als alle Tasks bis asOf). */
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
    seedFirmaIfAbsent(jt, FOREIGN_TENANT_ID, FOREIGN_TENANT_UUID);

    // (1) Orphan-State aktiv: OPEN, aktiver State (UNSUBSCRIBED_AT NULL), KEIN Participant.
    //     -> darf NICHT in findActivePage erscheinen (WRITE-Member fehlt).
    insertTask(
        jt, TASK_ORPHAN_ACTIVE_ID, TASK_ORPHAN_ACTIVE_UUID, TENANT_ID, STATUS_OPEN, T0,
        "Slice6-OrphanActive");
    insertUserState(jt, 591010L, TASK_ORPHAN_ACTIVE_ID, TENANT_ID, USER_B_REF, null, T_BEFORE);

    // (2) Orphan-State DONE: DONE, aktiver State, KEIN Participant.
    //     -> darf NICHT in done/all/countArchiveDone/countArchiveAll erscheinen.
    insertTask(
        jt, TASK_ORPHAN_DONE_ID, TASK_ORPHAN_DONE_UUID, TENANT_ID, STATUS_DONE, T0,
        "Slice6-OrphanDone");
    insertUserState(jt, 591020L, TASK_ORPHAN_DONE_ID, TENANT_ID, USER_B_REF, null, T_BEFORE);

    // (3) Orphan-State abgemeldet: OPEN, State mit UNSUBSCRIBED_AT, KEIN Participant.
    //     -> darf NICHT in left/all/countArchiveLeft/countArchiveAll erscheinen.
    insertTask(
        jt, TASK_ORPHAN_LEFT_ID, TASK_ORPHAN_LEFT_UUID, TENANT_ID, STATUS_OPEN, T0,
        "Slice6-OrphanLeft");
    insertUserState(jt, 591030L, TASK_ORPHAN_LEFT_ID, TENANT_ID, USER_B_REF, T_BEFORE, T_BEFORE);

    // (4) Gegenprobe aktiver WRITE-Member: OPEN, aktiver State + aktiver PERSON-Participant
    //     (EXIT_KIND NULL). -> MUSS in findActivePage erscheinen.
    insertTask(
        jt, TASK_MEMBER_ACTIVE_ID, TASK_MEMBER_ACTIVE_UUID, TENANT_ID, STATUS_OPEN, T0,
        "Slice6-MemberActive");
    insertUserState(jt, 591040L, TASK_MEMBER_ACTIVE_ID, TENANT_ID, USER_B_REF, null, T_BEFORE);
    insertActiveParticipant(
        jt, 591041L, TASK_MEMBER_ACTIVE_ID, TENANT_ID, USER_B_REF, TENANT_ID, T_BEFORE);

    // (5) Gegenprobe ausgetretener READ-Member: OPEN, State abgemeldet + Participant mit EXIT_KIND.
    //     -> MUSS in left + all + countArchiveLeft/countArchiveAll; NICHT in done.
    insertTask(
        jt, TASK_EXMEMBER_LEFT_ID, TASK_EXMEMBER_LEFT_UUID, TENANT_ID, STATUS_OPEN, T0,
        "Slice6-ExMemberLeft");
    insertUserState(jt, 591050L, TASK_EXMEMBER_LEFT_ID, TENANT_ID, USER_B_REF, T_BEFORE, T_BEFORE);
    insertExitedParticipant(
        jt, 591051L, TASK_EXMEMBER_LEFT_ID, TENANT_ID, USER_B_REF, TENANT_ID, T_BEFORE, T_BEFORE);

    // (6) Gegenprobe aktiver READ-Member auf DONE: DONE, aktiver State + aktiver PERSON-Participant.
    //     -> MUSS in done + all + countArchiveDone/countArchiveAll.
    insertTask(
        jt, TASK_MEMBER_DONE_ID, TASK_MEMBER_DONE_UUID, TENANT_ID, STATUS_DONE, T0,
        "Slice6-MemberDone");
    insertUserState(jt, 591060L, TASK_MEMBER_DONE_ID, TENANT_ID, USER_B_REF, null, T_BEFORE);
    insertActiveParticipant(
        jt, 591061L, TASK_MEMBER_DONE_ID, TENANT_ID, USER_B_REF, TENANT_ID, T_BEFORE);

    // (7) Fremde Exit-Zeile, Task-Status OPEN: KEINE task-tenant-Membership, nur eine
    //     fremd-PARTICIPANT_TENANT_ID-Zeile mit EXIT_KIND, KEIN abgemeldeter State.
    //     -> darf NICHT in left/all erscheinen (READ-Member im task-tenant fehlt);
    //        countArchiveLeft/countArchiveAll zaehlen sie nicht.
    insertTask(
        jt, TASK_FOREIGN_EXIT_ONLY_ID, TASK_FOREIGN_EXIT_ONLY_UUID, TENANT_ID, STATUS_OPEN, T0,
        "Slice6-ForeignExitOnly");
    insertExitedParticipant(
        jt, 591071L, TASK_FOREIGN_EXIT_ONLY_ID, TENANT_ID, USER_B_REF, FOREIGN_TENANT_ID, T_BEFORE,
        T_BEFORE);
  }

  @BeforeEach
  void setTenantContext() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  // ---- Aktive Inbox (items/hasMore, KEIN aktiver Count) -----------------------------------

  @Test
  @DisplayName(
      "findActivePage_schliesst_orphan_state_aus: aktiver State ohne Participant -> nicht sichtbar,"
          + " aktiver WRITE-Member sichtbar")
  void findActivePage_schliesst_orphan_state_aus(@Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findActivePage(USER_B_REF, STATUS_OPEN, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 50);

    assertThat(page)
        .as("Orphan-State (kein Participant) darf NICHT in der aktiven Inbox erscheinen")
        .extracting(Task::getId)
        .doesNotContain(TASK_ORPHAN_ACTIVE_ID);
    assertThat(page)
        .as("aktiver WRITE-Member MUSS in der aktiven Inbox erscheinen (kein False-Negative)")
        .extracting(Task::getId)
        .contains(TASK_MEMBER_ACTIVE_ID);
  }

  // ---- Archiv: done + countArchiveDone ----------------------------------------------------

  @Test
  @DisplayName(
      "findArchiveDonePage_und_count_schliessen_orphan_done_aus: erwartet genau {MemberDone}")
  void findArchiveDonePage_und_count_schliessen_orphan_done_aus(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveDonePage(
            USER_B_REF, STATUS_DONE, STATUS_CANCELLED, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 50);
    final long count = repo.countArchiveDone(USER_B_REF, STATUS_DONE, STATUS_CANCELLED, T0);

    assertThat(page)
        .as("Orphan-State DONE (kein Participant) darf NICHT in done erscheinen")
        .extracting(Task::getId)
        .doesNotContain(TASK_ORPHAN_DONE_ID);
    assertThat(page)
        .as("aktiver READ-Member auf DONE MUSS in done erscheinen")
        .extracting(Task::getId)
        .contains(TASK_MEMBER_DONE_ID);
    assertThat(page)
        .as("Ausgetretener (OPEN) darf NICHT in done erscheinen")
        .extracting(Task::getId)
        .doesNotContain(TASK_EXMEMBER_LEFT_ID);
    assertThat(count).as("done zum Wasserzeichen T0 = {MemberDone}; Orphan-DONE zaehlt nicht")
        .isEqualTo(1L);
  }

  // ---- Archiv: left + countArchiveLeft ----------------------------------------------------

  @Test
  @DisplayName(
      "findArchiveLeftPage_und_count_schliessen_orphan_left_und_fremde_exit_aus:"
          + " erwartet genau {ExMemberLeft}")
  void findArchiveLeftPage_und_count_schliessen_orphan_left_und_fremde_exit_aus(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveLeftPage(USER_B_REF, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 50);
    final long count = repo.countArchiveLeft(USER_B_REF, T0);

    assertThat(page)
        .as("Orphan-State abgemeldet (kein Participant) darf NICHT in left erscheinen")
        .extracting(Task::getId)
        .doesNotContain(TASK_ORPHAN_LEFT_ID);
    assertThat(page)
        .as("nur fremde Exit-Zeile (OPEN, kein task-tenant-Member) darf NICHT in left erscheinen")
        .extracting(Task::getId)
        .doesNotContain(TASK_FOREIGN_EXIT_ONLY_ID);
    assertThat(page)
        .as("ausgetretener READ-Member MUSS in left erscheinen (kein False-Negative)")
        .extracting(Task::getId)
        .contains(TASK_EXMEMBER_LEFT_ID);
    assertThat(count)
        .as("left zum Wasserzeichen T0 = {ExMemberLeft}; Orphan-Left + fremde Exit zaehlen nicht")
        .isEqualTo(1L);
  }

  // ---- Archiv: all + countArchiveAll ------------------------------------------------------

  @Test
  @DisplayName(
      "findArchiveAllPage_und_count_schliessen_orphans_aus: erwartet genau {ExMemberLeft, MemberDone}")
  void findArchiveAllPage_und_count_schliessen_orphans_aus(
      @Autowired TaskKeysetQueryRepository repo) {
    final List<Task> page =
        repo.findArchiveAllPage(
            USER_B_REF, STATUS_DONE, STATUS_CANCELLED, T0, CURSOR_LAST_ACTIVITY, CURSOR_EXT_ID, 50);
    final long count = repo.countArchiveAll(USER_B_REF, STATUS_DONE, STATUS_CANCELLED, T0);

    assertThat(page)
        .as("Orphan-State DONE darf NICHT in all erscheinen")
        .extracting(Task::getId)
        .doesNotContain(TASK_ORPHAN_DONE_ID);
    assertThat(page)
        .as("Orphan-State abgemeldet darf NICHT in all erscheinen")
        .extracting(Task::getId)
        .doesNotContain(TASK_ORPHAN_LEFT_ID);
    assertThat(page)
        .as("nur fremde Exit-Zeile (OPEN) darf NICHT in all erscheinen")
        .extracting(Task::getId)
        .doesNotContain(TASK_FOREIGN_EXIT_ONLY_ID);
    assertThat(page)
        .as("ausgetretener READ-Member + aktiver READ-Member auf DONE MUESSEN in all erscheinen")
        .extracting(Task::getId)
        .contains(TASK_EXMEMBER_LEFT_ID, TASK_MEMBER_DONE_ID);
    assertThat(count)
        .as("all zum Wasserzeichen T0 = {ExMemberLeft, MemberDone}; Orphans zaehlen nicht")
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
   * Fuegt einen TASK_USER_STATE-Eintrag ein.
   *
   * @param unsubscribedAt null = aktiv (abonniert); Wert = Abmeldezeitpunkt
   * @param angelegtAm Beitrittszeitpunkt (ANGELEGT_AM)
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

  /** Aktiver PERSON-Participant (EXIT_KIND NULL) = WRITE-/READ-Member. */
  private static void insertActiveParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID participantRef,
      final long participantTenantId,
      final LocalDateTime angelegtAm) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 1, 1, NULL, NULL, "
            + " 'it-seed', ?)",
        id, id, tenantId, taskId, participantRef, participantTenantId, angelegtAm);
  }

  /** Ausgetretener PERSON-Participant (EXIT_KIND HANDOVER) = READ-Member, KEIN WRITE-Member. */
  private static void insertExitedParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID participantRef,
      final long participantTenantId,
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
        id, id, tenantId, taskId, participantRef, participantTenantId, exitAt, angelegtAm);
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
      return () -> "keyset-membership-leak-it";
    }
  }
}
