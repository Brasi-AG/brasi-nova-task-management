package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.division.DivisionFetchResult.Members;
import de.brasi.nova.taskmanagement.division.DivisionFetchResult.NotFound;
import de.brasi.nova.taskmanagement.division.model.DivisionMemberPayload;
import de.brasi.nova.taskmanagement.division.model.DivisionMembersPayload;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-MSSQL-IT fuer {@link DivisionProjectionService} (Intake #190, Task 3): Kopf und Mitglieder
 * der lokalen Abteilungs-Projektion ({@code TASK_ABTEILUNG}, {@code TASK_ABTEILUNG_MITGLIED}).
 *
 * <p>Belegt: Anlegen beim ersten Stand, physisches Ersetzen der Mitglieder, „spaeterer Abruf
 * gewinnt“ ueber {@code ABGERUFEN_AM}, Deaktivieren bei 404 (nur fuer bekannte Abteilungen),
 * Reaktivieren, Mandantentrennung und Erhalt des Namens, wenn application keinen liefert.
 *
 * <p>{@code apply} verlangt eine laufende Transaktion ({@code MANDATORY}); jeder Aufruf laeuft in
 * einem eigenen {@link TransactionTemplate} unter dem Mandantenkontext. Abrufstart-Zeiten sind
 * feste {@link LocalDateTime}-Werte (t1 &lt; t2 &lt; t3), keine Systemzeit. Jeder Test nutzt eigene
 * Abteilungs-UUIDs, weil die Methoden der Klasse eine Wegwerf-DB teilen.
 *
 * <p>Kontext wie {@code TaskRowLockIT} (Paket {@code repository} plus der gezielt importierte
 * Service, kein Scan des Pakets {@code division}). Uebersprungen, wenn kein MSSQL-Zugang
 * konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = DivisionProjectionIT.ItConfig.class,
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
class DivisionProjectionIT {

  private static final long TENANT_A_ID = 561L;
  private static final UUID TENANT_A_UUID = UUID.fromString("00000056-0000-0056-0000-000000000561");
  private static final long TENANT_B_ID = 562L;
  private static final UUID TENANT_B_UUID = UUID.fromString("00000056-0000-0056-0000-000000000562");

  private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 20, 10, 0, 0);
  private static final LocalDateTime T2 = T1.plusMinutes(5);
  private static final LocalDateTime T3 = T2.plusMinutes(5);

  private static final String REV1 = "1".repeat(64);
  private static final String REV2 = "2".repeat(64);
  private static final String REV3 = "3".repeat(64);

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private DivisionProjectionService projectionService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

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
  static void seedTenants(@Autowired JdbcTemplate jt) {
    seedTenant(jt, TENANT_A_ID, TENANT_A_UUID);
    seedTenant(jt, TENANT_B_ID, TENANT_B_UUID);
  }

  private static void seedTenant(final JdbcTemplate jt, final long id, final UUID uuid) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id,
        id,
        uuid.toString(),
        id);
  }

  @Test
  @DisplayName("erster Stand legt einen ACTIVE-Kopf mit Revision und drei Mitgliederzeilen an")
  void i190_first_members_create_division_and_rows() {
    final UUID ref = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();

    final DivisionApplyOutcome outcome =
        apply(
            TENANT_A_ID,
            TENANT_A_UUID,
            ref,
            members(ref, null, REV1, coreMember(a), member(b), member(c)),
            T1);

    assertThat(outcome).isEqualTo(DivisionApplyOutcome.UPDATED);
    final Map<String, Object> head = singleHead(TENANT_A_ID, ref);
    assertThat(head.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(trimmed(head.get("REVISION"))).isEqualTo(REV1);
    assertThat(fetchedAt(head)).isEqualTo(T1);
    assertThat(memberRefs(TENANT_A_ID, ref)).containsExactlyInAnyOrder(a, b, c);

    final Map<String, Object> rowA =
        jdbcTemplate.queryForMap(
            "SELECT m.TENANT_ID, m.ANZEIGENAME, m.VORNAME, m.NACHNAME, m.LOGIN_NAME,"
                + " m.KERNMITGLIED FROM [dbo].[TASK_ABTEILUNG_MITGLIED] m"
                + " WHERE m.ABTEILUNG_ID = ? AND m.MITARBEITER_REF = ?",
            head.get("ID"),
            a.toString());
    assertThat(((Number) rowA.get("TENANT_ID")).longValue()).isEqualTo(TENANT_A_ID);
    assertThat(rowA.get("ANZEIGENAME")).isEqualTo("Anzeige " + a);
    assertThat(rowA.get("VORNAME")).isEqualTo("Vorname");
    assertThat(rowA.get("NACHNAME")).isEqualTo("Nachname");
    assertThat(rowA.get("LOGIN_NAME")).isEqualTo("login-" + a);
    assertThat(rowA.get("KERNMITGLIED")).isEqualTo(Boolean.TRUE);
  }

  @Test
  @DisplayName("neuer Stand ersetzt die Mitglieder physisch: {a,b,c} dann {b,d} ergibt {b,d}")
  void i190_new_state_replaces_members() {
    final UUID ref = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();
    final UUID d = UUID.randomUUID();
    apply(
        TENANT_A_ID,
        TENANT_A_UUID,
        ref,
        members(ref, null, REV1, member(a), member(b), member(c)),
        T1);

    final DivisionApplyOutcome outcome =
        apply(TENANT_A_ID, TENANT_A_UUID, ref, members(ref, null, REV2, member(b), member(d)), T2);

    assertThat(outcome).isEqualTo(DivisionApplyOutcome.UPDATED);
    final Map<String, Object> head = singleHead(TENANT_A_ID, ref);
    assertThat(trimmed(head.get("REVISION"))).isEqualTo(REV2);
    assertThat(fetchedAt(head)).isEqualTo(T2);
    assertThat(memberRefs(TENANT_A_ID, ref)).containsExactlyInAnyOrder(b, d);
    assertThat(memberRowCount(head.get("ID"))).as("keine Leichen alter Mitglieder").isEqualTo(2);
  }

  @Test
  @DisplayName("ein aelterer oder gleich alter Abruf ueberschreibt den neueren Stand nicht (STALE)")
  void i190_older_fetch_does_not_overwrite_newer() {
    final UUID ref = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();
    assertThat(
            apply(
                TENANT_A_ID,
                TENANT_A_UUID,
                ref,
                members(ref, "Neu", REV2, member(a), member(b)),
                T2))
        .isEqualTo(DivisionApplyOutcome.UPDATED);

    final DivisionApplyOutcome older =
        apply(TENANT_A_ID, TENANT_A_UUID, ref, members(ref, "Alt", REV1, member(c)), T1);
    final DivisionApplyOutcome sameStart =
        apply(TENANT_A_ID, TENANT_A_UUID, ref, members(ref, "Alt", REV1, member(c)), T2);
    final DivisionApplyOutcome olderNotFound =
        apply(TENANT_A_ID, TENANT_A_UUID, ref, new NotFound(), T1);

    assertThat(older).isEqualTo(DivisionApplyOutcome.STALE);
    assertThat(sameStart).as("ABGERUFEN_AM >= Abrufstart").isEqualTo(DivisionApplyOutcome.STALE);
    assertThat(olderNotFound).isEqualTo(DivisionApplyOutcome.STALE);
    final Map<String, Object> head = singleHead(TENANT_A_ID, ref);
    assertThat(head.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(trimmed(head.get("REVISION"))).isEqualTo(REV2);
    assertThat(head.get("NAME")).isEqualTo("Neu");
    assertThat(fetchedAt(head)).isEqualTo(T2);
    assertThat(memberRefs(TENANT_A_ID, ref)).containsExactlyInAnyOrder(a, b);
  }

  @Test
  @DisplayName("404 deaktiviert eine bekannte Abteilung, leert die Mitglieder, REVISION null")
  void i190_not_found_deactivates_and_clears() {
    final UUID ref = UUID.randomUUID();
    apply(
        TENANT_A_ID,
        TENANT_A_UUID,
        ref,
        members(ref, null, REV1, member(UUID.randomUUID()), member(UUID.randomUUID())),
        T1);

    final DivisionApplyOutcome outcome = apply(TENANT_A_ID, TENANT_A_UUID, ref, new NotFound(), T2);

    assertThat(outcome).isEqualTo(DivisionApplyOutcome.DEACTIVATED);
    final Map<String, Object> head = singleHead(TENANT_A_ID, ref);
    assertThat(head.get("ENTITY_STATUS")).isEqualTo("DEACTIVE");
    assertThat(head.get("REVISION")).isNull();
    assertThat(fetchedAt(head)).isEqualTo(T2);
    assertThat(memberRowCount(head.get("ID"))).isZero();
    assertThat(currentRevision(TENANT_A_ID, TENANT_A_UUID, ref)).isEmpty();
  }

  @Test
  @DisplayName("404 fuer eine nie gesehene Abteilung legt nichts an (IGNORED)")
  void i190_not_found_for_unknown_division_creates_nothing() {
    final UUID ref = UUID.randomUUID();

    final DivisionApplyOutcome outcome = apply(TENANT_A_ID, TENANT_A_UUID, ref, new NotFound(), T1);

    assertThat(outcome).isEqualTo(DivisionApplyOutcome.IGNORED);
    assertThat(heads(TENANT_A_ID, ref)).isEmpty();
  }

  @Test
  @DisplayName("ein spaeterer Stand nach 404 reaktiviert denselben Kopf mit neuen Mitgliedern")
  void i190_reactivates_after_not_found() {
    final UUID ref = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();
    apply(TENANT_A_ID, TENANT_A_UUID, ref, members(ref, null, REV1, member(a)), T1);
    final Object headId = singleHead(TENANT_A_ID, ref).get("ID");
    apply(TENANT_A_ID, TENANT_A_UUID, ref, new NotFound(), T2);

    final DivisionApplyOutcome outcome =
        apply(TENANT_A_ID, TENANT_A_UUID, ref, members(ref, null, REV3, member(b), member(c)), T3);

    assertThat(outcome).isEqualTo(DivisionApplyOutcome.UPDATED);
    final Map<String, Object> head = singleHead(TENANT_A_ID, ref);
    assertThat(head.get("ID")).as("derselbe Kopf, keine zweite Zeile").isEqualTo(headId);
    assertThat(head.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(trimmed(head.get("REVISION"))).isEqualTo(REV3);
    assertThat(fetchedAt(head)).isEqualTo(T3);
    assertThat(memberRefs(TENANT_A_ID, ref)).containsExactlyInAnyOrder(b, c);
    assertThat(currentRevision(TENANT_A_ID, TENANT_A_UUID, ref)).contains(REV3);
  }

  @Test
  @DisplayName(
      "gleiche Abteilungs-UUID in Mandant 561 und 562 ergibt zwei getrennte Koepfe; Revision,"
          + " Mitglieder und 404 wirken je Mandant")
  void i190_projection_is_tenant_scoped() {
    final UUID ref = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();

    apply(TENANT_A_ID, TENANT_A_UUID, ref, members(ref, null, REV1, member(a)), T1);
    apply(TENANT_B_ID, TENANT_B_UUID, ref, members(ref, null, REV2, member(b), member(c)), T1);

    final Map<String, Object> headA = singleHead(TENANT_A_ID, ref);
    final Map<String, Object> headB = singleHead(TENANT_B_ID, ref);
    assertThat(headA.get("ID")).isNotEqualTo(headB.get("ID"));
    assertThat(currentRevision(TENANT_A_ID, TENANT_A_UUID, ref)).contains(REV1);
    assertThat(currentRevision(TENANT_B_ID, TENANT_B_UUID, ref)).contains(REV2);
    assertThat(memberRefs(TENANT_A_ID, ref)).containsExactly(a);
    assertThat(memberRefs(TENANT_B_ID, ref)).containsExactlyInAnyOrder(b, c);
    assertThat(currentRevision(TENANT_A_ID, TENANT_A_UUID, UUID.randomUUID())).isEmpty();

    // 404 in Mandant 561 laesst Mandant 562 unberuehrt
    assertThat(apply(TENANT_A_ID, TENANT_A_UUID, ref, new NotFound(), T2))
        .isEqualTo(DivisionApplyOutcome.DEACTIVATED);
    assertThat(singleHead(TENANT_B_ID, ref).get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(memberRefs(TENANT_B_ID, ref)).containsExactlyInAnyOrder(b, c);
    assertThat(currentRevision(TENANT_B_ID, TENANT_B_UUID, ref)).contains(REV2);
  }

  @Test
  @DisplayName("NAME bleibt erhalten, wenn ein spaeterer Stand keinen divisionName liefert")
  void i190_division_name_kept_when_absent() {
    final UUID ref = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    apply(TENANT_A_ID, TENANT_A_UUID, ref, members(ref, "Vertrieb", REV1, member(a)), T1);
    assertThat(singleHead(TENANT_A_ID, ref).get("NAME")).isEqualTo("Vertrieb");

    final DivisionApplyOutcome outcome =
        apply(TENANT_A_ID, TENANT_A_UUID, ref, members(ref, null, REV2, member(a), member(b)), T2);

    assertThat(outcome).isEqualTo(DivisionApplyOutcome.UPDATED);
    final Map<String, Object> head = singleHead(TENANT_A_ID, ref);
    assertThat(head.get("NAME")).isEqualTo("Vertrieb");
    assertThat(trimmed(head.get("REVISION"))).isEqualTo(REV2);
    assertThat(memberRefs(TENANT_A_ID, ref)).containsExactlyInAnyOrder(a, b);
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  /** Wendet {@code result} im Mandantenkontext in einer eigenen Transaktion an. */
  private DivisionApplyOutcome apply(
      final long tenantId,
      final UUID tenantUuid,
      final UUID divisionRef,
      final DivisionFetchResult result,
      final LocalDateTime fetchStartedAt) {
    return inTenantTx(
        tenantId, tenantUuid, () -> projectionService.apply(divisionRef, result, fetchStartedAt));
  }

  private Optional<String> currentRevision(
      final long tenantId, final UUID tenantUuid, final UUID divisionRef) {
    return inTenantTx(tenantId, tenantUuid, () -> projectionService.currentRevision(divisionRef))
        .map(String::trim);
  }

  private <T> T inTenantTx(final long tenantId, final UUID tenantUuid, final Supplier<T> body) {
    TenantContextHolder.setTenant(new TenantInfo(tenantUuid, tenantId));
    try {
      return new TransactionTemplate(transactionManager).execute(status -> body.get());
    } finally {
      TenantContextHolder.clear();
    }
  }

  private static DivisionFetchResult members(
      final UUID divisionRef,
      final String divisionName,
      final String revision,
      final DivisionMemberPayload... members) {
    return new Members(
        new DivisionMembersPayload(divisionRef, divisionName, revision, Arrays.asList(members)));
  }

  private static DivisionMemberPayload member(final UUID employeeId) {
    return new DivisionMemberPayload(
        employeeId, "Anzeige " + employeeId, "Vorname", "Nachname", "login-" + employeeId, false);
  }

  private static DivisionMemberPayload coreMember(final UUID employeeId) {
    return new DivisionMemberPayload(
        employeeId, "Anzeige " + employeeId, "Vorname", "Nachname", "login-" + employeeId, true);
  }

  private List<Map<String, Object>> heads(final long tenantId, final UUID divisionRef) {
    return jdbcTemplate.queryForList(
        "SELECT ID, ENTITY_STATUS, NAME, REVISION, ABGERUFEN_AM FROM [dbo].[TASK_ABTEILUNG]"
            + " WHERE TENANT_ID = ? AND ABTEILUNG_REF = ?",
        tenantId,
        divisionRef.toString());
  }

  private Map<String, Object> singleHead(final long tenantId, final UUID divisionRef) {
    final List<Map<String, Object>> rows = heads(tenantId, divisionRef);
    assertThat(rows).as("genau ein Kopf je Mandant und Abteilung").hasSize(1);
    return rows.get(0);
  }

  private List<UUID> memberRefs(final long tenantId, final UUID divisionRef) {
    return jdbcTemplate
        .queryForList(
            "SELECT CAST(m.MITARBEITER_REF AS nvarchar(36)) FROM [dbo].[TASK_ABTEILUNG_MITGLIED] m"
                + " JOIN [dbo].[TASK_ABTEILUNG] a ON a.ID = m.ABTEILUNG_ID"
                + " WHERE a.TENANT_ID = ? AND a.ABTEILUNG_REF = ? AND m.TENANT_ID = a.TENANT_ID",
            String.class,
            tenantId,
            divisionRef.toString())
        .stream()
        .map(UUID::fromString)
        .toList();
  }

  private int memberRowCount(final Object divisionId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_ABTEILUNG_MITGLIED] WHERE ABTEILUNG_ID = ?",
        Integer.class,
        divisionId);
  }

  private static LocalDateTime fetchedAt(final Map<String, Object> head) {
    final Object value = head.get("ABGERUFEN_AM");
    return value instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
  }

  /** REVISION ist {@code char(64)}; Vergleich ohne Fuellzeichen. */
  private static String trimmed(final Object value) {
    return value == null ? null : value.toString().trim();
  }

  // ---- IT-Konfigurations-Context --------------------------------------------------------------

  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
      })
  @EnableTransactionManagement
  @EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
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
  @Import(DivisionProjectionService.class)
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "division-projection-it";
    }
  }
}
