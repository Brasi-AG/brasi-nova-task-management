package de.brasi.nova.taskmanagement.security.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Kapselt den Lebenszyklus der isolierten MSSQL-Wegwerf-Test-DB fuer den Security-Bootstrap-IT:
 * CREATE der eindeutig benannten Wegwerf-DB, Anlegen und Befuellen von PROD_FIRMA / PROD_BENUTZER
 * als Minimal-Referenz-Schema (keine vollstaendige Migration; nur das fuer die Filterchain/IT
 * benoetigte Minimum), DROP am Ende. TTL-basierter Stale-Cleanup verwaister Wegwerf-DBs.
 *
 * <p>Spiegelt das production-Muster ({@code CaptureItDb}/{@code KassenbuchItDb}): strikte
 * Namensregel, SINGLE_USER-DROP, TTL-Cleanup. Zugangsdaten ausschliesslich aus {@link
 * RefSchemaItEnv}.
 */
public final class RefSchemaItDb {

  private static final Logger log = LoggerFactory.getLogger(RefSchemaItDb.class);

  private static final String DRIVER_CLASS = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
  private static final String NAME_PREFIX = "TASK_MGMT_IT_";

  /** Strikte Namensregel: verhindert dynamischen DROP ausserhalb der Wegwerf-Namensfamilie. */
  private static final Pattern NAME_PATTERN =
      Pattern.compile("^TASK_MGMT_IT_[0-9a-f]{32}$");

  private static final int STALE_TTL_HOURS = 2;

  private RefSchemaItDb() {}

  /** Basis-DataSource (databaseName=master) fuer CREATE/DROP. */
  public static HikariDataSource masterDataSource() {
    return hikari(RefSchemaItEnv.masterUrl(), 1);
  }

  /** Eindeutiger Wegwerf-DB-Name pro Lauf; kollisionsfrei bei parallelen Laeufen. */
  public static String newDbName() {
    return NAME_PREFIX + UUID.randomUUID().toString().replace("-", "");
  }

  /**
   * Legt die Wegwerf-DB an (nach optionalem TTL-Cleanup), erstellt PROD_FIRMA und PROD_BENUTZER
   * minimal, und gibt den DB-Namen zurueck.
   *
   * @param master DataSource auf master-DB
   * @return der DB-Name der angelegten Wegwerf-DB
   */
  public static String createThrowawayDb(final DataSource master) {
    cleanupStaleDbs(master);
    final String dbName = newDbName();
    ensureSafeName(dbName);
    new JdbcTemplate(master).execute("CREATE DATABASE [" + dbName + "]");
    log.info("Task-Mgmt-IT Wegwerf-DB angelegt: {}", dbName);
    try (HikariDataSource throwaway = throwawayDataSource(dbName, 1)) {
      final JdbcTemplate jdbc = new JdbcTemplate(throwaway);
      createRefSchema(jdbc);
    }
    return dbName;
  }

  /** DataSource auf die Wegwerf-DB. */
  public static HikariDataSource throwawayDataSource(
      final String dbName, final int maxPoolSize) {
    ensureSafeName(dbName);
    return hikari(swapDatabaseName(RefSchemaItEnv.masterUrl(), dbName), maxPoolSize);
  }

  /** Voll aufgeloeste JDBC-URL auf die Wegwerf-DB (fuer {@code @DynamicPropertySource}). */
  public static String throwawayJdbcUrl(final String dbName) {
    ensureSafeName(dbName);
    return swapDatabaseName(RefSchemaItEnv.masterUrl(), dbName);
  }

  /** Droppt die Wegwerf-DB (SINGLE_USER kickt Restsessions); Name strikt validiert. */
  public static void dropThrowawayDb(final DataSource master, final String dbName) {
    ensureSafeName(dbName);
    final JdbcTemplate jdbc = new JdbcTemplate(master);
    jdbc.execute(
        "ALTER DATABASE [" + dbName + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
    jdbc.execute("DROP DATABASE [" + dbName + "]");
    log.info("Task-Mgmt-IT Wegwerf-DB gedroppt: {}", dbName);
  }

  /**
   * Best-effort: droppt verwaiste Wegwerf-DBs aelter als die TTL. Fehler werden geschluckt.
   */
  public static void cleanupStaleDbs(final DataSource master) {
    try {
      final JdbcTemplate jdbc = new JdbcTemplate(master);
      final List<String> names =
          jdbc.queryForList(
              "SELECT name FROM sys.databases "
                  + "WHERE name LIKE 'TASK[_]MGMT[_]IT[_]%' "
                  + "AND create_date < DATEADD(hour, -?, SYSUTCDATETIME())",
              String.class,
              STALE_TTL_HOURS);
      for (final String name : names) {
        if (NAME_PATTERN.matcher(name).matches()) {
          try {
            dropThrowawayDb(master, name);
          } catch (final RuntimeException e) {
            log.warn(
                "Stale-Cleanup: DROP {} fehlgeschlagen (ignoriert): {}",
                name,
                e.getMessage());
          }
        }
      }
    } catch (final RuntimeException e) {
      log.warn("Stale-Cleanup uebersprungen (ignoriert): {}", e.getMessage());
    }
  }

  /**
   * Erstellt das Minimal-Referenz-Schema (PROD_FIRMA + PROD_BENUTZER) in der Wegwerf-DB. Nur die
   * fuer die Filterchain-/Repository-Validierung benoetigten Spalten.
   */
  private static void createRefSchema(final JdbcTemplate jdbc) {
    jdbc.execute(
        """
        CREATE TABLE [dbo].[PROD_FIRMA] (
          [ID]                            bigint          NOT NULL,
          [ENTITY_STATUS]                 nvarchar(255)   NOT NULL,
          [GUELTIG_BIS]                   date            NULL,
          [GUELTIG_VON]                   date            NULL,
          [ANGELEGT_VON]                  nvarchar(255)   NULL,
          [AKTUALISIERT_VON]              nvarchar(255)   NULL,
          [GELOESCHT_VON]                 nvarchar(255)   NULL,
          [ANGELEGT_AM]                   datetime2       NULL,
          [AKTUALISIERT_AM]               datetime2       NULL,
          [GELOESCHT_AM]                  datetime2       NULL,
          [EXTERNALID]                    uniqueidentifier NOT NULL,
          [NAME]                          nvarchar(255)   NULL,
          [KURZNAME]                      nvarchar(255)   NULL,
          [KUERZEL]                       nvarchar(255)   NULL,
          [VERSION]                       int             NOT NULL DEFAULT 1,
          [TENANT_ID]                     bigint          NOT NULL,
          [DIAMAND_MANDANT_NR]            int             NULL,
          [PROCESSING_FIRM_ID]            bigint          NULL,
          [EFFECTIVE_PROCESSOR_FIRM_ID_REF] bigint        NULL,
          CONSTRAINT [PK_PROD_FIRMA] PRIMARY KEY ([ID])
        )
        """);

    jdbc.execute(
        """
        CREATE TABLE [dbo].[PROD_BENUTZER] (
          [ID]                  bigint          NOT NULL,
          [VOLLER_NAME]         nvarchar(255)   NULL,
          [LOGIN_NAME]          nvarchar(255)   NULL,
          [ENTITY_STATUS]       nvarchar(255)   NOT NULL,
          [ANGELEGT_VON]        nvarchar(255)   NULL,
          [AKTUALISIERT_VON]    nvarchar(255)   NULL,
          [GELOESCHT_VON]       nvarchar(255)   NULL,
          [ANGELEGT_AM]         datetime2       NULL,
          [AKTUALISIERT_AM]     datetime2       NULL,
          [GELOESCHT_AM]        datetime2       NULL,
          [EXTERNALID]          uniqueidentifier NOT NULL,
          [VERSION]             int             NOT NULL DEFAULT 1,
          [TENANT_ID]           bigint          NOT NULL,
          CONSTRAINT [PK_PROD_BENUTZER] PRIMARY KEY ([ID])
        )
        """);
  }

  private static void ensureSafeName(final String dbName) {
    if (dbName == null || !NAME_PATTERN.matcher(dbName).matches()) {
      throw new IllegalArgumentException("Unsicherer Wegwerf-DB-Name: " + dbName);
    }
  }

  private static HikariDataSource hikari(
      final String jdbcUrl, final int maxPoolSize) {
    final HikariConfig config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(RefSchemaItEnv.user());
    config.setPassword(RefSchemaItEnv.password());
    config.setDriverClassName(DRIVER_CLASS);
    config.setMaximumPoolSize(maxPoolSize);
    config.setPoolName("task-mgmt-it");
    return new HikariDataSource(config);
  }

  private static String swapDatabaseName(
      final String url, final String dbName) {
    if (Pattern.compile("(?i)databaseName=").matcher(url).find()) {
      return url.replaceAll("(?i)databaseName=[^;]*", "databaseName=" + dbName);
    }
    return url + ";databaseName=" + dbName;
  }
}
