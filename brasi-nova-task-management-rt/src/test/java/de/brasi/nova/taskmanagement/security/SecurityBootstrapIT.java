package de.brasi.nova.taskmanagement.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.refentity.FirmReadRepository;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController;
import io.jsonwebtoken.Jwts;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import io.keel.framework.security.KsfSecurityAutoConfiguration;
import io.keel.framework.security.web.KsfWebSecurityAutoConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Security-Bootstrap-IT: beweist gegen echten MSSQL (Wegwerf-DB) und die reale KSF-Filterchain,
 * dass ein signierter JWT mit {@code lastActiveTenantId} + {@code profile} korrekt verarbeitet
 * wird: Tenant wird aufgeloest, Benutzer-UUID wird aus dem Profile-Claim gelesen, und der
 * WhoAmI-Endpoint liefert die erwarteten IDs zurueck.
 *
 * <p>Wird uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 *
 * <p><b>TDD-RED-Fase:</b> laeuft fehl, bis Firm/User-Repos + SecurityContextUserAccessor +
 * CurrentActor implementiert sind (Step 2 des Plans). <b>GREEN-Fase:</b> nach Implementierung
 * dieser Klassen (Step 3).
 */
@SpringBootTest(
    classes = SecurityBootstrapIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "eureka.client.enabled=false",
      "ksf.persistence.dialect=MSSQL",
      "ksf.persistence.ddl-auto=none",
      "ksf.module-registration.enabled=false",
      // KSF Filterchain (FILTER-Modus, identisch zu application.properties)
      "ksf.web-security.enabled=true",
      "ksf.web-security.filter-chain.enabled=true",
      "ksf.web-security.principal.profile-claim.enabled=true",
      "ksf.web-security.tenant.context-binding=FILTER",
      "ksf.web-security.tenant.binding.source=JWT",
      "ksf.web-security.tenant.binding.required=false",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      // JWT-Config: test-generierter RSA-Key (kid wird per DynamicPropertySource gesetzt)
      "ksf.security.jwt.enabled=true",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class SecurityBootstrapIT {

  private static final String TEST_KID = "task-mgmt-test-key";
  // Tenant A: Haupt-Tenant fuer positive Tests
  private static final long TENANT_INTERNAL_ID = 1L;
  private static final UUID TENANT_UUID = UUID.randomUUID();
  private static final UUID USER_UUID = UUID.randomUUID();
  private static final String USER_LOGIN = "it-user@brasi.de";
  // Tenant B: fremder Tenant fuer Tenant-Scoping-Tests
  private static final long FOREIGN_TENANT_INTERNAL_ID = 2L;
  private static final UUID FOREIGN_TENANT_UUID = UUID.randomUUID();
  private static final String FOREIGN_USER_LOGIN = "foreign-user@brasi.de";

  // RSA-Keypair fuer Test-JWT-Signierung (im Speicher erzeugt, nie persistiert)
  private static final KeyPair RSA_KEY_PAIR;

  static {
    try {
      final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      RSA_KEY_PAIR = gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static HikariDataSource master;
  private static String dbName;
  private static Path pemTempFile;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    // Wegwerf-DB anlegen und befuellen
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
    registry.add("spring.datasource.url", () -> RefSchemaItDb.throwawayJdbcUrl(dbName));
    registry.add(
        "spring.datasource.username",
        de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv::user);
    registry.add(
        "spring.datasource.password",
        de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv::password);
    registry.add(
        "spring.datasource.driver-class-name",
        () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    // JWT-Key-Config: Public-Key als temporaere PEM-Datei (KSF laedt via ResourceLoader)
    final String pemContent = toPem(RSA_KEY_PAIR.getPublic().getEncoded());
    try {
      pemTempFile = Files.createTempFile("task-mgmt-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Konnte temporaere PEM-Datei nicht erstellen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem",
        () -> "file:" + pemTempFile.toAbsolutePath());
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
      if (pemTempFile != null) {
        try {
          Files.deleteIfExists(pemTempFile);
        } catch (IOException ignored) {
          // Cleanup best-effort
        }
      }
    }
  }

  // ---- Step 2: Failing IT (RED) ---------------------------------------------------------------

  @Test
  @DisplayName("filterchain_bindet_tenant_und_user_aus_jwt: Tenant und User-UUID korrekt aufgeloest")
  void filterchain_bindet_tenant_und_user_aus_jwt() throws Exception {
    // Seed: PROD_FIRMA und PROD_BENUTZER fuer den Test anlegen
    seedFirma(TENANT_INTERNAL_ID, TENANT_UUID);
    seedBenutzer(42L, USER_UUID, USER_LOGIN, TENANT_INTERNAL_ID);

    // JWT mit lastActiveTenantId + profile-Claim erstellen
    final String token = buildSignedJwt(TENANT_UUID, USER_UUID, USER_LOGIN);

    final MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();

    mvc.perform(get("/it/whoami").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(TENANT_INTERNAL_ID))
        .andExpect(jsonPath("$.tenantUuid").value(TENANT_UUID.toString()))
        .andExpect(jsonPath("$.userId").value(USER_UUID.toString()))
        .andExpect(jsonPath("$.loginName").value(USER_LOGIN));
  }

  // ---- Step 4a: Tenant-Scoping des UserReadRepository beweisen -----------------------------------

  /**
   * Beweist, dass UserReadRepository.findByLoginName(:tenantId) den Benutzer des FREMDEN Mandanten
   * NICHT zurueckliefert, wenn man als Mandant A (currentTenant) authentifiziert ist.
   *
   * <p>TDD-Rationale: Wuerde :tenantId NICHT auto-gebunden (z.B. keine WHERE TENANT_ID-Klausel),
   * wuerde findByLoginName den Benutzer mandantenuebergreifend finden und der Test lieferte 200
   * statt 404 -> Fehler. Er bestellt Gruene nur wenn der Query korrekt auf den Kontext-Tenant
   * beschraenkt ist.
   *
   * <p>Setup: Tenant A (TENANT_INTERNAL_ID / TENANT_UUID) und Tenant B
   * (FOREIGN_TENANT_INTERNAL_ID / FOREIGN_TENANT_UUID) werden in PROD_FIRMA geseedet. Der
   * Fremdbenutzer FOREIGN_USER_LOGIN wird AUSSCHLIESSLICH in Tenant B angelegt. Dann wird ein JWT
   * fuer Tenant A ausgestellt. Der /it/findUser-Endpoint sucht den fremden Login-Namen -- der KSF-
   * Filter bindet TenantContextHolder auf Tenant A, das Repository-Query filtert auf TENANT_ID=1.
   * Ergebnis: Optional.empty() -> 404 (Beweis fuer korrekte Scoping-Bindung).
   */
  @Test
  @DisplayName(
      "fremder_tenant_user_nicht_sichtbar: UserReadRepository scoped auf aktuellen Tenant;"
          + " Fremdbenutzer liefert 404")
  void fremder_tenant_user_nicht_sichtbar() throws Exception {
    // Tenant A und Tenant B anlegen (Seed idempotent durch IF NOT EXISTS)
    seedFirma(TENANT_INTERNAL_ID, TENANT_UUID);
    seedFirma(FOREIGN_TENANT_INTERNAL_ID, FOREIGN_TENANT_UUID);
    // Fremdbenutzer NUR in Tenant B anlegen -- in Tenant A existiert dieser Login-Name nicht
    seedBenutzer(99L, UUID.randomUUID(), FOREIGN_USER_LOGIN, FOREIGN_TENANT_INTERNAL_ID);

    // JWT fuer Tenant A (current) -- TenantContextHolder wird auf TENANT_INTERNAL_ID=1 gesetzt
    final String tokenForTenantA = buildSignedJwt(TENANT_UUID, USER_UUID, USER_LOGIN);

    final MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();

    // Anfrage als Tenant A nach dem Benutzer des fremden Tenants B:
    // UserReadRepository.findByLoginName bindet :tenantId an Tenant A -> kein Treffer -> 404
    mvc.perform(
            get("/it/findUser")
                .param("loginName", FOREIGN_USER_LOGIN)
                .header("Authorization", "Bearer " + tokenForTenantA))
        .andExpect(status().isNotFound());
  }

  // ---- Step 4b: fremder Tenant -> 403 ----------------------------------------------------------

  @Test
  @DisplayName("fremder_tenant_user_nicht_aufloesbar: unbekannte Tenant-UUID -> 403")
  void fremder_tenant_user_nicht_aufloesbar() throws Exception {
    // Kein Seed fuer diese UUID -> IdMappingService kann sie nicht aufloesen -> 403
    final UUID unknownTenantUuid = UUID.randomUUID();
    final String token = buildSignedJwt(unknownTenantUuid, UUID.randomUUID(), "nobody@test.de");

    final MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();

    mvc.perform(get("/it/whoami").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  // ---- Helper: JWT-Signierung -----------------------------------------------------------------

  private static String buildSignedJwt(
      final UUID tenantUuid, final UUID userUuid, final String loginName) {
    // profile-JSON: nur die fuer ProfileDTO.userId/username relevanten Felder
    final String profileJson =
        "{\"userId\":\""
            + userUuid
            + "\",\"username\":\""
            + loginName
            + "\",\"firstName\":\"IT\",\"lastName\":\"Test\","
            + "\"is2faEnabled\":false,\"is2faVerified\":true}";

    final Instant now = Instant.now();
    return Jwts.builder()
        .header()
        .keyId(TEST_KID)
        .and()
        .subject(loginName)
        .issuedAt(Date.from(now.minusSeconds(60)))
        .expiration(Date.from(now.plusSeconds(600)))
        .claim("lastActiveTenantId", tenantUuid.toString())
        .claim("profile", profileJson)
        .claim("authorities", List.of())
        .claim("global", List.of())
        .claim("byTenant", Map.of())
        .signWith(RSA_KEY_PAIR.getPrivate())
        .compact();
  }

  private void seedFirma(final long id, final UUID externalId) {
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private void seedBenutzer(
      final long id,
      final UUID externalId,
      final String loginName,
      final long tenantId) {
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, LOGIN_NAME, VOLLER_NAME) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, 'IT Test User')",
        id, id, externalId, tenantId, loginName);
  }

  // ---- PEM-Hilfsmethode -----------------------------------------------------------------------

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  // ---- IT-Konfigurations-Context --------------------------------------------------------------

  @SpringBootConfiguration
  @AutoConfigurationPackage(basePackages = "de.brasi.nova.taskmanagement.refentity")
  @EnableTransactionManagement
  @EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.refentity"
      })
  @ImportAutoConfiguration({
    // DataSource + JDBC
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    // KSF Persistence (NovaRepository-Implementierung, CachingIdMappingService)
    KsfPersistenceAutoConfiguration.class,
    // Web-Stack
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    TaskExecutionAutoConfiguration.class,
    ErrorMvcAutoConfiguration.class,
    // Security
    SecurityAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class,
    // KSF Security (JwtReader, JwkSetKeyProvider)
    KsfSecurityAutoConfiguration.class,
    // KSF Integration (CachingIdMappingService, NovaRepositoryRegistry)
    KsfIntegrationAutoConfiguration.class,
    // AOP (fuer PermissionEnforcementAspect)
    AopAutoConfiguration.class
  })
  @Import({
    KsfWebSecurityAutoConfiguration.class,
    SecurityContextUserAccessor.class,
    WhoAmIProbeController.class
  })
  static class ItConfig {

    /** Pflicht fuer KSF-Audit-Felder (ANGELEGT_VON etc.). */
    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "security-bootstrap-it";
    }
  }
}
