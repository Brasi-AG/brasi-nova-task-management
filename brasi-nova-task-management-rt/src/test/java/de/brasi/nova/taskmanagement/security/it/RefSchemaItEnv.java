package de.brasi.nova.taskmanagement.security.it;

/**
 * Liest die Zugangsdaten fuer die isolierte MSSQL-Wegwerf-Test-DB des Security-Bootstrap-ITs. Alle
 * Werte kommen aus Umgebungsvariablen oder System-Properties — KEIN Klartext-Secret im Code. Fehlt
 * ein Wert, wird der IT uebersprungen ({@link RefSchemaItEnabledCondition}).
 *
 * <p>Primaere Env-Vars: {@code BRASI_INT_DB_USER} / {@code BRASI_INT_DB_PASSWORD} (sa-Zugang auf
 * 82.165.206.17,1433).
 */
public final class RefSchemaItEnv {

  /** System-Property fuer die Basis-JDBC-URL (databaseName=master). */
  public static final String URL_PROPERTY = "brasi.int.db.url";

  /** Env-Var fuer die Basis-JDBC-URL. */
  public static final String URL_ENV = "BRASI_INT_DB_URL";

  /** System-Property fuer den Benutzernamen. */
  public static final String USER_PROPERTY = "brasi.int.db.user";

  /** Env-Var fuer den Benutzernamen. */
  public static final String USER_ENV = "BRASI_INT_DB_USER";

  /** System-Property fuer das Passwort. */
  public static final String PASSWORD_PROPERTY = "brasi.int.db.password";

  /** Env-Var fuer das Passwort. */
  public static final String PASSWORD_ENV = "BRASI_INT_DB_PASSWORD";

  /** Standard-MSSQL-Server fuer den Integrationstest (wenn keine URL explizit gesetzt). */
  private static final String DEFAULT_SERVER = "82.165.206.17:1433";

  private RefSchemaItEnv() {}

  /**
   * Basis-JDBC-URL (databaseName=master). Fallback auf den Default-Server, wenn keine URL-Var
   * gesetzt ist, aber User+Passwort vorhanden sind.
   */
  public static String masterUrl() {
    final String explicit = resolve(URL_PROPERTY, URL_ENV);
    if (isPresent(explicit)) {
      return explicit;
    }
    // Fallback: Default-Server mit master-DB (nur wenn User+PW gesetzt).
    if (isPresent(user()) && isPresent(password())) {
      return "jdbc:sqlserver://"
          + DEFAULT_SERVER
          + ";databaseName=master;encrypt=true;trustServerCertificate=true";
    }
    return null;
  }

  /** Benutzername (typisch: sa). */
  public static String user() {
    return resolve(USER_PROPERTY, USER_ENV);
  }

  /** Passwort (aus Env/System-Property, nie hardcoded). */
  public static String password() {
    return resolve(PASSWORD_PROPERTY, PASSWORD_ENV);
  }

  /** {@code true}, wenn alle drei Werte (url/user/password) vorhanden sind. */
  public static boolean isConfigured() {
    return isPresent(masterUrl()) && isPresent(user()) && isPresent(password());
  }

  private static String resolve(final String property, final String env) {
    final String prop = System.getProperty(property);
    if (isPresent(prop)) {
      return prop;
    }
    return System.getenv(env);
  }

  static boolean isPresent(final String value) {
    return value != null && !value.isBlank();
  }
}
