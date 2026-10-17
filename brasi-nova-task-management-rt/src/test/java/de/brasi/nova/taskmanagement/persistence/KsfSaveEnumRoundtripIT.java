package de.brasi.nova.taskmanagement.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.repository.BaseTaskParticipantRepository;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.time.LocalDateTime;
import java.util.Optional;
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
 * KSF-save()-Enum-Roundtrip-IT (Codex-Auflagenpflicht aus Task 5-Regate): beweist, dass
 * {@link BaseTaskParticipantRepository#save(Object)} via KSF-{@code NovaRepository} korrekt
 * persistiert und dass das Entity-Enum-Feld exakt zurueckkommt UND die rohe DB-Spalte den
 * {@code name()}-String haelt.
 *
 * <p>Prueft alle Kombinationen aus {@link ParticipantKind} (PERSON, TEAM) und
 * {@link ExitKind} (HANDOVER, UNSUBSCRIBED).
 *
 * <p>Infrastruktur: Wegwerf-MSSQL-DB (wie {@code EnumRoundtripIT}), Flyway-V1 fuer vollstaendiges
 * Schema inkl. PROD_FIRMA-Seed, KSF-Persistence-Context (SYS_SEQUENCE wird automatisch via
 * {@code SequenceSchemaManager.ensureSchemaExists()} angelegt).
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = KsfSaveEnumRoundtripIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      // Flyway: Schema via V1 aufbauen
      "spring.flyway.enabled=true",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0",
      "spring.flyway.locations=classpath:db/migration",
      // KSF: Dialekt explizit + kein DDL-Auto (Flyway ist allein zustaendig)
      "ksf.persistence.dialect=MSSQL",
      "ksf.persistence.ddl-auto=none",
      // Modul-Registrierung deaktivieren (kein Gateway)
      "ksf.module-registration.enabled=false",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class KsfSaveEnumRoundtripIT {

  /** Mandant fuer alle Roundtrip-Tests (FK auf PROD_FIRMA). */
  private static final long TENANT_ID = 700L;

  /** Feste Task-ID (FK-Vorbedingung; direkt per JDBC eingefuegt). */
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
  private BaseTaskParticipantRepository repository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    // PROD_FIRMA (FK fuer TASK.TENANT_ID + TASK_PARTICIPANT.TENANT_ID)
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, CAST(GETDATE() AS DATE))",
        TENANT_ID, TENANT_ID, TENANT_ID);

    // TASK-Zeile (FK fuer TASK_PARTICIPANT.TASK_ID); Seed-IDs 101/201/301 via Flyway V1
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, "
            + " TITLE, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, "
            + " LAST_ACTIVITY_AT, COMMENT_COUNT) "
            + "VALUES (?, 'ACTIVE', 'ksf-save-it', GETDATE(), NEWID(), 1, ?, "
            + " 'IT-Save-Test-Task', 101, 201, 301, ?, GETDATE(), 0)",
        TASK_ID, TASK_ID, TENANT_ID, TENANT_ID);
  }

  @BeforeEach
  void setTenantContext() {
    // KSF TenantQueryAspect erfordert gesetzten Mandantenkontext fuer Lese-/Schreib-Zugriffe
    TenantContextHolder.setTenant(new TenantInfo(UUID.randomUUID(), TENANT_ID));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  // ---- 1. ParticipantKind.PERSON -- kein ExitKind --------------------------------------------------

  @Test
  @DisplayName("ksf_save_participant_kind_person_roundtrip: Entity-Feld und DB-Spalte identisch")
  void ksf_save_participant_kind_person_roundtrip() {
    TaskParticipant entity = newParticipant(ParticipantKind.PERSON, null, null);
    TaskParticipant saved = repository.save(entity);

    assertThat(saved.getId())
        .as("KSF save() muss eine echte ID vergeben (nicht null/0)")
        .isNotNull()
        .isGreaterThan(0L);

    Optional<TaskParticipant> loaded = repository.findById(saved.getId());
    assertThat(loaded).as("findById muss die gespeicherte Entity zurueckliefern").isPresent();

    TaskParticipant found = loaded.get();
    assertThat(found.getParticipantKind())
        .as("Entity-Enum-Feld muss exakt zurueckkommen")
        .isEqualTo(ParticipantKind.PERSON);
    assertThat(found.getExitKind())
        .as("exitKind soll null sein (nicht gesetzt)")
        .isNull();

    assertRawColumn(
        saved.getId(), "PARTICIPANT_KIND", ParticipantKind.PERSON.name(), "EXIT_KIND", null);
  }

  // ---- 2. ParticipantKind.TEAM -- kein ExitKind ----------------------------------------------------

  @Test
  @DisplayName("ksf_save_participant_kind_team_roundtrip: Entity-Feld und DB-Spalte identisch")
  void ksf_save_participant_kind_team_roundtrip() {
    TaskParticipant entity = newParticipant(ParticipantKind.TEAM, null, null);
    TaskParticipant saved = repository.save(entity);

    assertThat(saved.getId()).isNotNull().isGreaterThan(0L);

    Optional<TaskParticipant> loaded = repository.findById(saved.getId());
    assertThat(loaded).isPresent();

    assertThat(loaded.get().getParticipantKind()).isEqualTo(ParticipantKind.TEAM);
    assertThat(loaded.get().getExitKind()).isNull();

    assertRawColumn(saved.getId(), "PARTICIPANT_KIND", ParticipantKind.TEAM.name(), "EXIT_KIND", null);
  }

  // ---- 3. ExitKind.HANDOVER ------------------------------------------------------------------------

  @Test
  @DisplayName("ksf_save_exit_kind_handover_roundtrip: Entity-Feld und DB-Spalte identisch")
  void ksf_save_exit_kind_handover_roundtrip() {
    TaskParticipant entity =
        newParticipant(ParticipantKind.PERSON, ExitKind.HANDOVER, LocalDateTime.now());
    TaskParticipant saved = repository.save(entity);

    assertThat(saved.getId()).isNotNull().isGreaterThan(0L);

    Optional<TaskParticipant> loaded = repository.findById(saved.getId());
    assertThat(loaded).isPresent();

    assertThat(loaded.get().getParticipantKind()).isEqualTo(ParticipantKind.PERSON);
    assertThat(loaded.get().getExitKind()).isEqualTo(ExitKind.HANDOVER);

    assertRawColumn(
        saved.getId(), "PARTICIPANT_KIND", ParticipantKind.PERSON.name(), "EXIT_KIND",
        ExitKind.HANDOVER.name());
  }

  // ---- 4. ExitKind.UNSUBSCRIBED --------------------------------------------------------------------

  @Test
  @DisplayName("ksf_save_exit_kind_unsubscribed_roundtrip: Entity-Feld und DB-Spalte identisch")
  void ksf_save_exit_kind_unsubscribed_roundtrip() {
    TaskParticipant entity =
        newParticipant(ParticipantKind.TEAM, ExitKind.UNSUBSCRIBED, LocalDateTime.now());
    TaskParticipant saved = repository.save(entity);

    assertThat(saved.getId()).isNotNull().isGreaterThan(0L);

    Optional<TaskParticipant> loaded = repository.findById(saved.getId());
    assertThat(loaded).isPresent();

    assertThat(loaded.get().getParticipantKind()).isEqualTo(ParticipantKind.TEAM);
    assertThat(loaded.get().getExitKind()).isEqualTo(ExitKind.UNSUBSCRIBED);

    assertRawColumn(
        saved.getId(), "PARTICIPANT_KIND", ParticipantKind.TEAM.name(), "EXIT_KIND",
        ExitKind.UNSUBSCRIBED.name());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------------

  /**
   * Erstellt eine neue, ungespeicherte {@link TaskParticipant}-Instanz mit dem angegebenen
   * {@link ParticipantKind} und optionalem {@link ExitKind}.
   *
   * <p>Wichtig: {@code tenantId} muss explizit gesetzt werden — KSF schreibt die
   * {@code TENANT_ID}-Spalte aus dem Entity-Feld, NICHT aus dem {@code TenantContextHolder}.
   * Der {@code TenantContextHolder} wird nur fuer den Lese-Scope (Criteria/findById) benoetigt.
   */
  private TaskParticipant newParticipant(
      ParticipantKind kind, ExitKind exitKind, LocalDateTime exitAt) {
    TaskParticipant p = new TaskParticipant();
    // Mandantenbezug: TENANT_ID-Spalte wird aus dem Entity-Feld geschrieben
    p.setTenantId(TENANT_ID);
    p.setTaskId(TASK_ID);
    p.setParticipantRef(UUID.randomUUID());
    p.setParticipantKind(kind);
    p.setParticipantTenantId(TENANT_ID);
    p.setCreatorFlag(false);
    p.setAssigneeFlag(false);
    p.setParticipantFlag(true);
    p.setExitKind(exitKind);
    p.setExitAt(exitAt);
    return p;
  }

  /**
   * Prueft die rohen DB-Spaltenwerte fuer PARTICIPANT_KIND und EXIT_KIND direkt per JDBC.
   *
   * @param id            gespeicherte Entity-ID
   * @param pkCol         Spaltenname PARTICIPANT_KIND
   * @param expectedPk    erwarteter PARTICIPANT_KIND-Wert (name()-String)
   * @param ekCol         Spaltenname EXIT_KIND
   * @param expectedEk    erwarteter EXIT_KIND-Wert (name()-String oder null)
   */
  private void assertRawColumn(
      long id,
      String pkCol,
      String expectedPk,
      String ekCol,
      String expectedEk) {
    String actualPk =
        jdbcTemplate.queryForObject(
            "SELECT " + pkCol + " FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?",
            String.class,
            id);
    assertThat(actualPk)
        .as("Rohe DB-Spalte %s muss den name()-String enthalten", pkCol)
        .isEqualTo(expectedPk);

    String actualEk =
        jdbcTemplate.queryForObject(
            "SELECT " + ekCol + " FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?",
            String.class,
            id);
    assertThat(actualEk)
        .as("Rohe DB-Spalte %s muss '%s' enthalten", ekCol, expectedEk)
        .isEqualTo(expectedEk);
  }

  // ---- IT-Konfigurations-Context ------------------------------------------------------------------

  /**
   * Minimaler Spring-Kontext: DataSource + Flyway (Schema) + KSF-Persistence (Repositories +
   * SYS_SEQUENCE). Kein Web-Layer, keine Security, kein Gateway.
   */
  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository"
      })
  @EnableTransactionManagement
  @EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository"
      })
  @ImportAutoConfiguration({
    // DataSource + JDBC
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    // Schema via Flyway V1
    FlywayAutoConfiguration.class,
    // KSF-Persistence: NovaRepository-Proxies, SequenceSchemaManager (-> SYS_SEQUENCE auto-create)
    KsfPersistenceAutoConfiguration.class,
  })
  static class ItConfig {

    /** Pflichtbean fuer KSF-Audit-Felder (ANGELEGT_VON, AKTUALISIERT_VON etc.). */
    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "ksf-save-enum-it";
    }
  }
}
