package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.support.AttachmentCountAssertions.assertCountMatchesRows;
import static de.brasi.nova.taskmanagement.support.AttachmentCountAssertions.attachmentCount;
import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.push.InboxSignalPort;
import de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Nebenlaeufigkeits-IT des Anhang-Zaehlers (Intake #194, Review-Fokus 1): zwei Uploads an dieselbe
 * Aufgabe, einer committet, der andere scheitert am versionierten TASK-save (409, voller Rollback).
 * Danach ist {@code ANHANG_ANZAHL} gleich der Zahl der aktiven Anhangzeilen, und von der
 * gescheiterten Transaktion bleibt keine Datei liegen.
 *
 * <p><b>Erzwungener Ablauf</b> am Seam {@code onAfterAttachmentsStoredForTest} (nach dem Speichern
 * der Zeilen und Dateien, vor dem TASK-save):
 *
 * <ol>
 *   <li>Upload A erreicht den Seam: Zeilen und Datei sind geschrieben (X-gesperrt), die Aufgabe ist
 *       gelesen, aber noch nicht gespeichert. A merkt sich seine Sitzung ({@code @@SPID}).
 *   <li>Am Seam startet Upload B auf einem eigenen Thread (eigener Mandanten- und Akteurskontext).
 *       B liest die Aufgabe mit derselben VERSION und wartet bei der Zaehlung der aktiven Anhaenge
 *       auf die Sperren von A (sperrendes READ COMMITTED). A verlaesst den Seam, sobald B auf A
 *       wartet oder schon fertig ist; das wird ueber eine unabhaengige master-Verbindung in {@code
 *       sys.dm_exec_requests} beobachtet, nicht abgewartet (Lesson {@code concurrency-test-seams}).
 *   <li>A speichert die Aufgabe und committet; B laeuft weiter, speichert seine Zeile und Datei und
 *       trifft beim TASK-save die veraltete VERSION: 409, Rollback, die Datei von B wird entfernt.
 *       Ohne sperrende Lesung (RCSI) laeuft B vorher durch, und A scheitert; der Test verlangt nur
 *       genau einen Gewinner und genau eine 409.
 * </ol>
 *
 * <p>Rot vor der Umsetzung nur an der Zaehlerpruefung: der Ablauf (eine 409, eine Zeile, eine
 * Datei) gilt schon heute.
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = AttachmentCountConcurrencyIT.ItConfig.class,
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
      // A haelt seine Verbindung am Seam, B braucht eine zweite, die Sequenz-Vergabe (eigene
      // Transaktion) eine weitere.
      "spring.datasource.hikari.maximum-pool-size=6",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class AttachmentCountConcurrencyIT {

  private static final long TENANT_ID = 597L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0597-0000-000000000597");
  private static final UUID ACTOR_A = UUID.fromString("00000059-00a0-0597-0000-000000000597");
  private static final UUID ACTOR_B = UUID.fromString("00000059-00b0-0597-0000-000000000597");
  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_NORMAL = 202L;
  private static final long DIRECTION = 301L;

  private static final long TASK_ID = 597101L;
  private static final UUID TASK_UUID = UUID.fromString("00000059-0000-0597-aaaa-000000597101");

  /** Hoechstdauer, bis B auf A wartet oder fertig ist. */
  private static final long GATE_TIMEOUT_MS = 30_000L;

  /** Abfrageabstand der Sperr-Sonde. */
  private static final long POLL_MS = 25L;

  /** Hoechstdauer fuer das Ende von B nach dem Commit von A. */
  private static final long JOIN_TIMEOUT_MS = 60_000L;

  private static final byte[] TXT_BYTES = "Notiz".getBytes(StandardCharsets.UTF_8);

  /** Thread-lokaler Akteur; der {@link UserAccessor} der IT liest ihn. */
  private static final ThreadLocal<UUID> ACTOR_PER_THREAD = ThreadLocal.withInitial(() -> ACTOR_A);

  private static HikariDataSource master;
  private static String dbName;
  private static Path baseDir;

  @Autowired private SeamCommandService commandService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
    try {
      baseDir = Files.createTempDirectory("attachment-count-it");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
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
      deleteTreeQuietly(baseDir);
    }
  }

  @BeforeAll
  static void seed(@Autowired final JdbcTemplate jt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " NAME, GUELTIG_VON) VALUES (?, 'ACTIVE', ?, 1, ?, 'IT-FIRMA-194',"
            + " CAST(GETDATE() AS DATE))",
        TENANT_ID,
        TENANT_ID,
        TENANT_UUID,
        TENANT_ID);
    jt.update(
        "INSERT INTO [dbo].[TASK] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE,"
            + " TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT,"
            + " COMMENT_COUNT, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', ?, 1, ?, 'IT-Zaehler-Konkurrenz', ?, ?, ?, ?,"
            + " DATEADD(hour, -1, GETDATE()), 0, 'it-seed', DATEADD(hour, -1, GETDATE()))",
        TASK_ID,
        TASK_UUID,
        TENANT_ID,
        STATUS_OPEN,
        PRIORITY_NORMAL,
        DIRECTION,
        TENANT_ID);
    insertActiveMember(jt, 597201L, ACTOR_A, true);
    insertActiveMember(jt, 597202L, ACTOR_B, false);
  }

  @Test
  @DisplayName(
      "i194_parallel_uploads_one_conflict_count_matches_rows: zwei Uploads an dieselbe Aufgabe,"
          + " einer committet, einer 409; ANHANG_ANZAHL gleich den aktiven Zeilen, keine verwaiste"
          + " Datei")
  void i194_parallel_uploads_one_conflict_count_matches_rows() throws Exception {
    final int versionBefore = taskVersion();
    final int countBefore = attachmentCount(jdbcTemplate, TASK_UUID);
    final Set<Path> filesBefore = filesUnder(baseDir);
    final JdbcTemplate probe = new JdbcTemplate(master);

    final AtomicReference<Throwable> errorA = new AtomicReference<>();
    final AtomicReference<Throwable> errorB = new AtomicReference<>();
    final AtomicReference<String> gate = new AtomicReference<>("Seam nicht erreicht");
    final Thread uploadB =
        new Thread(
            () -> {
              TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
              ACTOR_PER_THREAD.set(ACTOR_B);
              try {
                commandService.storeTaskAttachments(TASK_UUID, List.of(prepared("b.txt")));
              } catch (final Throwable t) {
                errorB.set(t);
              } finally {
                ACTOR_PER_THREAD.remove();
                TenantContextHolder.clear();
              }
            },
            "attachment-count-upload-b");

    // Am Seam von A (einmalig): B starten und warten, bis B auf A wartet oder fertig ist.
    commandService.armAfterStore(
        () -> {
          final int sessionA = jdbcTemplate.queryForObject("SELECT @@SPID", Integer.class);
          uploadB.start();
          gate.set(awaitBlockedOrDone(probe, uploadB, sessionA));
        });

    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ACTOR_A);
    try {
      commandService.storeTaskAttachments(TASK_UUID, List.of(prepared("a.txt")));
    } catch (final Throwable t) {
      errorA.set(t);
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
      commandService.armAfterStore(null);
    }
    uploadB.join(JOIN_TIMEOUT_MS);

    // Ablauf: genau ein Gewinner, genau eine 409, kein Haengen, keine andere Ausnahme
    assertThat(gate.get()).as("Sync-Punkt am Seam").isIn("B wartet auf A", "B fertig");
    assertThat(uploadB.isAlive()).as("Upload B beendet (kein Deadlock)").isFalse();
    final List<Throwable> errors =
        Stream.of(errorA.get(), errorB.get()).filter(e -> e != null).toList();
    assertThat(errors).as("genau ein Upload scheitert").hasSize(1);
    assertThat(errors.get(0))
        .as("der Verlierer scheitert am Versionskonflikt (409)")
        .isInstanceOf(BusinessRuleException.class);
    assertThat(taskVersion()).as("genau ein versionierter TASK-save").isEqualTo(versionBefore + 1);
    final List<String> activeKeys = activeStorageKeys();
    assertThat(activeKeys).as("genau eine aktive Anhangzeile").hasSize(1);
    final Set<Path> newFiles = new HashSet<>(filesUnder(baseDir));
    newFiles.removeAll(filesBefore);
    assertThat(newFiles)
        .as("nur die Datei des Gewinners, keine verwaiste Datei des Verlierers")
        .containsExactly(baseDir.resolve(activeKeys.get(0)));

    // Zaehler: Gewinner +1, Verlierer zurueckgerollt
    assertThat(attachmentCount(jdbcTemplate, TASK_UUID) - countBefore)
        .as("ANHANG_ANZAHL nach einem erfolgreichen Upload")
        .isEqualTo(1);
    assertCountMatchesRows(jdbcTemplate, TASK_UUID);
  }

  // ---- Sonden -------------------------------------------------------------------------------

  /** Wartet, bis B auf eine Sperre von A wartet oder fertig ist; liefert den Ausgang. */
  private static String awaitBlockedOrDone(
      final JdbcTemplate probe, final Thread uploadB, final int sessionA) {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GATE_TIMEOUT_MS);
    try {
      while (true) {
        uploadB.join(POLL_MS);
        if (!uploadB.isAlive()) {
          return "B fertig";
        }
        final Integer waiting =
            probe.queryForObject(
                "SELECT COUNT(*) FROM sys.dm_exec_requests WHERE blocking_session_id = ?",
                Integer.class,
                sessionA);
        if (waiting != null && waiting > 0) {
          return "B wartet auf A";
        }
        if (System.nanoTime() > deadline) {
          return "Zeitablauf am Seam";
        }
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unterbrochen";
    }
  }

  private int taskVersion() {
    return jdbcTemplate.queryForObject(
        "SELECT VERSION FROM [dbo].[TASK] WHERE ID = ?", Integer.class, TASK_ID);
  }

  /** Speicherschluessel der aktiven Aufgaben-Anhaenge. */
  private List<String> activeStorageKeys() {
    return jdbcTemplate.queryForList(
        "SELECT SPEICHERSCHLUESSEL FROM [dbo].[TASK_ANHANG]"
            + " WHERE TASK_ID = ? AND ENTITY_STATUS = 'ACTIVE'",
        String.class,
        TASK_ID);
  }

  private static PreparedAttachment prepared(final String name) {
    return new PreparedAttachment(
        name,
        AttachmentFileType.fromExtension("txt").orElseThrow(),
        "txt",
        TXT_BYTES.length,
        "ab".repeat(32),
        new ByteArrayResource(TXT_BYTES),
        null);
  }

  private static void insertActiveMember(
      final JdbcTemplate jt, final long id, final UUID ref, final boolean creator) {
    jt.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, ?, 1, 1, 'it-seed',"
            + " DATEADD(hour, -1, GETDATE()))",
        id,
        TENANT_ID,
        TASK_ID,
        ref,
        TENANT_ID,
        creator ? 1 : 0);
  }

  /** Alle regulaeren Dateien unterhalb eines Ordners (leer, wenn es ihn nicht gibt). */
  private static Set<Path> filesUnder(final Path dir) {
    if (dir == null || !Files.isDirectory(dir)) {
      return Set.of();
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      return paths.filter(Files::isRegularFile).collect(Collectors.toSet());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteTreeQuietly(final Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (final IOException ignored) {
      // Aufraeumen nach bestem Bemuehen
    }
  }

  // ---- Test-Unterklasse und Kontext ---------------------------------------------------------

  /** Kommando-Service mit einmalig scharfem Haken am Seam nach dem Speichern der Dateien. */
  static class SeamCommandService extends AttachmentCommandService {

    private final AtomicReference<Runnable> afterStore = new AtomicReference<>();

    SeamCommandService(
        final UserAccessor userAccessor,
        final AttachmentTaskAccess taskAccess,
        final TaskAttachmentQueryRepository attachmentRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final AttachmentWriter writer,
        final BaseTaskRepository taskRepository,
        final TaskHistoryService historyService,
        final InboxGenerationService inboxGenerationService,
        final InboxSignalService inboxSignalService,
        final AttachmentModelMapper modelMapper,
        final AttachmentProperties properties) {
      super(
          userAccessor,
          taskAccess,
          attachmentRepository,
          userStateQueryRepository,
          participantQueryRepository,
          writer,
          taskRepository,
          historyService,
          inboxGenerationService,
          inboxSignalService,
          modelMapper,
          properties);
    }

    void armAfterStore(final Runnable hook) {
      afterStore.set(hook);
    }

    @Override
    protected void onAfterAttachmentsStoredForTest(final Long taskId) {
      // One-Shot: nur der erste Durchlauf (Upload A) loest den Haken aus.
      final Runnable hook = afterStore.getAndSet(null);
      if (hook != null) {
        hook.run();
      }
    }
  }

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
    // Pflicht: ohne AOP laeuft der KSF-AuditAspect nicht, und das zweite save derselben
    // Anhang-Instanz (Update mit Schluessel) endet im Versionskonflikt.
    AopAutoConfiguration.class,
  })
  @Import(NoOpInboxSignalConfig.class)
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "attachment-count-concurrency-it";
    }

    @Bean
    UserAccessor userAccessor() {
      return () -> {
        final UUID ref = ACTOR_PER_THREAD.get();
        return new AuthUser(ref, ref + "@brasi.de");
      };
    }

    @Bean
    AuditTimeConverter auditTimeConverter() {
      return new AuditTimeConverter("Europe/Berlin");
    }

    @Bean
    InboxGenerationService inboxGenerationService(final TaskInboxGenerationQueryRepository repo) {
      return new InboxGenerationService(repo);
    }

    @Bean
    TaskHistoryService taskHistoryService(final TaskHistoryEntryQueryRepository repo) {
      return new TaskHistoryService(repo);
    }

    @Bean
    InboxSignalService inboxSignalService(final InboxSignalPort port) {
      return new InboxSignalService(port);
    }

    @Bean
    TaskMembershipGuard membershipGuard(
        final TaskParticipantQueryRepository participantQueryRepository) {
      return new TaskMembershipGuard(participantQueryRepository);
    }

    @Bean
    AttachmentModelMapper attachmentModelMapper(final AuditTimeConverter auditTimeConverter) {
      return new AttachmentModelMapper(auditTimeConverter, "");
    }

    /** Basispfad im Temp-Verzeichnis, hoechstens 20 aktive Anhaenge je Ziel. */
    @Bean
    AttachmentProperties attachmentProperties() {
      return new AttachmentProperties(
          new AttachmentProperties.Storage(baseDir), List.of(), 20, null, null, null);
    }

    @Bean
    AttachmentContentProvider attachmentContentProvider(final AttachmentProperties properties) {
      return new FilesystemAttachmentContentProvider(properties);
    }

    @Bean
    AttachmentWriter attachmentWriter(
        final TaskAttachmentQueryRepository attachmentRepository,
        final AttachmentContentProvider attachmentContentProvider) {
      return new AttachmentWriter(attachmentRepository, attachmentContentProvider);
    }

    @Bean
    SeamCommandService seamCommandService(
        final UserAccessor userAccessor,
        final BaseTaskRepository taskRepository,
        final TaskMembershipGuard membershipGuard,
        final TaskAttachmentQueryRepository attachmentRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final AttachmentWriter writer,
        final TaskHistoryService historyService,
        final InboxGenerationService inboxGenerationService,
        final InboxSignalService inboxSignalService,
        final AttachmentModelMapper modelMapper,
        final AttachmentProperties properties) {
      return new SeamCommandService(
          userAccessor,
          new AttachmentTaskAccess(taskRepository, membershipGuard),
          attachmentRepository,
          userStateQueryRepository,
          participantQueryRepository,
          writer,
          taskRepository,
          historyService,
          inboxGenerationService,
          inboxSignalService,
          modelMapper,
          properties);
    }
  }
}
