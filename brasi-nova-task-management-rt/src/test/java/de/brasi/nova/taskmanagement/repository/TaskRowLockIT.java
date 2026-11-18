package de.brasi.nova.taskmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-MSSQL-IT fuer die Zeilensperre {@link TaskKeysetQueryRepository#lockById} (Review #150).
 *
 * <p>Darauf stuetzt sich die Serialisierung von {@code MarkerService#markSeen} mit {@code
 * CommentService#createComment}: markSeen stempelt {@code SEEN_AT} unter dieser Update-Sperre,
 * createComment stempelt den Kommentar erst nach seinem {@code TASK}-save, also unter der
 * Schreibsperre derselben Zeile. Die Service-Reihenfolge belegen die Unit-Tests ({@code
 * MarkerServiceTest}, {@code CommentServiceTest}) und der Zwei-Thread-Lauf in {@code
 * CommentConcurrencyIT}; dieser IT belegt den tragenden Sperrmechanismus in beide Richtungen.
 *
 * <p>Die Wegwerf-DB laeuft bewusst mit {@code READ_COMMITTED_SNAPSHOT ON}: dort wartet ein
 * gewoehnlicher SELECT nicht auf Schreibsperren. So zeigt der IT, dass die Sperre aus dem
 * Tabellenhinweis kommt und nicht aus dem Isolationsniveau (unter gesperrtem READ COMMITTED wartete
 * auch ein einfacher Leser, der Beweis waere dort nicht trennscharf).
 *
 * <p>Warten wird nicht ueber Zeitablauf geprueft, sondern positiv beobachtet: eine unabhaengige
 * Verbindung auf master sieht in {@code sys.dm_exec_requests} eine Anfrage dieser DB, die auf eine
 * Sperre der erwarteten Art wartet.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskRowLockIT.ItConfig.class,
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
      // Halter, Wartender und Leser brauchen je eine eigene Verbindung.
      "spring.datasource.hikari.maximum-pool-size=5",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class TaskRowLockIT {

  private static final long TENANT_ID = 596L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000596");
  private static final long TASK_ID = 596001L;
  private static final UUID TASK_UUID = UUID.fromString("00000059-0000-0059-aaaa-000000596001");

  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_LOW = 201L;
  private static final long DIRECTION_BRASI_CUSTOMER = 301L;

  private static final long TIMEOUT_SECONDS = 30L;

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TaskKeysetQueryRepository taskRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private ExecutorService executor;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
    // Vor dem Start des Kontexts (noch keine Verbindung auf die Wegwerf-DB offen).
    new JdbcTemplate(master)
        .execute(
            "ALTER DATABASE ["
                + dbName
                + "] SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE");
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
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        TENANT_ID,
        TENANT_ID,
        TENANT_UUID,
        TENANT_ID);
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE, TASK_STATUS_ID, "
            + " PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT, COMMENT_COUNT, "
            + " ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, 'RowLock-Task', ?, ?, ?, ?, GETDATE(), 0, 'it-seed',"
            + " GETDATE())",
        TASK_ID,
        TASK_ID,
        TASK_UUID,
        TENANT_ID,
        STATUS_OPEN,
        PRIORITY_LOW,
        DIRECTION_BRASI_CUSTOMER,
        TENANT_ID);
  }

  @BeforeEach
  void setUp() {
    executor = Executors.newFixedThreadPool(3);
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  @DisplayName(
      "lockById wartet auf eine offene Schreibsperre der Zeile bis zum Commit und sieht danach den"
          + " neuen Stand; ein gewoehnlicher Leser wartet unter READ_COMMITTED_SNAPSHOT nicht")
  void sperre_wartet_auf_offene_schreibsperre_leser_nicht() throws Exception {
    final int before = commentCount();
    final CountDownLatch held = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    // Halter: schreibt die Zeile (wie der TASK-save eines Kommentars) und haelt die Tx offen.
    final Future<?> writer =
        executor.submit(
            inTx(
                () -> {
                  jdbcTemplate.update(
                      "UPDATE [dbo].[TASK] SET COMMENT_COUNT = COMMENT_COUNT + 1 WHERE ID = ?",
                      TASK_ID);
                  held.countDown();
                  release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                  return null;
                }));
    assertThat(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("Halter schreibt").isTrue();

    // Gewoehnlicher Leser: kehrt sofort mit dem letzten committeten Stand zurueck.
    final Future<Integer> plain =
        executor.submit(
            inTx(() -> taskRepository.findByUuid(TASK_UUID.toString()).get().getCommentCount()));
    assertThat(plain.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("einfacher Leser wartet nicht (READ_COMMITTED_SNAPSHOT) und sieht den alten Stand")
        .isEqualTo(before);

    // Sperrender Leser: wartet auf die Schreibsperre.
    final Future<Integer> locked =
        executor.submit(inTx(() -> taskRepository.lockById(TASK_ID).get(0).getCommentCount()));
    awaitLockWait("LCK_M_U");
    assertThat(locked.isDone()).as("lockById wartet, solange der Halter offen ist").isFalse();

    release.countDown();
    writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(locked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("nach dem Commit des Halters sieht die Sperre den neuen Stand")
        .isEqualTo(before + 1);
  }

  @Test
  @DisplayName(
      "ein Schreiber der Zeile wartet auf die Sperre von lockById bis zu deren Commit (der"
          + " TASK-save eines spaeter beginnenden Kommentars laeuft erst danach)")
  void schreiber_wartet_auf_sperre_bis_commit() throws Exception {
    final int before = commentCount();
    final CountDownLatch held = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    final Future<?> holder =
        executor.submit(
            inTx(
                () -> {
                  assertThat(taskRepository.lockById(TASK_ID)).hasSize(1);
                  held.countDown();
                  release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                  return null;
                }));
    assertThat(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("Sperre gehalten").isTrue();

    final Future<Integer> write =
        executor.submit(
            inTx(
                () ->
                    jdbcTemplate.update(
                        "UPDATE [dbo].[TASK] SET COMMENT_COUNT = COMMENT_COUNT + 1 WHERE ID = ?",
                        TASK_ID)));
    awaitLockWait("LCK_M_X");
    assertThat(write.isDone()).as("Schreiber wartet auf die Sperre").isFalse();

    release.countDown();
    holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(write.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(commentCount()).isEqualTo(before + 1);
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  /** Fuehrt {@code body} im Mandantenkontext in einer eigenen Transaktion des Ausfuehrers aus. */
  private <T> Callable<T> inTx(final Callable<T> body) {
    return () -> {
      TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
      try {
        return new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  try {
                    return body.call();
                  } catch (final RuntimeException e) {
                    throw e;
                  } catch (final Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
      } finally {
        TenantContextHolder.clear();
      }
    };
  }

  /** Wartet, bis eine Anfrage dieser DB auf eine Sperre der Art {@code waitType} wartet. */
  private void awaitLockWait(final String waitType) throws InterruptedException {
    final JdbcTemplate probe = new JdbcTemplate(master);
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      final Integer waiting =
          probe.queryForObject(
              "SELECT COUNT(*) FROM sys.dm_exec_requests"
                  + " WHERE database_id = DB_ID(?) AND wait_type = ?",
              Integer.class,
              dbName,
              waitType);
      if (waiting != null && waiting > 0) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(50);
    }
    throw new AssertionError("Keine Anfrage wartet auf " + waitType + " in " + dbName);
  }

  private int commentCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE ID = ?", Integer.class, TASK_ID);
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
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "task-row-lock-it";
    }
  }
}
