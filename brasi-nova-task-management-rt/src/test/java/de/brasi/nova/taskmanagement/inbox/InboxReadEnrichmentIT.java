package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.tag.TagCatalogService;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * P5-End-to-End-IT (BE-1 Read-Enrichment) auf echtem MSSQL: der echte {@link InboxSnapshotService}
 * (reale Keyset-/Batch-Query-Repos + realer {@link InboxEnrichmentAssembler}) reichert aktive Seite
 * und Archiv korrekt an. Peripherie ohne Enrichment-Bezug (Notifications/Tags-Katalog/Participant-
 * Summaries/Resolver/Generation) ist gestubbt, damit der IT fokussiert die keyset-then-enrich-Kette
 * gegen echte Daten prueft.
 *
 * <p>Matrix: 0 / 1 / 100 Zeilen; createdById/assignees/participants/tags/Marker/isDone/description;
 * {@code exit}-Mapping handover/unsubscribed/Praezedenz; asOf-Grenze (Future-Unsub bleibt aktiv,
 * {@code exit=null}); fremder Mandant erscheint nicht; katalog-inaktiver Tag bleibt sichtbar.
 *
 * <p>Die N+1-/Query-Zahl-Invariante ist auf Invocation-Ebene in {@code InboxSnapshotServiceTest}
 * (Mockito-Sentinel: je Batch-Repo genau 1 Aufruf pro Versuch, 0 bei leerer Seite) verankert; dieser
 * IT beweist die Korrektheit der Anreicherung bei 1 UND 100 Zeilen gegen echtes MSSQL.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = InboxReadEnrichmentIT.ItConfig.class,
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
class InboxReadEnrichmentIT {

  private static final long TENANT_ID = 590L;
  private static final long FOREIGN_TENANT_ID = 591L;
  private static final UUID TENANT_UUID =
      UUID.fromString("00000059-0000-0059-0000-000000000590");
  private static final UUID FOREIGN_TENANT_UUID =
      UUID.fromString("00000059-0000-0059-0000-000000000591");

  private static final UUID USER_A = UUID.fromString("00000059-000a-0059-0000-00000000059a");
  private static final UUID USER_B = UUID.fromString("00000059-000b-0059-0000-00000000059b");
  private static final UUID USER_C = UUID.fromString("00000059-000c-0059-0000-00000000059c");
  private static final UUID USER_D = UUID.fromString("00000059-000d-0059-0000-00000000059d");
  private static final UUID USER_EMPTY = UUID.fromString("00000059-00e0-0059-0000-0000000005e0");
  private static final UUID USER_VOL = UUID.fromString("00000059-00f0-0059-0000-0000000005f0");

  private static final long STATUS_OPEN = 101L;
  private static final long STATUS_DONE = 102L;
  private static final long STATUS_CANCELLED = 103L;
  private static final long PRIORITY_LOW = 201L;
  private static final long DIRECTION_BRASI_CUSTOMER = 301L;

  // Task-IDs (fest, 590xxx)
  private static final long AE_FULL = 590501L;
  private static final long AE_FUTURE_UNSUB = 590502L;
  private static final long AL_UNSUB = 590511L;
  private static final long AL_HANDOVER = 590512L;
  private static final long AL_BOTH = 590513L;
  private static final long AD_DONE = 590521L;
  private static final long AD_CANCELLED = 590522L;
  private static final long AF_FOREIGN = 591501L;

  private static final UUID AE_FULL_UUID = UUID.fromString("00000059-0501-0059-0000-000000590501");
  private static final UUID AE_FUTURE_UUID = UUID.fromString("00000059-0502-0059-0000-000000590502");
  private static final UUID AL_UNSUB_UUID = UUID.fromString("00000059-0511-0059-0000-000000590511");
  private static final UUID AL_HANDOVER_UUID = UUID.fromString("00000059-0512-0059-0000-000000590512");
  private static final UUID AL_BOTH_UUID = UUID.fromString("00000059-0513-0059-0000-000000590513");
  private static final UUID AD_DONE_UUID = UUID.fromString("00000059-0521-0059-0000-000000590521");
  private static final UUID AD_CANCELLED_UUID = UUID.fromString("00000059-0522-0059-0000-000000590522");
  private static final UUID AF_FOREIGN_UUID = UUID.fromString("00000059-1501-0059-0000-000000591501");

  private static final LocalDateTime PAST = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
  private static final LocalDateTime FUTURE = LocalDateTime.of(2099, 1, 1, 8, 0, 0);

  private static UUID tagActiveUuid;
  private static UUID tagInactiveUuid;

  private static HikariDataSource master;
  private static String dbName;

  private InboxSnapshotService service;

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
    tagActiveUuid = seedGlobalTag(jt, 9501L, "IT-E2E-Tag-Active", "ACTIVE");
    tagInactiveUuid = seedGlobalTag(jt, 9502L, "IT-E2E-Tag-Inactive", "DEACTIVE");

    // --- Aktive Seite (USER_A) ---
    // AE_FULL: Ersteller A (+aktive UserState mit allen Markern), Assignee B, Teilnehmer C,
    //          ausgetretener Teilnehmer D (HANDOVER), zwei Tags (einer katalog-inaktiv).
    insertTask(jt, AE_FULL, AE_FULL_UUID, TENANT_ID, STATUS_OPEN, "AE-Full", "Beschreibung AE");
    participant(jt, 591001L, AE_FULL, TENANT_ID, USER_A, "PERSON", true, false, false, null, null);
    participant(jt, 591002L, AE_FULL, TENANT_ID, USER_B, "PERSON", false, true, false, null, null);
    participant(jt, 591003L, AE_FULL, TENANT_ID, USER_C, "PERSON", false, false, true, null, null);
    participant(jt, 591004L, AE_FULL, TENANT_ID, USER_D, "PERSON", false, false, true, "HANDOVER", PAST);
    userState(jt, 591005L, AE_FULL, TENANT_ID, USER_A, true, true, true, null);
    tagAssignment(jt, 591006L, AE_FULL, TENANT_ID, 9501L, "ACTIVE");
    tagAssignment(jt, 591007L, AE_FULL, TENANT_ID, 9502L, "ACTIVE");

    // AE_FUTURE_UNSUB: A aktiv, aber UNSUBSCRIBED_AT in der Zukunft -> bleibt aktiv, exit=null.
    insertTask(jt, AE_FUTURE_UNSUB, AE_FUTURE_UUID, TENANT_ID, STATUS_OPEN, "AE-Future", null);
    participant(jt, 591011L, AE_FUTURE_UNSUB, TENANT_ID, USER_A, "PERSON", true, false, false, null, null);
    userStateUnsub(jt, 591012L, AE_FUTURE_UNSUB, TENANT_ID, USER_A, FUTURE);

    // --- Archiv-left (USER_A) ---
    // AL_UNSUB: A per UserState abgemeldet (Vergangenheit). Slice 6 (Membership-Konjunkt): die
    // Archiv-Sicht verlangt zusaetzlich einen PERSON-Participant (READ-Member). A hat sich nur via
    // TASK_USER_STATE.UNSUBSCRIBED_AT abgemeldet, bleibt aber Participant -> aktive PERSON-Zeile.
    insertTask(jt, AL_UNSUB, AL_UNSUB_UUID, TENANT_ID, STATUS_OPEN, "AL-Unsub", null);
    userStateUnsub(jt, 591101L, AL_UNSUB, TENANT_ID, USER_A, PAST);
    participant(jt, 591102L, AL_UNSUB, TENANT_ID, USER_A, "PERSON", false, false, true, null, null);
    // AL_HANDOVER: A per Participant-HANDOVER ausgetreten (Vergangenheit), keine aktive UserState.
    insertTask(jt, AL_HANDOVER, AL_HANDOVER_UUID, TENANT_ID, STATUS_OPEN, "AL-Handover", null);
    participant(jt, 591111L, AL_HANDOVER, TENANT_ID, USER_A, "PERSON", false, false, true, "HANDOVER", PAST);
    // AL_BOTH: beide Signale -> Praezedenz handover.
    insertTask(jt, AL_BOTH, AL_BOTH_UUID, TENANT_ID, STATUS_OPEN, "AL-Both", null);
    participant(jt, 591121L, AL_BOTH, TENANT_ID, USER_A, "PERSON", false, false, true, "HANDOVER", PAST);
    userStateUnsub(jt, 591122L, AL_BOTH, TENANT_ID, USER_A, PAST);

    // --- Archiv-done (USER_A, aktive UserState) ---
    // Slice 6 (Membership-Konjunkt): die Archiv-done-Sicht verlangt zusaetzlich einen aktiven
    // PERSON-Participant (READ-Member); State und Participant koexistieren fuer legitime Mitglieder.
    insertTask(jt, AD_DONE, AD_DONE_UUID, TENANT_ID, STATUS_DONE, "AD-Done", null);
    userState(jt, 591201L, AD_DONE, TENANT_ID, USER_A, false, false, false, null);
    participant(jt, 591203L, AD_DONE, TENANT_ID, USER_A, "PERSON", false, false, true, null, null);
    insertTask(jt, AD_CANCELLED, AD_CANCELLED_UUID, TENANT_ID, STATUS_CANCELLED, "AD-Cancelled", null);
    userState(jt, 591202L, AD_CANCELLED, TENANT_ID, USER_A, false, false, false, null);
    participant(jt, 591204L, AD_CANCELLED, TENANT_ID, USER_A, "PERSON", false, false, true, null, null);

    // --- Fremder Mandant (591) ---
    insertTask(jt, AF_FOREIGN, AF_FOREIGN_UUID, FOREIGN_TENANT_ID, STATUS_OPEN, "AF-Foreign", null);
    participant(jt, 591301L, AF_FOREIGN, FOREIGN_TENANT_ID, USER_A, "PERSON", true, false, false, null, null);
    userState(jt, 591302L, AF_FOREIGN, FOREIGN_TENANT_ID, USER_A, false, false, false, null);

    // --- 100 aktive Aufgaben (USER_VOL) fuer den Volumen-Fall ---
    for (int i = 0; i < 100; i++) {
      final long taskId = 590700L + i;
      insertTask(
          jt, taskId, new UUID(0x0000005907000000L, taskId), TENANT_ID, STATUS_OPEN,
          "AV-" + i, null);
      participant(jt, 592000L + i, taskId, TENANT_ID, USER_VOL, "PERSON", true, false, false, null, null);
      userState(jt, 593000L + i, taskId, TENANT_ID, USER_VOL, false, false, false, null);
    }
  }

  @BeforeEach
  void setUp(
      @Autowired TaskKeysetQueryRepository taskQueryRepository,
      @Autowired TaskParticipantQueryRepository participantQueryRepository,
      @Autowired TaskTagAssignmentQueryRepository tagAssignmentQueryRepository,
      @Autowired TaskUserStateQueryRepository userStateQueryRepository,
      @Autowired de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository
              handoverQueryRepository) {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));

    // Handgeschriebene Stub-Subklassen statt Mockito: der ByteBuddy-Inline-MockMaker kann auf
    // JDK 25 bereits geladene Klassen nicht zuverlaessig re-instrumentieren. Fuer die reinen
    // Seiten-Reads reicht ein Status-/Generation-Stub; die uebrigen Kollaborateure
    // (Notifications/Tag-Katalog/Participant-Summaries) werden von loadActivePage/loadArchivePage
    // NICHT aufgerufen -> null.
    final DictionaryCodeResolver resolver =
        new DictionaryCodeResolver(null, null, null, null, null, null) {
          @Override
          public Long resolveTaskStatusOpen() {
            return STATUS_OPEN;
          }

          @Override
          public Long resolveTaskStatusDone() {
            return STATUS_DONE;
          }

          @Override
          public Long resolveTaskStatusCancelled() {
            return STATUS_CANCELLED;
          }

          @Override
          public String resolveTaskStatusCode(final Long id) {
            final long sid = id;
            if (sid == STATUS_DONE) {
              return "DONE";
            }
            if (sid == STATUS_CANCELLED) {
              return "CANCELLED";
            }
            return "OPEN";
          }

          @Override
          public String resolveTaskPriorityCode(final Long id) {
            return "NORMAL";
          }
        };
    final InboxGenerationService generation =
        new InboxGenerationService(null) {
          @Override
          public long currentGeneration(final UUID userRef) {
            return 0L;
          }
        };

    service =
        new InboxSnapshotService(
            taskQueryRepository,
            participantQueryRepository,
            tagAssignmentQueryRepository,
            userStateQueryRepository,
            handoverQueryRepository,
            new InboxEnrichmentAssembler(),
            null,
            null,
            null,
            resolver,
            new AuditTimeConverter("Europe/Berlin"),
            generation);
  }

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
  }

  // ---- Tests ---------------------------------------------------------------------------------

  @Test
  @DisplayName("aktive Seite: AE_FULL vollstaendig angereichert; Future-Unsub bleibt aktiv (exit=null)")
  void active_page_enrichment() {
    final Map<UUID, TaskSummaryModel> byId = byId(service.loadActivePage(USER_A, null, 50));

    final TaskSummaryModel full = byId.get(AE_FULL_UUID);
    assertThat(full).as("AE_FULL muss aktiv sein").isNotNull();
    assertThat(full.createdById()).isEqualTo(USER_A);
    assertThat(full.assigneeIds()).containsExactly(USER_B);
    assertThat(full.participantIds()).containsExactly(USER_C);
    assertThat(full.tagIds())
        .as("beide zugeordneten Tags inkl. katalog-inaktivem")
        .containsExactlyInAnyOrder(tagActiveUuid, tagInactiveUuid);
    assertThat(full.isPinned()).isTrue();
    assertThat(full.isFlagged()).isTrue();
    assertThat(full.isMuted()).isTrue();
    assertThat(full.isDone()).isFalse();
    assertThat(full.exit()).isNull();
    assertThat(full.createdAt()).isNotNull();
    assertThat(full.description()).isEqualTo("Beschreibung AE");

    final TaskSummaryModel future = byId.get(AE_FUTURE_UUID);
    assertThat(future).as("Future-Unsub bleibt aktiv").isNotNull();
    assertThat(future.exit()).as("Unsub in der Zukunft -> kein Austritt zum asOf").isNull();
  }

  @Test
  @DisplayName("Archiv-left: exit-Mapping handover/unsubscribed + Praezedenz handover")
  void archive_left_exit_mapping() {
    final Map<UUID, TaskSummaryModel> byId = byId(service.loadArchivePage(USER_A, "left", null, 50));

    assertThat(byId.get(AL_UNSUB_UUID)).isNotNull();
    assertThat(byId.get(AL_UNSUB_UUID).exit().kind()).isEqualTo("unsubscribed");
    assertThat(byId.get(AL_HANDOVER_UUID).exit().kind()).isEqualTo("handover");
    assertThat(byId.get(AL_BOTH_UUID).exit().kind())
        .as("beide Signale -> Praezedenz handover")
        .isEqualTo("handover");
  }

  @Test
  @DisplayName("Archiv-done: isDone=true fuer DONE und CANCELLED")
  void archive_done_isDone() {
    final Map<UUID, TaskSummaryModel> byId = byId(service.loadArchivePage(USER_A, "done", null, 50));
    assertThat(byId.get(AD_DONE_UUID).isDone()).isTrue();
    assertThat(byId.get(AD_CANCELLED_UUID).isDone()).isTrue();
    assertThat(byId.get(AD_DONE_UUID).exit()).isNull();
  }

  @Test
  @DisplayName("fremder Mandant erscheint NICHT in der Tenant-590-Sicht")
  void foreign_tenant_excluded() {
    final Map<UUID, TaskSummaryModel> byId = byId(service.loadActivePage(USER_A, null, 50));
    assertThat(byId).doesNotContainKey(AF_FOREIGN_UUID);
  }

  @Test
  @DisplayName("0 Zeilen: leere aktive Seite ohne Fehler")
  void zero_rows_empty_page() {
    final KeysetPage<TaskSummaryModel> page = service.loadActivePage(USER_EMPTY, null, 50);
    assertThat(page.items()).isEmpty();
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  @DisplayName("100 Zeilen: alle angereichert (createdById gesetzt), Korrektheit bei Volumen")
  void hundred_rows_enriched() {
    final KeysetPage<TaskSummaryModel> page = service.loadActivePage(USER_VOL, null, 100);
    assertThat(page.items()).hasSize(100);
    assertThat(page.items()).allSatisfy(s -> assertThat(s.createdById()).isEqualTo(USER_VOL));
  }

  // ---- Hilfen --------------------------------------------------------------------------------

  private static Map<UUID, TaskSummaryModel> byId(final KeysetPage<TaskSummaryModel> page) {
    return page.items().stream().collect(Collectors.toMap(TaskSummaryModel::id, s -> s));
  }

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
      final String title,
      final String description) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE, DESCRIPTION, TASK_STATUS_ID, "
            + " PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT, COMMENT_COUNT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'it-seed', ?)",
        id, id, externalId, tenantId, title, description, statusId,
        PRIORITY_LOW, DIRECTION_BRASI_CUSTOMER, tenantId, PAST, PAST);
  }

  private static void participant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID ref,
      final String kind,
      final boolean creator,
      final boolean assignee,
      final boolean participant,
      final String exitKind,
      final LocalDateTime exitAt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'it-seed', ?)",
        id, id, tenantId, taskId, ref, kind, tenantId,
        creator ? 1 : 0, assignee ? 1 : 0, participant ? 1 : 0, exitKind, exitAt, PAST);
  }

  private static void userState(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID userRef,
      final boolean pinned,
      final boolean flagged,
      final boolean muted,
      final LocalDateTime unsubscribedAt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, USER_REF, "
            + " USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, ?, ?, ?, ?, 'it-seed', ?)",
        id, id, tenantId, taskId, userRef, tenantId,
        pinned ? 1 : 0, flagged ? 1 : 0, muted ? 1 : 0, unsubscribedAt, PAST);
  }

  private static void userStateUnsub(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tenantId,
      final UUID userRef,
      final LocalDateTime unsubscribedAt) {
    userState(jt, id, taskId, tenantId, userRef, false, false, false, unsubscribedAt);
  }

  private static void tagAssignment(
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
      return () -> "inbox-read-enrichment-it";
    }
  }
}
