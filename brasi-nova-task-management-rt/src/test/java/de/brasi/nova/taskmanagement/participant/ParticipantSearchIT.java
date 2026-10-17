package de.brasi.nova.taskmanagement.participant;

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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Integration-Test fuer den {@link ParticipantSearchController} gegen echtes MSSQL.
 *
 * <p>Beweist:
 * <ul>
 *   <li>LIKE-Suche: nur Tenant-eigene Benutzer werden gefunden.
 *   <li>Keyset-Pagination: Seite1+nextCursor->Seite2, kein Skip/Duplikat.
 *   <li>Routing-Beweis: {@code GET /api/v1/tasks/participants} kollidiert NICHT mit
 *       {@code GET /api/v1/tasks/{id}} (kein 400 durch participants={id}).
 *   <li>Negativ: anonym 401, Token ohne TASK_READ 403.
 * </ul>
 */
@SpringBootTest(
    classes = ParticipantSearchIT.ItConfig.class,
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
class ParticipantSearchIT {

  private static final String TEST_KID = "participant-search-it-key";

  /** Mandant fuer Suchtest. */
  private static final long TENANT_ID = 580L;

  private static final UUID TENANT_UUID = UUID.randomUUID();

  /** Anfrage-Benutzer. */
  private static final UUID ACTOR_REF = UUID.randomUUID();
  private static final String ACTOR_LOGIN = "actor-part-search@brasi.de";
  private static final long ACTOR_DB_ID = 581L;

  /**
   * Drei Such-Benutzer fuer LIKE-Treffer (eindeutiges Praefix "ptit-"):
   * IDs 582-584 (nummerisch aufsteigend fuer stabile EXTERNALID-ORDER BY-Pagination).
   */
  private static final long SEARCH_USER_1_ID = 582L;
  private static final long SEARCH_USER_2_ID = 583L;
  private static final long SEARCH_USER_3_ID = 584L;

  private static final UUID SEARCH_USER_1_REF = UUID.fromString("00000000-0000-0000-0001-000000000582");
  private static final UUID SEARCH_USER_2_REF = UUID.fromString("00000000-0000-0000-0001-000000000583");
  private static final UUID SEARCH_USER_3_REF = UUID.fromString("00000000-0000-0000-0001-000000000584");

  /** Benutzer in anderem Mandanten (darf nicht in Suchergebnissen erscheinen). */
  private static final long OTHER_TENANT_ID = 585L;
  private static final UUID OTHER_TENANT_UUID = UUID.randomUUID();
  private static final long OTHER_USER_ID = 586L;

  /** User ohne TASK_READ-Permission. */
  private static final UUID USER_NO_PERM_REF = UUID.randomUUID();
  private static final String USER_NO_PERM_LOGIN = "no-perm-part@brasi.de";

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
      pemTempFile = Files.createTempFile("participant-search-it-jwk-", ".pem");
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
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_ID, TENANT_UUID);
    seedFirmaIfAbsent(jt, OTHER_TENANT_ID, OTHER_TENANT_UUID);
    seedBenutzerIfAbsent(jt, ACTOR_DB_ID, ACTOR_REF, ACTOR_LOGIN, "IT Actor", TENANT_ID);
    // Drei Such-Benutzer mit festgelegten EXTERNALIDs fuer stabile Pagination
    seedBenutzerWithExternalId(jt, SEARCH_USER_1_ID, SEARCH_USER_1_REF, "ptit-alpha@brasi.de", "ptit Alpha", TENANT_ID);
    seedBenutzerWithExternalId(jt, SEARCH_USER_2_ID, SEARCH_USER_2_REF, "ptit-beta@brasi.de", "ptit Beta", TENANT_ID);
    seedBenutzerWithExternalId(jt, SEARCH_USER_3_ID, SEARCH_USER_3_REF, "ptit-gamma@brasi.de", "ptit Gamma", TENANT_ID);
    // Benutzer in anderem Mandanten (darf nicht in Treffern erscheinen)
    seedBenutzerIfAbsent(jt, OTHER_USER_ID, UUID.randomUUID(), "ptit-other@brasi.de", "ptit Other", OTHER_TENANT_ID);
  }

  // ---- IT 1: LIKE-Suche + Tenant-Isolation ---------------------------------------------------

  @Test
  @DisplayName(
      "suche_findet_nur_eigene_mandanten_benutzer_per_like:"
          + " q=ptit -> Tenant-eigene Treffer, kein Fremd-Mandant-Benutzer")
  void suche_findet_nur_eigene_mandanten_benutzer_per_like() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String token =
        buildJwtWithAuthority(TENANT_UUID, ACTOR_REF, ACTOR_LOGIN, "task-management:TASK_READ");

    final String response =
        mvc.perform(
                get("/api/v1/tasks/participants")
                    .header("Authorization", "Bearer " + token)
                    .param("q", "ptit"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode page = om.readValue(response, JsonNode.class);
    final JsonNode items = page.get("items");
    assertThat(items.isArray()).isTrue();

    // Mindestens die 3 seedierten ptit-Benutzer gefunden
    assertThat(items.size()).isGreaterThanOrEqualTo(3);

    // Kein Fremd-Mandant-Benutzer in der Liste
    final Set<String> foundLogins = new HashSet<>();
    for (JsonNode item : items) {
      foundLogins.add(item.get("loginName").asText());
    }
    assertThat(foundLogins).doesNotContain("ptit-other@brasi.de");
    assertThat(foundLogins).contains("ptit-alpha@brasi.de", "ptit-beta@brasi.de", "ptit-gamma@brasi.de");
  }

  // ---- IT 2: Keyset-Pagination: Seite1+nextCursor->Seite2, kein Skip/Duplikat ----------------

  @Test
  @DisplayName(
      "pagination_seite1_cursor_seite2_kein_duplikat:"
          + " limit=2 bei 3 ptit-Treffern -> Seite1 hasMore=true + nextCursor -> Seite2 ohne Duplikat")
  void pagination_seite1_cursor_seite2_kein_duplikat() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String token =
        buildJwtWithAuthority(TENANT_UUID, ACTOR_REF, ACTOR_LOGIN, "task-management:TASK_READ");

    // Seite 1 (limit=2)
    final String page1Response =
        mvc.perform(
                get("/api/v1/tasks/participants")
                    .header("Authorization", "Bearer " + token)
                    .param("q", "ptit")
                    .param("limit", "2"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode page1 = om.readValue(page1Response, JsonNode.class);
    assertThat(page1.get("hasMore").asBoolean()).as("Seite 1 muss hasMore=true haben").isTrue();
    final String nextCursor = page1.get("nextCursor").asText();
    assertThat(nextCursor).as("nextCursor darf nicht leer sein").isNotBlank();

    final List<String> page1Refs = new ArrayList<>();
    for (JsonNode item : page1.get("items")) {
      page1Refs.add(item.get("userRef").asText());
    }
    assertThat(page1Refs).hasSize(2);

    // Seite 2 (cursor aus Seite 1)
    final String page2Response =
        mvc.perform(
                get("/api/v1/tasks/participants")
                    .header("Authorization", "Bearer " + token)
                    .param("q", "ptit")
                    .param("limit", "2")
                    .param("cursor", nextCursor))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode page2 = om.readValue(page2Response, JsonNode.class);
    final List<String> page2Refs = new ArrayList<>();
    for (JsonNode item : page2.get("items")) {
      page2Refs.add(item.get("userRef").asText());
    }
    assertThat(page2Refs).hasSizeGreaterThanOrEqualTo(1);

    // Kein Duplikat zwischen Seite 1 und Seite 2
    final Set<String> allRefs = new HashSet<>(page1Refs);
    for (String ref : page2Refs) {
      assertThat(allRefs).as("Kein Duplikat: " + ref + " erscheint bereits auf Seite 1").doesNotContain(ref);
    }
  }

  // ---- IT 3: Routing-Beweis participants != {id} + Negativ 401/403 ---------------------------

  @Test
  @DisplayName(
      "routing_participants_kein_id_match_401_anonym_403_keine_permission:"
          + " GET /api/v1/tasks/participants trifft Suche (nicht {id}=participants);"
          + " anonym 401; kein TASK_READ 403")
  void routing_participants_kein_id_match_401_anonym_403_keine_permission() throws Exception {
    final MockMvc mvc = buildMvc();

    // 1. Anonym -> 401 (kein Token)
    mvc.perform(get("/api/v1/tasks/participants"))
        .andExpect(status().isUnauthorized());

    // 2. Token ohne TASK_READ -> 403
    final String tokenNoRead =
        buildJwtWithAuthority(TENANT_UUID, USER_NO_PERM_REF, USER_NO_PERM_LOGIN,
            "task-management:TASK_WRITE");
    mvc.perform(
            get("/api/v1/tasks/participants")
                .header("Authorization", "Bearer " + tokenNoRead))
        .andExpect(status().isForbidden());

    // 3. Routing-Beweis: mit TASK_READ -> 200 (nicht 404 durch {id}=participants)
    final String tokenRead =
        buildJwtWithAuthority(TENANT_UUID, ACTOR_REF, ACTOR_LOGIN, "task-management:TASK_READ");
    mvc.perform(
            get("/api/v1/tasks/participants")
                .header("Authorization", "Bearer " + tokenRead))
        .andExpect(status().isOk());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

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
      final String vollerName,
      final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, LOGIN_NAME, VOLLER_NAME) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?)",
        id, id, externalId, tenantId, loginName, vollerName);
  }

  private static void seedBenutzerWithExternalId(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final String loginName,
      final String vollerName,
      final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, LOGIN_NAME, VOLLER_NAME) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?)",
        id, id, externalId, tenantId, loginName, vollerName);
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  // ---- IT-Konfigurations-Context ------------------------------------------------------------

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
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.dictionary",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.time",
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
      return () -> "participant-search-it";
    }
  }
}
