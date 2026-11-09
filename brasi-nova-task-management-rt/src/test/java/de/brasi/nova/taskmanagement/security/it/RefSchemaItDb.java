package de.brasi.nova.taskmanagement.security.it;

import com.microsoft.sqlserver.jdbc.SQLServerException;
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
 * als Minimal-Referenz-Schema sowie der leeren Struktur-Kopie der System-Tabelle {@code
 * system_controller_regel} (keine vollstaendige Migration; nur das fuer Filterchain/IT bzw. die
 * KSF-HATEOAS-Engine benoetigte Minimum, das im realen Deploy als Struktur-Kopie der diamant-DB
 * vorliegt), DROP am Ende. TTL-basierter Stale-Cleanup verwaister Wegwerf-DBs.
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

  /** Maximale Anzahl Versuche beim DROP einer Wegwerf-DB (SQL-Fehlercode 3702: "currently in use"). */
  private static final int DROP_MAX_RETRIES = 5;

  /** Wartezeit zwischen DROP-Versuchen in Millisekunden. */
  private static final long DROP_RETRY_WAIT_MS = 500L;

  /** SQL-Fehlercode 3702: "Cannot drop database ... because it is currently in use." */
  private static final int SQL_ERROR_DB_IN_USE = 3702;

  /** SQL-Fehlercode 5064: Einzelbenutzermodus, ein Benutzer ist bereits verbunden (ALTER). */
  private static final int SQL_ERROR_SINGLE_USER_BUSY = 5064;

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

  /**
   * Droppt die Wegwerf-DB (SINGLE_USER kickt Restsessions); Name strikt validiert.
   *
   * <p>Haertung: bei den SQL-Fehlercodes 3702 ("currently in use", DROP) und 5064 ("Einzelbenutzer-
   * modus, ein Benutzer ist verbunden", ALTER) wird ALTER SET SINGLE_USER + DROP wiederholt,
   * maximal {@link #DROP_MAX_RETRIES} Mal mit kurzem Warten. Ursache: der Hikari-Pool eines
   * gecachten Spring-Kontexts verbindet sich sofort neu und belegt den einzigen SINGLE_USER-Slot.
   *
   * <p>Sind die Versuche erschoepft, wird NICHT geworfen (sonst wuerde die IT-Klasse im
   * {@code @AfterAll} rot, obwohl alle Tests gruen waren), sondern gewarnt: die Wegwerf-DB ist
   * isoliert, beeinflusst keinen Testlauf und wird vom TTL-Stale-Cleanup ({@link
   * #cleanupStaleDbs}, {@value #STALE_TTL_HOURS} h) eingesammelt. Bewusst KEIN {@code SET OFFLINE}:
   * dabei wuerden die Dateien beim DROP nicht geloescht. Andere SQL-Fehler werden weiter geworfen.
   *
   * @param master DataSource auf master-DB
   * @param dbName Name der Wegwerf-DB (strikt validiert)
   */
  public static void dropThrowawayDb(final DataSource master, final String dbName) {
    ensureSafeName(dbName);
    final JdbcTemplate jdbc = new JdbcTemplate(master);
    for (int attempt = 1; attempt <= DROP_MAX_RETRIES; attempt++) {
      try {
        jdbc.execute(
            "ALTER DATABASE [" + dbName + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
        jdbc.execute("DROP DATABASE [" + dbName + "]");
        log.info("Task-Mgmt-IT Wegwerf-DB gedroppt: {} (Versuch {})", dbName, attempt);
        return;
      } catch (final RuntimeException e) {
        if (!isRetryableDropError(e)) {
          throw e;
        }
        if (attempt < DROP_MAX_RETRIES) {
          log.warn(
              "DROP {} fehlgeschlagen (DB in use / Single-User belegt), Versuch {}/{} - warte {}ms...",
              dbName,
              attempt,
              DROP_MAX_RETRIES,
              DROP_RETRY_WAIT_MS);
          try {
            Thread.sleep(DROP_RETRY_WAIT_MS);
          } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DROP-Retry unterbrochen.", ie);
          }
        } else {
          log.warn(
              "Wegwerf-DB {} nach {} Versuchen nicht gedroppt (Pool des gecachten Kontexts belegt"
                  + " die DB) - Rueckfall auf TTL-Stale-Cleanup ({} h): {}",
              dbName,
              DROP_MAX_RETRIES,
              STALE_TTL_HOURS,
              e.getMessage());
        }
      }
    }
  }

  /**
   * Klassifiziert SQL-Fehlercodes des Wegwerf-DB-Drops: 3702 und 5064 sind retrybar.
   *
   * @param errorCode SQL-Server-Fehlercode
   * @return true, wenn ein erneuter Versuch sinnvoll ist
   */
  static boolean isRetryableDropErrorCode(final int errorCode) {
    return errorCode == SQL_ERROR_DB_IN_USE || errorCode == SQL_ERROR_SINGLE_USER_BUSY;
  }

  /** Prueft die Ursachenkette auf einen retrybaren SQL-Fehlercode. */
  private static boolean isRetryableDropError(final RuntimeException e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof SQLServerException sqlEx) {
        return isRetryableDropErrorCode(sqlEx.getErrorCode());
      }
      cause = cause.getCause();
    }
    return false;
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
   * Erstellt das Minimal-Referenz-Schema (PROD_FIRMA + PROD_BENUTZER + die leere Struktur-Kopie der
   * System-Tabelle {@code system_controller_regel}) in der Wegwerf-DB. Nur die fuer die
   * Filterchain-/Repository-Validierung bzw. die KSF-HATEOAS-Engine benoetigten Spalten.
   *
   * <p><b>FINDING 2 (Codex-IMPL-Gate) — {@code system_controller_regel}:</b> Diese System-Tabelle
   * ist eine FRAMEWORK-/DEPLOY-verantwortete Tabelle, KEINE Consumer-Flyway-Tabelle. Im realen
   * Deploy wird die task-management-DB als Struktur-Kopie der diamant-DB provisioniert (siehe
   * Startkarte „Stammdaten-Basis: PROD_FIRMA und PROD_BENUTZER ... SYS_SEQUENCE und SYSTEM*-Tabellen
   * als leere Struktur-Kopie"). Genau wie PROD_FIRMA/PROD_BENUTZER/SYS_SEQUENCE existiert
   * {@code system_controller_regel} dort also bereits (leer) und wird vom Modul bewusst NICHT in V1-
   * V9 angelegt; auch KSF 2.3.24 liefert keine Flyway-Migration dafuer (Referenz-Consumer
   * spring-application indiziert sie in V5 nur per {@code IF OBJECT_ID(...) IS NOT NULL} als
   * vorbestehend). In der isolierten Wegwerf-DB fehlt diese Struktur-Kopie -> wir legen sie hier
   * EINMAL zentral (nicht ad-hoc im Affordance-IT) an und spiegeln damit das reale Deploy. Die
   * Spalten folgen exakt dem Vertrag des KSF-{@code JdbcHateoasRuleStore} (SELECT/INSERT/UPDATE),
   * inkl. {@code angelegt_von}/{@code aktualisiert_von}, die der fruehere IT-Ad-hoc-DDL fehlten
   * (Insert/Update einer Regel waere dort gebrochen). Leer angelegt: die KSF-Engine liest nur aktive
   * DB-Regeln (hier keine) und faellt auf die Annotation-Regeln zurueck -> der {@code self}-Link
   * entsteht, ohne dass der Test still eine Tabelle vortaeuschen muss.
   */
  private static void createRefSchema(final JdbcTemplate jdbc) {
    // Leere Struktur-Kopie der System-Tabelle (Spalten exakt nach KSF JdbcHateoasRuleStore).
    jdbc.execute(
        """
        IF OBJECT_ID('dbo.system_controller_regel', 'U') IS NULL
        CREATE TABLE [dbo].[system_controller_regel] (
          [id]               bigint           NOT NULL IDENTITY(1,1) PRIMARY KEY,
          [rule_schluessel]  nvarchar(255)    NULL,
          [beschreibung]     nvarchar(1024)   NULL,
          [entity_status]    nvarchar(255)    NOT NULL DEFAULT 'ACTIVE',
          [gueltig_von]      date             NULL,
          [gueltig_bis]      date             NULL,
          [angelegt_von]     nvarchar(255)    NULL,
          [aktualisiert_von] nvarchar(255)    NULL,
          [externalid]       uniqueidentifier NOT NULL DEFAULT NEWID(),
          [version]          int              NOT NULL DEFAULT 1,
          [tenant_id]        bigint           NULL
        )
        """);

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
