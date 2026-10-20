package de.brasi.nova.taskmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
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
 * P1-Repo-IT (BE-1 Read-Enrichment): die drei neuen Batch-Query-Methoden
 * ({@link TaskParticipantQueryRepository#findActiveByTaskIds},
 * {@link TaskTagAssignmentQueryRepository#findActiveTagUuidsByTaskIds},
 * {@link TaskUserStateQueryRepository#findByTaskIdsAndUserRef}) liefern genau die aktiven Rows der
 * uebergebenen Seite - tenant-gebunden, aktiv-gefiltert, inkl. Austritts-Rows bzw. zugeordneter
 * katalog-inaktiver Tags, ohne fremden Mandanten.
 *
 * <p>Beweist insbesondere die KSF-Projektion {@link TaskTagUuidRow} (Mehrspalten-{@code (taskId,
 * tagUuid)} via {@code NovaDtoRowMapper}) auf echtem MSSQL.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskEnrichmentBatchQueryIT.ItConfig.class,
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
class TaskEnrichmentBatchQueryIT {

  private static final long TENANT_ID = 590L;
  private static final long FOREIGN_TENANT_ID = 591L;
  private static final UUID TENANT_UUID =
      UUID.fromString("00000059-0000-0059-0000-000000000590");
  private static final UUID FOREIGN_TENANT_UUID =
      UUID.fromString("00000059-0000-0059-0000-000000000591");

  // Actor + weitere Personen
  private static final UUID USER_A = UUID.fromString("00000059-000a-0059-0000-00000000059a");
  private static final UUID USER_B = UUID.fromString("00000059-000b-0059-0000-00000000059b");
  private static final UUID USER_C = UUID.fromString("00000059-000c-0059-0000-00000000059c");
  private static final UUID USER_D = UUID.fromString("00000059-000d-0059-0000-00000000059d");
  private static final UUID USER_E = UUID.fromString("00000059-000e-0059-0000-00000000059e");

  // Tasks (feste IDs 590xxx, ausserhalb KSF-Sequenz >= 1 000 000 000)
  private static final long TASK_1 = 590101L;
  private static final long TASK_2 = 590102L;
  private static final long TASK_3 = 590103L;
  private static final long TASK_FOREIGN = 591101L;
  private static final UUID TASK_1_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000590101");
  private static final UUID TASK_2_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000590102");
  private static final UUID TASK_3_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000590103");
  private static final UUID TASK_FOREIGN_UUID =
      UUID.fromString("00000059-0000-0059-bbbb-000000591101");

  // Bekannte Seed-IDs aus V1-Migration
  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_LOW = 201L;
  private static final long DIRECTION_BRASI_CUSTOMER = 301L;

  // Tag-UUIDs (aus DB nach Seed gelesen)
  private static UUID tagActiveUuid;
  private static UUID tagInactiveUuid;

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
    seedFirma(jt, TENANT_ID, TENANT_UUID);
    seedFirma(jt, FOREIGN_TENANT_ID, FOREIGN_TENANT_UUID);

    // globale Tags: 9301 aktiv, 9302 katalog-inaktiv (DEACTIVE, Zuordnung bleibt sichtbar),
    // 9303 nur fuer den DEACTIVE-Zuordnungs-Ausschluss (UNIQUE(TASK_ID,TAG_ID) verbietet Kollision
    // mit einer aktiven Zuordnung desselben Tags).
    tagActiveUuid = seedGlobalTag(jt, 9301L, "IT-Tag-Active", "ACTIVE");
    tagInactiveUuid = seedGlobalTag(jt, 9302L, "IT-Tag-Inactive", "DEACTIVE");
    seedGlobalTag(jt, 9303L, "IT-Tag-Extra", "ACTIVE");

    // TASK_1 (Tenant 590): voller Teilnehmer-Satz
    insertTask(jt, TASK_1, TASK_1_UUID, TENANT_ID, STATUS_OPEN, "IT-Enrich-Task-1");
    // Ersteller (A), Assignee (B), Teilnehmer (C), ausgetretener Teilnehmer (D, HANDOVER)
    insertParticipant(jt, 590201L, TASK_1, TENANT_ID, USER_A, "PERSON", true, false, false, null);
    insertParticipant(jt, 590202L, TASK_1, TENANT_ID, USER_B, "PERSON", false, true, false, null);
    insertParticipant(jt, 590203L, TASK_1, TENANT_ID, USER_C, "PERSON", false, false, true, null);
    insertParticipant(jt, 590204L, TASK_1, TENANT_ID, USER_D, "PERSON", false, false, true, "HANDOVER");
    // DEACTIVE-Teilnehmer (E) -> darf NICHT erscheinen
    insertParticipantWithStatus(
        jt, 590205L, TASK_1, TENANT_ID, USER_E, "PERSON", false, false, true, null, "DEACTIVE");

    // Actor-UserState auf TASK_1: alle Marker gesetzt
    insertUserState(jt, 590301L, TASK_1, TENANT_ID, USER_A, true, true, true);
    // Anderer User (B) UserState -> darf bei userRef=A NICHT erscheinen
    insertUserState(jt, 590302L, TASK_1, TENANT_ID, USER_B, false, false, false);

    // Tag-Zuordnungen auf TASK_1: aktiv (9301), aktiv (9302=katalog-inaktiv), DEACTIVE (9303, ausgeschlossen)
    insertTagAssignment(jt, 590401L, TASK_1, TENANT_ID, 9301L, "ACTIVE");
    insertTagAssignment(jt, 590402L, TASK_1, TENANT_ID, 9302L, "ACTIVE");
    insertTagAssignment(jt, 590403L, TASK_1, TENANT_ID, 9303L, "DEACTIVE");

    // TASK_2 (Tenant 590): minimal - nur Actor-Ersteller, keine Tags, aktive Actor-UserState
    insertTask(jt, TASK_2, TASK_2_UUID, TENANT_ID, STATUS_OPEN, "IT-Enrich-Task-2");
    insertParticipant(jt, 590210L, TASK_2, TENANT_ID, USER_A, "PERSON", true, false, false, null);
    insertUserState(jt, 590310L, TASK_2, TENANT_ID, USER_A, false, false, false);

    // TASK_3 (Tenant 590): nur eine DEACTIVE Actor-UserState -> Exclusion-Beweis (keine aktive Row
    // desselben (TASK_ID,USER_REF), daher keine UNIQUE-Kollision).
    insertTask(jt, TASK_3, TASK_3_UUID, TENANT_ID, STATUS_OPEN, "IT-Enrich-Task-3");
    insertUserStateWithStatus(jt, 590303L, TASK_3, TENANT_ID, USER_A, true, false, false, "DEACTIVE");

    // TASK_FOREIGN (Tenant 591): voller Satz - darf im Tenant-590-Kontext NIE erscheinen
    insertTask(jt, TASK_FOREIGN, TASK_FOREIGN_UUID, FOREIGN_TENANT_ID, STATUS_OPEN, "IT-Enrich-Foreign");
    insertParticipant(
        jt, 590220L, TASK_FOREIGN, FOREIGN_TENANT_ID, USER_A, "PERSON", true, false, false, null);
    insertUserState(jt, 590320L, TASK_FOREIGN, FOREIGN_TENANT_ID, USER_A, true, true, true);
    insertTagAssignment(jt, 590420L, TASK_FOREIGN, FOREIGN_TENANT_ID, 9301L, "ACTIVE");
  }

  @BeforeEach
  void setTenantContext() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  // ---- findActiveByTaskIds -------------------------------------------------------------------

  @Test
  @DisplayName(
      "findActiveByTaskIds_liefert_alle_aktiven_rows_inkl_exit_ohne_inaktiv_ohne_fremdtenant")
  void findActiveByTaskIds_liefert_alle_aktiven_rows(
      @Autowired TaskParticipantQueryRepository repo) {
    final List<TaskParticipant> rows =
        repo.findActiveByTaskIds(List.of(TASK_1, TASK_2, TASK_FOREIGN));

    // TASK_1: A (creator), B (assignee), C (participant), D (participant+exit) = 4 PERSON-Rows
    //   NICHT: E (DEACTIVE)
    // TASK_2: A (creator) = 1
    //   NICHT: TASK_FOREIGN-Row 590220 (Fremdmandant -> :tenantId-Binding)
    assertThat(rows)
        .as("nur aktive Rows der eigenen Tenant-Aufgaben")
        .extracting(TaskParticipant::getId)
        .containsExactlyInAnyOrder(590201L, 590202L, 590203L, 590204L, 590210L);

    // Austritts-Row (D) ist enthalten (kein EXIT_KIND-Filter in der Batch-Query)
    assertThat(rows)
        .filteredOn(p -> p.getId() == 590204L)
        .singleElement()
        .satisfies(p -> assertThat(p.getExitKind()).isNotNull());

    // DEACTIVE und Fremdmandant ausgeschlossen
    assertThat(rows).extracting(TaskParticipant::getId).doesNotContain(590205L, 590220L);
  }

  // ---- findActiveTagUuidsByTaskIds (Projektion) ----------------------------------------------

  @Test
  @DisplayName(
      "findActiveTagUuidsByTaskIds_mappt_projektion_inkl_inaktivem_tag_ohne_deactive_assignment")
  void findActiveTagUuidsByTaskIds_mappt_projektion(
      @Autowired TaskTagAssignmentQueryRepository repo) {
    final List<TaskTagUuidRow> rows =
        repo.findActiveTagUuidsByTaskIds(List.of(TASK_1, TASK_2, TASK_FOREIGN));

    // TASK_1 -> tagActive + tagInactive(Katalog DEACTIVE, aber Zuordnung ACTIVE) = beide
    // DEACTIVE-Zuordnung (590403) NICHT; TASK_2 hat keine Tags;
    // TASK_FOREIGN-Zuordnung (590420) nicht enthalten (:tenantId-Binding).
    assertThat(rows)
        .as("Projektion (taskId,tagUuid) je aktiver Zuordnung, inkl. katalog-inaktivem Tag")
        .extracting(TaskTagUuidRow::taskId, TaskTagUuidRow::tagUuid)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(TASK_1, tagActiveUuid),
            org.assertj.core.groups.Tuple.tuple(TASK_1, tagInactiveUuid));
  }

  // ---- findByTaskIdsAndUserRef ---------------------------------------------------------------

  @Test
  @DisplayName(
      "findByTaskIdsAndUserRef_liefert_nur_actor_rows_mit_markern_ohne_fremduser_ohne_inaktiv")
  void findByTaskIdsAndUserRef_liefert_nur_actor_rows(
      @Autowired TaskUserStateQueryRepository repo) {
    final List<TaskUserState> rows =
        repo.findByTaskIdsAndUserRef(List.of(TASK_1, TASK_2, TASK_3, TASK_FOREIGN), USER_A);

    // Nur Actor-A-Rows: TASK_1 (590301, Marker true), TASK_2 (590310, Marker false)
    //   NICHT: 590302 (User B), 590303 (TASK_3 DEACTIVE), 590320 (Fremdtenant)
    assertThat(rows)
        .extracting(TaskUserState::getId)
        .containsExactlyInAnyOrder(590301L, 590310L);

    assertThat(rows)
        .filteredOn(s -> s.getId() == 590301L)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.isPinned()).isTrue();
              assertThat(s.isFlagged()).isTrue();
              assertThat(s.isMuted()).isTrue();
            });
  }

  // ---- Seed-Hilfsmethoden --------------------------------------------------------------------

  private static void seedFirma(final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static UUID seedGlobalTag(
      final JdbcTemplate jt, final long id, final String bezeichnung, final String status) {
    final UUID externalId = UUID.randomUUID();
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_TAG] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_TAG] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, BEZEICHNUNG, ENTRY_TYPE,"
            + " GUELTIG_VON, SEVERITY_ID) "
            + "VALUES (?, ?, ?, 1, 0, ?, 'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), 601)",
        id, id, status, externalId, bezeichnung);
    return jt.queryForObject("SELECT EXTERNALID FROM [dbo].[TASK_TAG] WHERE ID = ?", UUID.class, id);
  }

  private static void insertTask(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final long tenantId,
      final long statusId,
      final String title) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE, DESCRIPTION, TASK_STATUS_ID, "
            + " PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT, COMMENT_COUNT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?, ?, GETDATE(), 0, 'it-seed', GETDATE())",
        id, id, externalId, tenantId, title, title + " desc", statusId,
        PRIORITY_LOW, DIRECTION_BRASI_CUSTOMER, tenantId);
  }

  private static void insertParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID participantRef,
      final String kind,
      final boolean creator,
      final boolean assignee,
      final boolean participant,
      final String exitKind) {
    insertParticipantWithStatus(
        jt, id, taskId, tenantId, participantRef, kind, creator, assignee, participant, exitKind,
        "ACTIVE");
  }

  private static void insertParticipantWithStatus(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID participantRef,
      final String kind,
      final boolean creator,
      final boolean assignee,
      final boolean participant,
      final String exitKind,
      final String status) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, ?, NEWID(), 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'it-seed', GETDATE())",
        id, id, status, tenantId, taskId, participantRef, kind, tenantId,
        creator ? 1 : 0, assignee ? 1 : 0, participant ? 1 : 0, exitKind);
  }

  private static void insertUserState(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID userRef,
      final boolean pinned,
      final boolean flagged,
      final boolean muted) {
    insertUserStateWithStatus(jt, id, taskId, tenantId, userRef, pinned, flagged, muted, "ACTIVE");
  }

  private static void insertUserStateWithStatus(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID userRef,
      final boolean pinned,
      final boolean flagged,
      final boolean muted,
      final String status) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, USER_REF, "
            + " USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, ?, NEWID(), 1, ?, ?, ?, ?, ?, ?, ?, NULL, 'it-seed', GETDATE())",
        id, id, status, tenantId, taskId, userRef, tenantId,
        pinned ? 1 : 0, flagged ? 1 : 0, muted ? 1 : 0);
  }

  private static void insertTagAssignment(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final long tagId,
      final String status) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_TAG_ASSIGNMENT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_TAG_ASSIGNMENT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, TAG_ID, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, ?, NEWID(), 1, ?, ?, ?, 'it-seed', GETDATE())",
        id, id, status, tenantId, taskId, tagId);
  }

  // ---- IT-Konfigurations-Context -------------------------------------------------------------

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
      return () -> "enrichment-batch-it";
    }
  }
}
