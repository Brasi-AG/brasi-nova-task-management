package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test fuer {@link UserStateController} gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist:
 * <ul>
 *   <li>PUT pin true -&gt; TASK_USER_STATE.IS_PINNED=1 fuer Actor; anderer Benutzer unberuehrt
 *   <li>PUT flag true -&gt; IS_FLAGGED=1; PUT mute true -&gt; IS_MUTED=1
 *   <li>Inbox-Invariante: Marker-Set veraendert UNSUBSCRIBED_AT NICHT (aktiv bleibt aktiv,
 *       abgemeldet bleibt abgemeldet)
 *   <li>Anonym -&gt; 401; fehlende TASK_READ -&gt; 403; fremder Tenant -&gt; 404
 * </ul>
 *
 * <p>Uebersprungen wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = UserStateControllerIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      // Flyway: Schema via V1 aufbauen
      "spring.flyway.enabled=true",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0",
      "spring.flyway.locations=classpath:db/migration",
      // KSF: Dialekt explizit + kein DDL-Auto
      "ksf.persistence.dialect=MSSQL",
      "ksf.persistence.ddl-auto=none",
      // KSF Filterchain (FILTER-Modus)
      "ksf.web-security.enabled=true",
      "ksf.web-security.filter-chain.enabled=true",
      "ksf.web-security.principal.profile-claim.enabled=true",
      "ksf.web-security.tenant.context-binding=FILTER",
      "ksf.web-security.tenant.binding.source=JWT",
      "ksf.web-security.tenant.binding.required=false",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.security.jwt.enabled=true",
      // Modul-Registrierung deaktivieren
      "ksf.module-registration.enabled=false",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class UserStateControllerIT {

  private static final String TEST_KID = "userstate-it-key";

  /** Mandant A: Haupt-Tenant fuer positive Tests. */
  private static final long TENANT_A_ID = 520L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-state@brasi.de";
  private static final long USER_A_DB_ID = 521L;

  /** Zweiter Benutzer im selben Mandanten: fuer Pro-Benutzer-Isolation und Inbox-Invariante. */
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "user-b-state@brasi.de";
  private static final long USER_B_DB_ID = 522L;

  /** Mandant B: fremder Tenant fuer 404-Test. */
  private static final long TENANT_B_ID = 523L;

  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID USER_C_REF = UUID.randomUUID();
  private static final String USER_C_LOGIN = "user-c-state@brasi.de";
  private static final long USER_C_DB_ID = 524L;

  /** Benutzer ohne TASK_READ-Permission fuer 403-Test. */
  private static final UUID USER_NO_PERM_REF = UUID.randomUUID();
  private static final String USER_NO_PERM_LOGIN = "no-perm-state@brasi.de";

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
      pemTempFile = Files.createTempFile("userstate-it-jwk-", ".pem");
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
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_C_DB_ID, USER_C_REF, USER_C_LOGIN, TENANT_B_ID);
  }

  // ---- Positiv-IT: pin true -> IS_PINNED=1 fuer Actor; anderer Benutzer unberuehrt ------------

  @Test
  @DisplayName(
      "pin_setzt_is_pinned_fuer_actor_anderer_benutzer_unberuehrt: PUT pin -> IS_PINNED=1"
          + " fuer User A; User B hat eigenen State ohne IS_PINNED")
  void pin_setzt_is_pinned_fuer_actor_anderer_benutzer_unberuehrt() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // User A legt Task an (wird Creator + UserState)
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String taskId = createTask(mvc, om, tokenA, "Pin-IT-Task");

    // User B wird als Assignee hinzugefuegt (bekommt UserState)
    final String assignBody =
        om.writeValueAsString(Map.of("assignees", List.of(USER_B_REF.toString())));
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignBody))
        .andExpect(status().isNoContent());

    // User A pinnt
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/pin")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isNoContent());

    // Beweis 1: IS_PINNED=1 fuer User A
    final Integer pinnedForA =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.IS_PINNED = 1",
            Integer.class,
            UUID.fromString(taskId),
            USER_A_REF);
    assertThat(pinnedForA)
        .as("IS_PINNED=1 muss fuer User A gesetzt sein")
        .isEqualTo(1);

    // Beweis 2: IS_PINNED fuer User B ist unveraendert (0)
    final Integer pinnedForB =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.IS_PINNED = 0",
            Integer.class,
            UUID.fromString(taskId),
            USER_B_REF);
    assertThat(pinnedForB)
        .as("IS_PINNED fuer User B muss unveraendert 0 sein (pro-Benutzer-Isolation)")
        .isEqualTo(1);
  }

  // ---- Positiv-IT: flag und mute setzbar -------------------------------------------------------

  @Test
  @DisplayName("flag_und_mute_setzbar: PUT flag true -> IS_FLAGGED=1; PUT mute true -> IS_MUTED=1")
  void flag_und_mute_setzbar() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String taskId = createTask(mvc, om, tokenA, "Flag-Mute-IT-Task");

    // flag
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/flag")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isNoContent());

    final Integer flagged =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.IS_FLAGGED = 1",
            Integer.class,
            UUID.fromString(taskId),
            USER_A_REF);
    assertThat(flagged).as("IS_FLAGGED=1 muss gesetzt sein").isEqualTo(1);

    // mute
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/mute")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isNoContent());

    final Integer muted =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.IS_MUTED = 1",
            Integer.class,
            UUID.fromString(taskId),
            USER_A_REF);
    assertThat(muted).as("IS_MUTED=1 muss gesetzt sein").isEqualTo(1);
  }

  // ---- Inbox-Invariante IT: Marker-Set veraendert UNSUBSCRIBED_AT NICHT -----------------------

  @Test
  @DisplayName(
      "marker_set_veraendert_unsubscribed_at_nicht: aktiver Benutzer bleibt aktiv;"
          + " abgemeldeter Benutzer bleibt abgemeldet nach Marker-Set")
  void marker_set_veraendert_unsubscribed_at_nicht() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Task anlegen (User A = Creator, aktiver UserState ohne UNSUBSCRIBED_AT)
    final String taskId = createTask(mvc, om, tokenA, "Inbox-Invariante-IT-Task");

    // Vorbedingung: User A hat KEIN UNSUBSCRIBED_AT (aktiver State)
    final Integer activeBeforePin =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.UNSUBSCRIBED_AT IS NULL",
            Integer.class,
            UUID.fromString(taskId),
            USER_A_REF);
    assertThat(activeBeforePin)
        .as("User A muss vor pin() einen aktiven UserState haben (UNSUBSCRIBED_AT IS NULL)")
        .isEqualTo(1);

    // Aktiver Benutzer pinnt: UNSUBSCRIBED_AT darf danach immer noch NULL sein
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/pin")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isNoContent());

    final Integer activeAfterPin =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.UNSUBSCRIBED_AT IS NULL",
            Integer.class,
            UUID.fromString(taskId),
            USER_A_REF);
    assertThat(activeAfterPin)
        .as("UNSUBSCRIBED_AT muss nach pin() fuer aktiven Benutzer weiterhin NULL sein"
            + " (Inbox-Invariante: ensure veraendert UNSUBSCRIBED_AT NICHT)")
        .isEqualTo(1);

    // Abgemeldeter Benutzer: User B wird als Assignee hinzugefuegt, dann abgemeldet
    final String assignBody =
        om.writeValueAsString(Map.of("assignees", List.of(USER_B_REF.toString())));
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignBody))
        .andExpect(status().isNoContent());

    // User B abmelden (EXIT_KIND=UNSUBSCRIBED via PUT mit leerem assignees)
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of()))))
        .andExpect(status().isNoContent());

    // Vorbedingung: User B hat jetzt UNSUBSCRIBED_AT gesetzt
    final Integer unsubForB =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.UNSUBSCRIBED_AT IS NOT NULL",
            Integer.class,
            UUID.fromString(taskId),
            USER_B_REF);
    assertThat(unsubForB)
        .as("User B muss nach Abmeldung UNSUBSCRIBED_AT gesetzt haben")
        .isEqualTo(1);

    // User B pinnt (hat noch TASK_READ via Token mit TASK_WRITE+TASK_READ)
    final String tokenB = buildJwtWithAuthority(
        TENANT_A_UUID, USER_B_REF, USER_B_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/pin")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isNoContent());

    // Beweis Inbox-Invariante: UNSUBSCRIBED_AT fuer User B ist NOCH IMMER gesetzt
    // (ensure aendert UNSUBSCRIBED_AT NICHT -> abgemeldeter bleibt abgemeldet)
    final Integer unsubForBAfterPin =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.UNSUBSCRIBED_AT IS NOT NULL",
            Integer.class,
            UUID.fromString(taskId),
            USER_B_REF);
    assertThat(unsubForBAfterPin)
        .as("UNSUBSCRIBED_AT fuer abgemeldeten Benutzer darf durch pin() NICHT geloescht werden"
            + " (Inbox-Invariante: ensure veraendert UNSUBSCRIBED_AT NICHT)")
        .isEqualTo(1);

    // Und IS_PINNED ist trotzdem gesetzt worden
    final Integer pinnedForB =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.IS_PINNED = 1",
            Integer.class,
            UUID.fromString(taskId),
            USER_B_REF);
    assertThat(pinnedForB)
        .as("IS_PINNED=1 muss trotz UNSUBSCRIBED_AT fuer User B gesetzt sein")
        .isEqualTo(1);
  }

  // ---- Negativ-IT: 401 / 403 / 404 -----------------------------------------------------------

  @Test
  @DisplayName("anonym_401_falsche_permission_403_fremder_tenant_404")
  void anonym_401_falsche_permission_403_fremder_tenant_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // 1. Anonym -> 401
    mvc.perform(
            put("/api/v1/tasks/" + UUID.randomUUID() + "/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isUnauthorized());

    // 2. Fehlende TASK_READ-Permission (kein task-management:TASK_READ) -> 403
    final String tokenNoPerm = buildJwtWithAuthority(
        TENANT_A_UUID, USER_NO_PERM_REF, USER_NO_PERM_LOGIN
        /* keine Authorities */);
    mvc.perform(
            put("/api/v1/tasks/" + UUID.randomUUID() + "/pin")
                .header("Authorization", "Bearer " + tokenNoPerm)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isForbidden());

    // 3. Fremder Tenant -> 404
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String taskId = createTask(mvc, om, tokenA, "Fremd-Tenant-Pin-Test");

    // User C (Mandant B) versucht pin -> 404 (Task gehoert Mandant A)
    final String tokenC = buildJwtWithAuthority(
        TENANT_B_UUID, USER_C_REF, USER_C_LOGIN,
        "task-management:TASK_READ");
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/pin")
                .header("Authorization", "Bearer " + tokenC)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isNotFound());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  /** Legt einen Task an und gibt die externe UUID (als String) zurueck. */
  private static String createTask(
      final MockMvc mvc, final ObjectMapper om, final String token, final String title)
      throws Exception {
    final String body = om.writeValueAsString(Map.of("title", title));
    final String response =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(response, com.fasterxml.jackson.databind.JsonNode.class)
        .get("id")
        .asText();
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
        "de.brasi.nova.taskmanagement.time",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
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
      return () -> "userstate-it";
    }
  }
}
