package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.repository.TaskEventReceiptQueryRepository;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-MSSQL-IT fuer {@link TaskEventReceiptStore} und {@link TaskEventReceiptQueryRepository}
 * (Intake #190, Task 4): die Quittungen in {@code TASK_EREIGNIS_QUITTUNG} als Inbox nach dem
 * Vertrag von {@code NovaEventIdempotencyStore}.
 *
 * <p>Belegt: Claim vor dem Handler und genau ein Handler-Lauf je Ereignis, Rollback des Claims bei
 * Handler-Fehler (gleiche Ausnahme-Instanz), ein Gewinner bei gleichzeitigem Claim, {@code
 * markDead} in eigener Transaktion ({@code REQUIRES_NEW}) mit auf 900 Zeichen gekuerztem Grund,
 * Trennung je Mandant und die mandantenuebergreifende Aufbewahrungsbereinigung ({@code
 * deleteOlderThan} unter {@code runAsSystem}).
 *
 * <p>{@code executeOnce} laeuft {@code MANDATORY}: jeder Aufruf liegt in einem eigenen {@link
 * TransactionTemplate} unter dem Mandantenkontext; liefert er {@code false}, setzt der Test die
 * Transaktion wie der Processor auf rollback-only. Jeder Test nutzt eigene Ereignis-IDs, weil die
 * Methoden der Klasse eine Wegwerf-DB teilen.
 *
 * <p>Kontext wie {@code DivisionProjectionIT} (Pakete {@code entity} und {@code repository}, der
 * Store gezielt importiert). Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 *
 * <p>Angenommene Signaturen: Bean {@code TaskEventReceiptStore} ohne Abhaengigkeiten ausserhalb von
 * {@code repository}; {@code boolean executeOnce(DivisionSignal, Runnable)}, {@code boolean
 * isProcessed(DivisionSignal)}, {@code void markDead(DivisionSignal, String)}; {@code
 * TaskEventReceiptQueryRepository#existsByEvent(UUID, UUID)} und {@code
 * #deleteOlderThan(LocalDateTime)} ({@code int}).
 */
@SpringBootTest(
    classes = TaskEventReceiptStoreIT.ItConfig.class,
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
class TaskEventReceiptStoreIT {

  private static final long TENANT_A_ID = 563L;
  private static final UUID TENANT_A_UUID = UUID.fromString("00000056-0000-0056-0000-000000000563");
  private static final long TENANT_B_ID = 564L;
  private static final UUID TENANT_B_UUID = UUID.fromString("00000056-0000-0056-0000-000000000564");

  private static final UUID DIV = UUID.fromString("00000056-0000-0056-dddd-000000000563");
  private static final String REV = "c".repeat(64);

  /** Runden des Zwei-Thread-Claims, je Runde mit eigener Ereignis-ID. */
  private static final int RACE_ROUNDS = 5;

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TaskEventReceiptStore store;
  @Autowired private TaskEventReceiptQueryRepository receiptRepository;
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
  @DisplayName("zweimal executeOnce fuer dasselbe Ereignis: true, dann false; Handler genau einmal")
  void i190_receipt_runs_handler_once() {
    final DivisionSignal signal = signal(TENANT_A_UUID);
    final AtomicInteger runs = new AtomicInteger();
    assertThat(isProcessed(TENANT_A_ID, TENANT_A_UUID, signal)).isFalse();

    final boolean first = claim(TENANT_A_ID, TENANT_A_UUID, signal, runs::incrementAndGet);
    final boolean second = claim(TENANT_A_ID, TENANT_A_UUID, signal, runs::incrementAndGet);

    assertThat(first).isTrue();
    assertThat(second).as("Duplikat ohne Handler").isFalse();
    assertThat(runs).hasValue(1);
    assertThat(isProcessed(TENANT_A_ID, TENANT_A_UUID, signal)).isTrue();
    final Map<String, Object> row = singleReceipt(TENANT_A_UUID, signal.eventId());
    assertThat(((Number) row.get("TENANT_ID")).longValue()).isEqualTo(TENANT_A_ID);
    assertThat(row.get("STATUS")).isEqualTo("PROCESSED");
    assertThat(uuid(row.get("ABTEILUNG_REF"))).isEqualTo(DIV);
    assertThat(row.get("FEHLER_MELDUNG")).isNull();
  }

  @Test
  @DisplayName("Handler wirft: dieselbe Ausnahme kommt durch, die Quittung wird zurueckgerollt")
  void i190_handler_failure_rolls_back_claim() {
    final DivisionSignal signal = signal(TENANT_A_UUID);
    final IllegalStateException failure = new IllegalStateException("Handler-Testfehler");

    assertThatThrownBy(
            () ->
                claim(
                    TENANT_A_ID,
                    TENANT_A_UUID,
                    signal,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    assertThat(receiptCount(TENANT_A_UUID, signal.eventId())).isZero();
    // Gegenprobe: ohne Quittung laeuft der Handler beim naechsten Versuch.
    final AtomicInteger runs = new AtomicInteger();
    assertThat(claim(TENANT_A_ID, TENANT_A_UUID, signal, runs::incrementAndGet)).isTrue();
    assertThat(runs).hasValue(1);
  }

  /**
   * Zwei Threads claimen gleichzeitig dasselbe Ereignis, je in eigener Transaktion unter demselben
   * Mandanten; eine {@link CyclicBarrier} haelt beide nach Transaktionsbeginn und vor {@code
   * executeOnce} an. Der Unique-Schluessel {@code (MANDANT_UUID, EREIGNIS_ID)} laesst den zweiten
   * Insert bis zum Commit des ersten warten und dann scheitern: genau ein Handler-Lauf, genau eine
   * Zeile, genau ein {@code true}.
   *
   * <p>Zulaessiger Sonderfall wie in {@code DivisionProjectionIT} (Lesson {@code
   * concurrency-test-seams}, Punkt 3): scheitert ein Aufruf an der Sequenzvergabe ({@link
   * BusinessRuleException} ohne {@link DataAccessException} in der Ursachenkette), darf hoechstens
   * ein Thread scheitern; der andere muss dann gewinnen.
   */
  @Test
  @DisplayName("gleichzeitiger Claim desselben Ereignisses: genau ein Handler-Lauf, eine Zeile")
  void i190_parallel_claim_one_wins() throws Exception {
    for (int round = 0; round < RACE_ROUNDS; round++) {
      final String ctx = "Runde " + round;
      final DivisionSignal signal = signal(TENANT_A_UUID);
      final AtomicInteger runs = new AtomicInteger();
      final CyclicBarrier barrier = new CyclicBarrier(2);
      final AtomicReference<Boolean> firstResult = new AtomicReference<>();
      final AtomicReference<Throwable> firstError = new AtomicReference<>();
      final AtomicReference<Boolean> secondResult = new AtomicReference<>();
      final AtomicReference<Throwable> secondError = new AtomicReference<>();
      final Thread first =
          claimThread(signal, runs, barrier, firstResult, firstError, "tm190-claim-1");
      final Thread second =
          claimThread(signal, runs, barrier, secondResult, secondError, "tm190-claim-2");
      first.start();
      second.start();
      first.join(TimeUnit.SECONDS.toMillis(30));
      second.join(TimeUnit.SECONDS.toMillis(30));
      assertThat(first.isAlive()).as(ctx + ": Thread 1 beendet").isFalse();
      assertThat(second.isAlive()).as(ctx + ": Thread 2 beendet").isFalse();

      assertOnlySequenceCollision(ctx + ", Thread 1", firstError.get());
      assertOnlySequenceCollision(ctx + ", Thread 2", secondError.get());
      assertThat(firstError.get() == null || secondError.get() == null)
          .as(ctx + ": hoechstens ein Aufruf scheitert")
          .isTrue();
      final long winners =
          List.of(firstResult, secondResult).stream()
              .filter(result -> Boolean.TRUE.equals(result.get()))
              .count();
      assertThat(winners).as(ctx + ": genau ein Claim gewinnt").isEqualTo(1);
      assertThat(runs).as(ctx + ": genau ein Handler-Lauf").hasValue(1);
      assertThat(receiptCount(TENANT_A_UUID, signal.eventId()))
          .as(ctx + ": genau eine Quittung")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("markDead ueberlebt den Rollback der aeusseren Transaktion; Grund auf 900 gekuerzt")
  void i190_mark_dead_survives_outer_rollback() {
    final DivisionSignal signal = signal(TENANT_A_UUID);
    final String reason = "Abruf fehlgeschlagen: HTTP 403 " + "x".repeat(1000);

    inTenantTx(
        TENANT_A_ID,
        TENANT_A_UUID,
        status -> {
          store.markDead(signal, reason);
          status.setRollbackOnly();
          return null;
        });

    final Map<String, Object> row = singleReceipt(TENANT_A_UUID, signal.eventId());
    assertThat(row.get("STATUS")).isEqualTo("DEAD");
    assertThat(((Number) row.get("TENANT_ID")).longValue()).isEqualTo(TENANT_A_ID);
    assertThat(uuid(row.get("ABTEILUNG_REF"))).isEqualTo(DIV);
    assertThat((String) row.get("FEHLER_MELDUNG"))
        .hasSize(900)
        .startsWith("Abruf fehlgeschlagen: HTTP 403");
    assertThat(isProcessed(TENANT_A_ID, TENANT_A_UUID, signal))
        .as("eine DEAD-Quittung gilt als vorhanden")
        .isTrue();
  }

  @Test
  @DisplayName("gleiche Ereignis-ID in Mandant 563 und 564 ergibt zwei getrennte Quittungen")
  void i190_receipts_are_tenant_scoped() {
    final UUID eventId = UUID.randomUUID();
    final DivisionSignal signalA = new DivisionSignal(eventId, TENANT_A_UUID, DIV, REV);
    final DivisionSignal signalB = new DivisionSignal(eventId, TENANT_B_UUID, DIV, REV);
    final AtomicInteger runs = new AtomicInteger();

    assertThat(claim(TENANT_A_ID, TENANT_A_UUID, signalA, runs::incrementAndGet)).isTrue();
    assertThat(claim(TENANT_B_ID, TENANT_B_UUID, signalB, runs::incrementAndGet)).isTrue();

    assertThat(runs).hasValue(2);
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT TENANT_ID, CAST(MANDANT_UUID AS nvarchar(36)) AS MANDANT_UUID"
                + " FROM [dbo].[TASK_EREIGNIS_QUITTUNG] WHERE EREIGNIS_ID = ?",
            eventId.toString());
    assertThat(rows)
        .extracting(
            row -> ((Number) row.get("TENANT_ID")).longValue(),
            row -> uuid(row.get("MANDANT_UUID")))
        .containsExactlyInAnyOrder(
            tuple(TENANT_A_ID, TENANT_A_UUID), tuple(TENANT_B_ID, TENANT_B_UUID));
    assertThat(isProcessed(TENANT_A_ID, TENANT_A_UUID, signalA)).isTrue();
    assertThat(isProcessed(TENANT_B_ID, TENANT_B_UUID, signalB)).isTrue();
    // :tenantId bindet den Kontext-Mandanten: Mandant 564 sieht die Quittung von 563 nicht.
    final boolean visibleToOtherTenant =
        inTenantTx(
            TENANT_B_ID,
            TENANT_B_UUID,
            status -> receiptRepository.existsByEvent(TENANT_A_UUID, eventId));
    assertThat(visibleToOtherTenant).isFalse();
  }

  @Test
  @DisplayName("deleteOlderThan unter runAsSystem loescht alte Quittungen beider Mandanten")
  void i190_delete_older_than_global() {
    final DivisionSignal oldA = signal(TENANT_A_UUID);
    final DivisionSignal newA = signal(TENANT_A_UUID);
    final DivisionSignal oldB = signal(TENANT_B_UUID);
    final DivisionSignal newB = signal(TENANT_B_UUID);
    claim(TENANT_A_ID, TENANT_A_UUID, oldA, () -> {});
    claim(TENANT_A_ID, TENANT_A_UUID, newA, () -> {});
    claim(TENANT_B_ID, TENANT_B_UUID, oldB, () -> {});
    claim(TENANT_B_ID, TENANT_B_UUID, newB, () -> {});
    final Timestamp longAgo = Timestamp.valueOf(LocalDateTime.of(2020, 1, 1, 0, 0));
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_EREIGNIS_QUITTUNG] SET ANGELEGT_AM = ? WHERE EREIGNIS_ID IN (?, ?)",
        longAgo,
        oldA.eventId().toString(),
        oldB.eventId().toString());

    final Integer deleted =
        TenantContextHolder.runAsSystem(
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        status ->
                            receiptRepository.deleteOlderThan(LocalDateTime.of(2021, 1, 1, 0, 0))));

    assertThat(deleted).isEqualTo(2);
    assertThat(receiptCount(TENANT_A_UUID, oldA.eventId())).isZero();
    assertThat(receiptCount(TENANT_B_UUID, oldB.eventId())).isZero();
    assertThat(receiptCount(TENANT_A_UUID, newA.eventId())).isEqualTo(1);
    assertThat(receiptCount(TENANT_B_UUID, newB.eventId())).isEqualTo(1);
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private static DivisionSignal signal(final UUID tenantUuid) {
    return new DivisionSignal(UUID.randomUUID(), tenantUuid, DIV, REV);
  }

  /**
   * {@code executeOnce} in eigener Transaktion; bei {@code false} rollback-only wie im Processor.
   */
  private boolean claim(
      final long tenantId,
      final UUID tenantUuid,
      final DivisionSignal signal,
      final Runnable handler) {
    return inTenantTx(
        tenantId,
        tenantUuid,
        status -> {
          final boolean claimed = store.executeOnce(signal, handler);
          if (!claimed) {
            status.setRollbackOnly();
          }
          return claimed;
        });
  }

  private boolean isProcessed(
      final long tenantId, final UUID tenantUuid, final DivisionSignal signal) {
    return inTenantTx(tenantId, tenantUuid, status -> store.isProcessed(signal));
  }

  private <T> T inTenantTx(
      final long tenantId, final UUID tenantUuid, final TransactionCallback<T> body) {
    TenantContextHolder.setTenant(new TenantInfo(tenantUuid, tenantId));
    try {
      return new TransactionTemplate(transactionManager).execute(body);
    } finally {
      TenantContextHolder.clear();
    }
  }

  /** Thread eines Claim-Wettlaufs: eigener Mandantenkontext, eigene Transaktion, Barriere davor. */
  private Thread claimThread(
      final DivisionSignal signal,
      final AtomicInteger runs,
      final CyclicBarrier barrier,
      final AtomicReference<Boolean> result,
      final AtomicReference<Throwable> error,
      final String name) {
    final Thread thread =
        new Thread(
            () -> {
              try {
                TenantContextHolder.setTenant(new TenantInfo(TENANT_A_UUID, TENANT_A_ID));
                result.set(
                    new TransactionTemplate(transactionManager)
                        .execute(
                            status -> {
                              rendezvous(barrier);
                              final boolean claimed =
                                  store.executeOnce(signal, runs::incrementAndGet);
                              if (!claimed) {
                                status.setRollbackOnly();
                              }
                              return claimed;
                            }));
              } catch (final Throwable t) {
                error.set(t);
              } finally {
                TenantContextHolder.clear();
              }
            },
            name);
    thread.setDaemon(true);
    return thread;
  }

  private static void rendezvous(final CyclicBarrier barrier) {
    try {
      barrier.await(20, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Barriere unterbrochen", e);
    } catch (final Exception e) {
      throw new IllegalStateException("Barriere nicht erreicht", e);
    }
  }

  /**
   * Laesst keinen Fehler zu ausser einer {@link BusinessRuleException} ohne {@link
   * DataAccessException} in der Ursachenkette (Sequenz-Kollision).
   */
  private static void assertOnlySequenceCollision(final String who, final Throwable error) {
    if (error == null) {
      return;
    }
    boolean databaseCause = false;
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof DataAccessException) {
        databaseCause = true;
        break;
      }
    }
    assertThat(error instanceof BusinessRuleException && !databaseCause)
        .as("%s: nur eine Sequenz-Kollision ist zulaessig, war aber: %s", who, error)
        .isTrue();
  }

  private Map<String, Object> singleReceipt(final UUID tenantUuid, final UUID eventId) {
    final List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT TENANT_ID, CAST(ABTEILUNG_REF AS nvarchar(36)) AS ABTEILUNG_REF, STATUS,"
                + " FEHLER_MELDUNG FROM [dbo].[TASK_EREIGNIS_QUITTUNG]"
                + " WHERE MANDANT_UUID = ? AND EREIGNIS_ID = ?",
            tenantUuid.toString(),
            eventId.toString());
    assertThat(rows).as("genau eine Quittung je Mandant und Ereignis").hasSize(1);
    return rows.get(0);
  }

  private int receiptCount(final UUID tenantUuid, final UUID eventId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_EREIGNIS_QUITTUNG]"
            + " WHERE MANDANT_UUID = ? AND EREIGNIS_ID = ?",
        Integer.class,
        tenantUuid.toString(),
        eventId.toString());
  }

  private static UUID uuid(final Object value) {
    return value == null ? null : UUID.fromString(value.toString());
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
  @Import(TaskEventReceiptStore.class)
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "event-receipt-store-it";
    }
  }
}
