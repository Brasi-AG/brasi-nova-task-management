package de.brasi.nova.taskmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Real-MSSQL-Repo-IT (BE-7, PA3) fuer {@link TaskHandoverQueryRepository#findVisibleHandoverTargets}
 * gegen eine Wegwerf-DB (Flyway V1..V5). Beweist:
 *
 * <ul>
 *   <li>UNIQUE(HANDOVER_ID, TO_REF): doppeltes Ziel im selben Ereignis scheitert hart.
 *   <li>Mehrere Austritts-Ereignisse derselben Aufgabe -&gt; nur das JUENGSTE sichtbare
 *       ({@code SHOULD_STAY=0}, {@code ANGELEGT_AM<=asOf}, Tie-Break ID) liefert seine Ziele (kein
 *       Vermischen mit aelteren Ereignissen).
 *   <li>Juengstes sichtbares Ereignis OHNE Ziele -&gt; leere Ziel-Liste (LEFT JOIN -&gt; toRef null).
 *   <li>SHOULD_STAY=1-Ereignisse (Stay-Handover) werden NICHT geliefert.
 *   <li>Ereignisse NACH {@code asOf} werden NICHT beruecksichtigt.
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskHandoverQueryRepositoryIT.ItConfig.class,
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
class TaskHandoverQueryRepositoryIT {

  private static final long TENANT_ID = 560L;
  private static final UUID TENANT_UUID = UUID.fromString("00000056-0000-0056-0000-000000000560");

  private static final UUID ACTOR = UUID.fromString("00000056-000a-0056-0000-00000000056a");
  private static final UUID OTHER_ACTOR = UUID.fromString("00000056-000b-0056-0000-00000000056b");

  private static final UUID T1 = UUID.fromString("00000056-0001-0056-0000-000000000561");
  private static final UUID T2 = UUID.fromString("00000056-0002-0056-0000-000000000562");
  private static final UUID T3 = UUID.fromString("00000056-0003-0056-0000-000000000563");

  // Aufgaben-IDs (fest, je Testmethode disjunkt: die Wegwerf-DB wird zwischen Methoden NICHT
  // zurueckgesetzt -> keine geteilten Task-IDs ueber Tests hinweg).
  private static final long TASK_NEWEST = 560101L;
  private static final long TASK_NO_TARGETS = 560102L;
  private static final long TASK_STAY_ONLY = 560103L;
  private static final long TASK_UNIQUE = 560104L;
  private static final long TASK_SCOPE_FUTURE = 560105L;
  private static final long TASK_SCOPE_OTHER = 560106L;

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TaskHandoverQueryRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

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
  static void seedFirma(@Autowired JdbcTemplate jt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " GUELTIG_VON) VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        TENANT_ID, TENANT_ID, TENANT_UUID, TENANT_ID);
  }

  @BeforeEach
  void setTenant() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void clearTenant() {
    TenantContextHolder.clear();
  }

  // ---- UNIQUE(HANDOVER_ID, TO_REF) ---

  @Test
  @DisplayName("unique_event_to: doppeltes Ziel im selben Ereignis scheitert hart")
  void unique_event_to_verhindert_doppeltes_ziel() {
    final long handoverId = 560201L;
    insertHandover(handoverId, TASK_UNIQUE, ACTOR, false, LocalDateTime.of(2026, 1, 1, 10, 0));
    insertTarget(560301L, handoverId, T1);
    assertThatThrownBy(() -> insertTarget(560302L, handoverId, T1))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("UNIQUE");
  }

  // ---- Mehrere Ereignisse -> juengstes sichtbares ---

  @Test
  @DisplayName(
      "mehrere_events_juengstes_sichtbares: zwei SHOULD_STAY=0-Ereignisse derselben Aufgabe"
          + " -> nur die Ziele des juengsten (ANGELEGT_AM, Tie-Break ID)")
  void mehrere_events_juengstes_sichtbares() {
    final long older = 560210L;
    final long newer = 560211L;
    // Aelteres Ereignis mit Ziel T1.
    insertHandover(older, TASK_NEWEST, ACTOR, false, LocalDateTime.of(2026, 2, 1, 9, 0));
    insertTarget(560310L, older, T1);
    // Juengeres Ereignis mit Zielen T2, T3.
    insertHandover(newer, TASK_NEWEST, ACTOR, false, LocalDateTime.of(2026, 2, 2, 9, 0));
    insertTarget(560311L, newer, T2);
    insertTarget(560312L, newer, T3);

    final LocalDateTime asOf = LocalDateTime.of(2026, 3, 1, 0, 0);
    final List<HandoverTargetRow> rows =
        repository.findVisibleHandoverTargets(List.of(TASK_NEWEST), ACTOR, asOf);

    assertThat(rows).extracting(HandoverTargetRow::taskId).containsOnly(TASK_NEWEST);
    assertThat(rows).extracting(HandoverTargetRow::toRef).containsExactlyInAnyOrder(T2, T3);
    assertThat(rows).extracting(HandoverTargetRow::toRef).doesNotContain(T1);
  }

  // ---- Juengstes ohne Ziele -> [] ---

  @Test
  @DisplayName(
      "juengstes_ohne_ziele_leer: juengstes sichtbares Ereignis OHNE Ziele -> eine Zeile toRef=null"
          + " (Service leitet [] ab), kein Vermischen mit aelterem Ereignis")
  void juengstes_ohne_ziele_leer() {
    final long older = 560220L;
    final long newerEmpty = 560221L;
    insertHandover(older, TASK_NO_TARGETS, ACTOR, false, LocalDateTime.of(2026, 2, 1, 9, 0));
    insertTarget(560320L, older, T1);
    // Juengeres Selbst-Handover ohne Ziele.
    insertHandover(newerEmpty, TASK_NO_TARGETS, ACTOR, false, LocalDateTime.of(2026, 2, 5, 9, 0));

    final LocalDateTime asOf = LocalDateTime.of(2026, 3, 1, 0, 0);
    final List<HandoverTargetRow> rows =
        repository.findVisibleHandoverTargets(List.of(TASK_NO_TARGETS), ACTOR, asOf);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).taskId()).isEqualTo(TASK_NO_TARGETS);
    assertThat(rows.get(0).toRef()).as("LEFT JOIN ohne Ziel -> toRef null").isNull();
  }

  // ---- SHOULD_STAY=1 wird ignoriert ---

  @Test
  @DisplayName("stay_handover_nicht_sichtbar: SHOULD_STAY=1-Ereignisse liefern keine Zeilen")
  void stay_handover_nicht_sichtbar() {
    final long stay = 560230L;
    insertHandover(stay, TASK_STAY_ONLY, ACTOR, true, LocalDateTime.of(2026, 2, 1, 9, 0));
    insertTarget(560330L, stay, T1);

    final LocalDateTime asOf = LocalDateTime.of(2026, 3, 1, 0, 0);
    final List<HandoverTargetRow> rows =
        repository.findVisibleHandoverTargets(List.of(TASK_STAY_ONLY), ACTOR, asOf);

    assertThat(rows).as("SHOULD_STAY=1 ist kein Austritt -> keine Zeilen").isEmpty();
  }

  // ---- asOf-Grenze + Actor-Scope ---

  @Test
  @DisplayName(
      "asof_und_actor_scope: Ereignis nach asOf UND Ereignis eines anderen Akteurs liefern nichts")
  void asof_und_actor_scope() {
    final long futureEvent = 560240L;
    insertHandover(futureEvent, TASK_SCOPE_FUTURE, ACTOR, false, LocalDateTime.of(2026, 12, 31, 9, 0));
    insertTarget(560340L, futureEvent, T1);
    final long otherActorEvent = 560241L;
    insertHandover(otherActorEvent, TASK_SCOPE_OTHER, OTHER_ACTOR, false, LocalDateTime.of(2026, 1, 1, 9, 0));
    insertTarget(560341L, otherActorEvent, T2);

    // asOf VOR dem Future-Event: Future-Event unsichtbar; anderes-Akteur-Event nie im ACTOR-Scope.
    final LocalDateTime asOf = LocalDateTime.of(2026, 6, 1, 0, 0);
    final List<HandoverTargetRow> rows =
        repository.findVisibleHandoverTargets(
            List.of(TASK_SCOPE_FUTURE, TASK_SCOPE_OTHER), ACTOR, asOf);

    assertThat(rows)
        .as("weder Future-Event (nach asOf) noch Fremd-Akteur-Event duerfen erscheinen")
        .isEmpty();
  }

  // ---- Seed-Helfer ---

  private void insertHandover(
      final long id,
      final long taskId,
      final UUID fromRef,
      final boolean shouldStay,
      final LocalDateTime angelegtAm) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_HANDOVER]"
            + " (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, FROM_REF, SHOULD_STAY,"
            + "  ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, 'it-seed', ?)",
        id, TENANT_ID, taskId, fromRef, shouldStay ? 1 : 0, angelegtAm);
  }

  private void insertTarget(final long id, final long handoverId, final UUID toRef) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_HANDOVER_TARGET]"
            + " (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, HANDOVER_ID, TO_REF,"
            + "  ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'it-seed', GETDATE())",
        id, TENANT_ID, handoverId, toRef);
  }

  // ---- IT-Konfiguration ---

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
      return () -> "handover-query-it";
    }
  }
}
