package de.brasi.nova.taskmanagement.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig;
import de.brasi.nova.taskmanagement.push.NoOpNotificationPushConfig;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
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
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test fuer den {@link DictionaryCatalogController} gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist:
 * <ul>
 *   <li>GET /dictionaries mit TASK_READ-Token: alle 6 Listen nicht leer, je Eintrag code+label
 *   <li>taskPriority sortOrder gesetzt; andere sortOrder=null
 *   <li>Global sichtbar: zwei verschiedene Tenants sehen denselben Katalog (Tenant-0-Dicts)
 *   <li>Negativ: anonym 401, kein TASK_READ 403
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = DictionaryCatalogIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
      "ksf.web-security.enabled=true",
      "ksf.web-security.filter-chain.enabled=true",
      "ksf.web-security.principal.profile-claim.enabled=true",
      "ksf.web-security.tenant.context-binding=FILTER",
      "ksf.web-security.tenant.binding.source=JWT",
      "ksf.web-security.tenant.binding.required=false",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.security.jwt.enabled=true",
      "ksf.module-registration.enabled=false",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class DictionaryCatalogIT {

  private static final String TEST_KID = "dict-it-key";

  /** Mandant A. */
  private static final long TENANT_A_ID = 580L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-dict@brasi.de";
  private static final long USER_A_DB_ID = 581L;

  /** Mandant B: zweiter Tenant fuer Global-sichtbar-Test. */
  private static final long TENANT_B_ID = 582L;

  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "user-b-dict@brasi.de";
  private static final long USER_B_DB_ID = 583L;

  /** User ohne TASK_READ (fuer 403-Test). */
  private static final UUID USER_NO_PERM_REF = UUID.randomUUID();
  private static final String USER_NO_PERM_LOGIN = "user-noperm-dict@brasi.de";

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

    final String pemContent = toPem(RSA_KEY_PAIR.getPublic().getEncoded());
    try {
      pemTempFile = Files.createTempFile("dict-it-jwk-", ".pem");
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

  @Autowired private WebApplicationContext webApplicationContext;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_B_ID);
  }

  // ---- IT 1: GET /dictionaries -> alle 6 Listen nicht leer, code+label gesetzt -----------

  @Test
  @DisplayName("get_dictionaries_liefert_alle_6_listen_mit_code_und_label")
  void get_dictionaries_liefert_alle_6_listen_mit_code_und_label() throws Exception {
    final MockMvc mvc = buildMvc();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_READ");

    final String json = mvc.perform(get("/api/v1/dictionaries")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taskStatus").isArray())
        .andExpect(jsonPath("$.taskPriority").isArray())
        .andExpect(jsonPath("$.taskDirection").isArray())
        .andExpect(jsonPath("$.notificationType").isArray())
        .andExpect(jsonPath("$.notificationSeverity").isArray())
        .andExpect(jsonPath("$.tagSeverity").isArray())
        .andReturn().getResponse().getContentAsString();

    final JsonNode root = new ObjectMapper().readTree(json);

    // Alle 6 Listen nicht leer
    assertThat(root.get("taskStatus").size()).as("taskStatus nicht leer").isGreaterThan(0);
    assertThat(root.get("taskPriority").size()).as("taskPriority nicht leer").isGreaterThan(0);
    assertThat(root.get("taskDirection").size()).as("taskDirection nicht leer").isGreaterThan(0);
    assertThat(root.get("notificationType").size()).as("notificationType nicht leer").isGreaterThan(0);
    assertThat(root.get("notificationSeverity").size()).as("notificationSeverity nicht leer").isGreaterThan(0);
    assertThat(root.get("tagSeverity").size()).as("tagSeverity nicht leer").isGreaterThan(0);

    // Je Eintrag: code und label gesetzt
    for (final String field : List.of("taskStatus", "taskPriority", "taskDirection",
        "notificationType", "notificationSeverity", "tagSeverity")) {
      for (final JsonNode entry : root.get(field)) {
        assertThat(entry.get("id")).as(field + ".id nicht null").isNotNull();
        assertThat(entry.get("code").asText()).as(field + ".code nicht leer").isNotBlank();
        assertThat(entry.get("label").asText()).as(field + ".label nicht leer").isNotBlank();
      }
    }

    // taskPriority[*].sortOrder gesetzt (nicht null)
    for (final JsonNode entry : root.get("taskPriority")) {
      assertThat(entry.get("sortOrder")).as("taskPriority.sortOrder muss gesetzt sein")
          .isNotNull();
      assertThat(entry.get("sortOrder").isNull()).as("taskPriority.sortOrder darf nicht null sein")
          .isFalse();
    }

    // Alle anderen: sortOrder = null (JSON null)
    for (final String field : List.of("taskStatus", "taskDirection",
        "notificationType", "notificationSeverity", "tagSeverity")) {
      for (final JsonNode entry : root.get(field)) {
        final JsonNode sortOrder = entry.get("sortOrder");
        assertThat(sortOrder == null || sortOrder.isNull())
            .as(field + ".sortOrder muss null sein").isTrue();
      }
    }
  }

  // ---- IT 2: Global sichtbar - zwei Tenants sehen denselben Katalog --------------------

  @Test
  @DisplayName("get_dictionaries_gleicher_katalog_fuer_zwei_tenants")
  void get_dictionaries_gleicher_katalog_fuer_zwei_tenants() throws Exception {
    final MockMvc mvc = buildMvc();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_READ");
    final String tokenB = buildJwtWithAuthority(
        TENANT_B_UUID, USER_B_REF, USER_B_LOGIN,
        "task-management:TASK_READ");

    final String jsonA = mvc.perform(get("/api/v1/dictionaries")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    final String jsonB = mvc.perform(get("/api/v1/dictionaries")
            .header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    final JsonNode rootA = new ObjectMapper().readTree(jsonA);
    final JsonNode rootB = new ObjectMapper().readTree(jsonB);

    // Gleiche Anzahl Eintraege in allen Listen (Tenant-0-Dicts sind global)
    assertThat(rootA.get("taskStatus").size())
        .isEqualTo(rootB.get("taskStatus").size());
    assertThat(rootA.get("taskPriority").size())
        .isEqualTo(rootB.get("taskPriority").size());
    assertThat(rootA.get("notificationType").size())
        .isEqualTo(rootB.get("notificationType").size());
  }

  // ---- IT 3: Discriminator - future-dated Eintrag darf nicht im Katalog erscheinen ------

  /**
   * TDD-Discriminator-Test: Eine DIC_TASK_STATUS-Zeile mit GUELTIG_VON = morgen darf im
   * Katalog-Ergebnis NICHT auftauchen.
   *
   * <p>Vor dem Fix (findAllActive = Basis-Criteria) wuerde sie erscheinen, da die Basis-Query
   * kein GUELTIG_VON-Praedikat kennt. Nach dem Fix (findAllValidAndActive mit GUELTIG_VON-Filter)
   * bleibt sie unsichtbar.
   */
  @Test
  @DisplayName("future_dated_dict_eintrag_erscheint_nicht_im_katalog")
  void future_dated_dict_eintrag_erscheint_nicht_im_katalog(
      @Autowired JdbcTemplate jt) throws Exception {
    final long futureEntryId = 59901L;
    final UUID futureEntryUuid = UUID.fromString("00000099-0000-0099-0000-000000059901");
    final String futureCode = "IT_FUTURE";

    // Seed future-dated entry in DIC_TASK_STATUS (GUELTIG_VON = morgen)
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[DIC_TASK_STATUS] WHERE ID = ?) "
            + "INSERT INTO [dbo].[DIC_TASK_STATUS] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, ENTRY_TYPE, CODE, BEZEICHNUNG,"
            + " ANGELEGT_VON, ANGELEGT_AM, GUELTIG_VON, GUELTIG_BIS) "
            + "VALUES (?, 'ACTIVE', ?, 1, 0, 'SYSTEM_PUBLIC', ?, 'IT Future Entry',"
            + " 'it-test', GETDATE(), CAST(DATEADD(day, 1, GETDATE()) AS DATE), NULL)",
        futureEntryId, futureEntryId, futureEntryUuid, futureCode);

    try {
      final MockMvc mvc = buildMvc();
      final String tokenA = buildJwtWithAuthority(
          TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
          "task-management:TASK_READ");

      final String json = mvc.perform(get("/api/v1/dictionaries")
              .header("Authorization", "Bearer " + tokenA))
          .andExpect(status().isOk())
          .andReturn().getResponse().getContentAsString();

      final JsonNode root = new ObjectMapper().readTree(json);
      final JsonNode taskStatusList = root.get("taskStatus");
      final List<String> codes = new java.util.ArrayList<>();
      taskStatusList.forEach(e -> codes.add(e.get("code").asText()));

      assertThat(codes)
          .as("Future-dated DIC_TASK_STATUS-Eintrag IT_FUTURE darf nicht im Katalog erscheinen")
          .doesNotContain(futureCode);
    } finally {
      jt.update("DELETE FROM [dbo].[DIC_TASK_STATUS] WHERE ID = ?", futureEntryId);
    }
  }

  // ---- IT 4: findByCode ignoriert future-dated Eintraege (P2-A) -----------------------

  /**
   * TDD-Discriminator fuer P2-A: ein future-dated DIC_TASK_PRIORITY-Eintrag darf via
   * findByCode NICHT aufgeloest werden. Vor dem Fix (kein GUELTIG_VON-Praedikat) wuerde
   * resolveTaskPriority den Code finden und eine ID liefern; nach dem Fix wirft der Resolver
   * eine DictionaryResolverException.
   */
  @Test
  @DisplayName("findbycode_ignoriert_future_dated_priority_code")
  void findbycode_ignoriert_future_dated_priority_code(
      @Autowired JdbcTemplate jt,
      @Autowired de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver resolver)
      throws Exception {
    final long futureId = 59902L;
    final UUID futureUuid = UUID.fromString("00000099-0000-0099-0000-000000059902");
    final String futureCode = "IT_FUTURE_PRIO";

    // Seed future-dated priority (GUELTIG_VON = morgen)
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[DIC_TASK_PRIORITY] WHERE ID = ?) "
            + "INSERT INTO [dbo].[DIC_TASK_PRIORITY] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, ENTRY_TYPE, CODE, BEZEICHNUNG,"
            + " ANGELEGT_VON, ANGELEGT_AM, GUELTIG_VON, GUELTIG_BIS, SORT_ORDER) "
            + "VALUES (?, 'ACTIVE', ?, 1, 0, 'SYSTEM_PUBLIC', ?, 'IT Future Prio',"
            + " 'it-test', GETDATE(), CAST(DATEADD(day, 1, GETDATE()) AS DATE), NULL, 99)",
        futureId, futureId, futureUuid, futureCode);

    try {
      io.keel.framework.api.security.tenant.TenantContextHolder.runWithTenant(
          io.keel.framework.api.security.tenant.TenantInfo.global(), () -> {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> resolver.resolveTaskPriority(futureCode))
                .as("Future-dated Code darf via findByCode nicht aufgeloest werden")
                .isInstanceOf(de.brasi.nova.taskmanagement.resolver.DictionaryResolverException.class);
            return null;
          });
    } finally {
      jt.update("DELETE FROM [dbo].[DIC_TASK_PRIORITY] WHERE ID = ?", futureId);
    }
  }

  // ---- Negativ-Tests: 401 / 403 -------------------------------------------------------

  @Test
  @DisplayName("negativ_anonym_401_kein_task_read_403")
  void negativ_anonym_401_kein_task_read_403() throws Exception {
    final MockMvc mvc = buildMvc();

    // 1. Anonym -> 401
    mvc.perform(get("/api/v1/dictionaries"))
        .andExpect(status().isUnauthorized());

    // 2. Kein TASK_READ (kein Permission) -> 403
    final String tokenNoPerm = buildJwtWithAuthority(
        TENANT_A_UUID, USER_NO_PERM_REF, USER_NO_PERM_LOGIN
        // keine Authorities
    );
    mvc.perform(get("/api/v1/dictionaries")
            .header("Authorization", "Bearer " + tokenNoPerm))
        .andExpect(status().isForbidden());
  }

  // ---- Hilfsmethoden ------------------------------------------------------------------

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private static String buildJwtWithAuthority(
      final UUID tenantUuid,
      final UUID userUuid,
      final String loginName,
      final String... authorities) {
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
        .claim("authorities", List.of(authorities))
        .claim("global", List.of())
        .claim("byTenant", Map.of())
        .signWith(RSA_KEY_PAIR.getPrivate())
        .compact();
  }

  private static void seedFirmaIfAbsent(
      final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static void seedBenutzerIfAbsent(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final String loginName,
      final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, LOGIN_NAME, VOLLER_NAME) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, 'IT Test User')",
        id, id, externalId, tenantId, loginName);
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  // ---- IT-Konfigurations-Context --------------------------------------------------------

  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @org.springframework.context.annotation.ComponentScan(
      basePackages = {
        "de.brasi.nova.taskmanagement.resolver",
        "de.brasi.nova.taskmanagement.task",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.comment",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.dictionary",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.time",
        "de.brasi.nova.taskmanagement.paging",
      },
      excludeFilters = {
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.ANNOTATION,
            classes = org.springframework.boot.SpringBootConfiguration.class)
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
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    TaskExecutionAutoConfiguration.class,
    ErrorMvcAutoConfiguration.class,
    SecurityAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class,
    KsfSecurityAutoConfiguration.class,
    KsfIntegrationAutoConfiguration.class,
    AopAutoConfiguration.class,
    io.keel.framework.web.autoconfigure.NovaJacksonInfrastructureAutoConfiguration.class,
    io.keel.framework.web.autoconfigure.NovaExceptionHandlingAutoConfiguration.class,
  })
  @Import({
    KsfWebSecurityAutoConfiguration.class,
    SecurityContextUserAccessor.class,
    WhoAmIProbeController.class,
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "dictionary-it";
    }
  }
}
