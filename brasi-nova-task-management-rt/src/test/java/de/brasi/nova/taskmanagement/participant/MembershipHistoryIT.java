package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig;
import de.brasi.nova.taskmanagement.push.NoOpNotificationPushConfig;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Intake #180: Der Aufgabenverlauf zeigt Mitgliedschaftsaenderungen, je betroffener Person ein
 * Eintrag mit der handelnden Person als Akteur und dem Namen der betroffenen Person im Detail
 * (echtes MSSQL, Wegwerf-DB).
 *
 * <p>Je Pfad ein Fall ueber die HTTP-Endpunkte: {@code PUT /assignees} (zugewiesen, Zuweisung
 * entfernt, auch beim Ersteller), {@code POST /participants} (hinzugefuegt, auch reaktiviert),
 * {@code POST /comments} mit Erwaehnung eines Nicht-Mitglieds (per Erwaehnung hinzugefuegt) und
 * {@code POST /exit} UNSUBSCRIBED (verlassen, Bestand). Gezaehlt wird in {@code TASK_HISTORY_ENTRY}
 * als Differenz vorher und nachher, weil die Methoden der Klasse eine Datenbank teilen; jede
 * Methode legt ihre eigene Aufgabe an.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = MembershipHistoryIT.ItConfig.class,
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
class MembershipHistoryIT {

  private static final String TEST_KID = "membership-history-it-key";

  /** Verlaufstext einer neuen Zuweisung per PUT /assignees. */
  private static final String ASSIGNED = "hat eine Person zugewiesen";

  /** Verlaufstext einer entfallenen Zuweisung per PUT /assignees. */
  private static final String UNASSIGNED = "hat eine Zuweisung entfernt";

  /** Verlaufstext einer Aufnahme per POST /participants. */
  private static final String PARTICIPANT_ADDED = "hat einen Beteiligten hinzugefuegt";

  /** Verlaufstext einer Aufnahme per Erwaehnung im Kommentar. */
  private static final String PARTICIPANT_MENTIONED =
      "hat einen Beteiligten per Erwaehnung hinzugefuegt";

  /** Vorhandener Verlaufstext des Austritts UNSUBSCRIBED. */
  private static final String LEFT = "hat die Aufgabe verlassen";

  /** Vorhandener Verlaufstext des Entfernens (Intake #145). */
  private static final String REMOVED = "hat einen Beteiligten entfernt";

  private static final long TENANT_ID = 1800L;
  private static final UUID TENANT_UUID = UUID.randomUUID();

  /** Ersteller und Akteur aller Aenderungen (TASK_WRITE). */
  private static final UUID ANNA_REF = UUID.randomUUID();

  private static final String ANNA_LOGIN = "hist-anna@brasi.de";
  private static final String ANNA_NAME = "Hist Anna Ersteller";

  private static final UUID BERND_REF = UUID.randomUUID();
  private static final String BERND_LOGIN = "hist-bernd@brasi.de";
  private static final String BERND_NAME = "Hist Bernd Beteiligt";

  private static final UUID CARLA_REF = UUID.randomUUID();
  private static final String CARLA_LOGIN = "hist-carla@brasi.de";
  private static final String CARLA_NAME = "Hist Carla Zugewiesen";

  private static final UUID DORA_REF = UUID.randomUUID();
  private static final String DORA_LOGIN = "hist-dora@brasi.de";
  private static final String DORA_NAME = "Hist Dora Dritte";

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
      pemTempFile = Files.createTempFile("membership-history-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Konnte temporaere PEM-Datei nicht erstellen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem", () -> "file:" + pemTempFile.toAbsolutePath());
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
          // Aufraeumen best effort
        }
      }
    }
  }

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        TENANT_ID,
        TENANT_ID,
        TENANT_UUID,
        TENANT_ID);
    // Unterschiedliche Anzeigenamen (VOLLER_NAME), damit das Detail die betroffene Person belegt.
    seedUser(jt, 1801L, ANNA_REF, ANNA_LOGIN, ANNA_NAME);
    seedUser(jt, 1802L, BERND_REF, BERND_LOGIN, BERND_NAME);
    seedUser(jt, 1803L, CARLA_REF, CARLA_LOGIN, CARLA_NAME);
    seedUser(jt, 1804L, DORA_REF, DORA_LOGIN, DORA_NAME);
  }

  @Test
  @DisplayName(
      "i180_put_je_person_ein_eintrag: PUT +C ergibt genau einen Eintrag 'hat eine Person"
          + " zugewiesen' (Akteur A, Detail Name C); PUT mit +B und -C (C kein Ersteller) ergibt"
          + " genau zwei neue Eintraege, zugewiesen B und Zuweisung entfernt C; derselbe PUT"
          + " nochmals ergibt keinen neuen Eintrag")
  void i180_put_add_and_remove_one_entry_per_person() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token = annaToken();
    final UUID taskId = createTask(mvc, om, token, "I180-PUT");
    final long dbId = taskDbId(taskId);

    final int beforeFirst = historyTotal(dbId);
    putAssignees(mvc, om, token, taskId, ANNA_REF, CARLA_REF).andExpect(status().isNoContent());
    assertThat(historyTotal(dbId) - beforeFirst).as("PUT +C: genau ein Eintrag").isEqualTo(1);
    assertThat(historyEntries(dbId, ANNA_REF, ASSIGNED, CARLA_NAME)).isEqualTo(1);

    final int beforeSwap = historyTotal(dbId);
    putAssignees(mvc, om, token, taskId, ANNA_REF, BERND_REF).andExpect(status().isNoContent());
    assertThat(historyTotal(dbId) - beforeSwap)
        .as("PUT +B -C: genau zwei neue Eintraege, je Person einer")
        .isEqualTo(2);
    assertThat(historyEntries(dbId, ANNA_REF, ASSIGNED, BERND_NAME)).isEqualTo(1);
    assertThat(historyEntries(dbId, ANNA_REF, UNASSIGNED, CARLA_NAME)).isEqualTo(1);

    final int beforeNoOp = historyTotal(dbId);
    putAssignees(mvc, om, token, taskId, ANNA_REF, BERND_REF).andExpect(status().isNoContent());
    assertThat(historyTotal(dbId) - beforeNoOp).as("PUT ohne Aenderung: kein Eintrag").isZero();
  }

  @Test
  @DisplayName(
      "i180_put_ersteller_de_assign: PUT ohne den Ersteller (er bleibt Mitglied) ergibt 'hat eine"
          + " Zuweisung entfernt' mit dem Namen des Erstellers und 'hat eine Person zugewiesen'"
          + " fuer die neue Person, genau zwei neue Eintraege")
  void i180_put_creator_deassign_records_unassigned_with_creator_name() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token = annaToken();
    final UUID taskId = createTask(mvc, om, token, "I180-Ersteller-De-Assign");
    final long dbId = taskDbId(taskId);

    final int before = historyTotal(dbId);
    putAssignees(mvc, om, token, taskId, BERND_REF).andExpect(status().isNoContent());

    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = ?"
                    + " AND PARTICIPANT_REF = ? AND CREATOR_FLAG = 1 AND ASSIGNEE_FLAG = 0"
                    + " AND EXIT_KIND IS NULL",
                dbId,
                ANNA_REF))
        .as("Ersteller nur de-assignt, kein Austritt")
        .isEqualTo(1);
    assertThat(historyTotal(dbId) - before).as("genau zwei neue Eintraege").isEqualTo(2);
    assertThat(historyEntries(dbId, ANNA_REF, UNASSIGNED, ANNA_NAME))
        .as("Zuweisung des Erstellers entfernt, Detail mit seinem Namen")
        .isEqualTo(1);
    assertThat(historyEntries(dbId, ANNA_REF, ASSIGNED, BERND_NAME)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i180_hinzufuegen_je_person_ein_eintrag: POST mit B und C ergibt genau zwei Eintraege 'hat"
          + " einen Beteiligten hinzugefuegt'; B erneut ergibt keinen; B, C und D ergibt genau"
          + " einen (D); nach dem Entfernen von B ergibt das Wiederaufnehmen genau einen weiteren"
          + " fuer B")
  void i180_post_participants_one_entry_per_added_person_none_when_idempotent() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token = annaToken();
    final UUID taskId = createTask(mvc, om, token, "I180-Hinzufuegen");
    final long dbId = taskDbId(taskId);

    final int beforeFirst = historyTotal(dbId);
    postParticipants(mvc, om, token, taskId, BERND_REF, CARLA_REF)
        .andExpect(status().isNoContent());
    assertThat(historyTotal(dbId) - beforeFirst).as("zwei neue Personen").isEqualTo(2);
    assertThat(historyEntries(dbId, ANNA_REF, PARTICIPANT_ADDED, BERND_NAME)).isEqualTo(1);
    assertThat(historyEntries(dbId, ANNA_REF, PARTICIPANT_ADDED, CARLA_NAME)).isEqualTo(1);

    final int beforeIdempotent = historyTotal(dbId);
    postParticipants(mvc, om, token, taskId, BERND_REF).andExpect(status().isNoContent());
    assertThat(historyTotal(dbId) - beforeIdempotent)
        .as("bereits aktive Person: kein Eintrag")
        .isZero();

    final int beforeMixed = historyTotal(dbId);
    postParticipants(mvc, om, token, taskId, BERND_REF, CARLA_REF, DORA_REF)
        .andExpect(status().isNoContent());
    assertThat(historyTotal(dbId) - beforeMixed).as("nur D ist neu").isEqualTo(1);
    assertThat(historyEntries(dbId, ANNA_REF, PARTICIPANT_ADDED, DORA_NAME)).isEqualTo(1);

    mvc.perform(
            delete("/api/v1/tasks/" + taskId + "/participants/" + BERND_REF)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
    assertThat(historyEntries(dbId, ANNA_REF, REMOVED, BERND_NAME)).isEqualTo(1);

    final int beforeReactivate = historyTotal(dbId);
    postParticipants(mvc, om, token, taskId, BERND_REF).andExpect(status().isNoContent());
    assertThat(historyTotal(dbId) - beforeReactivate)
        .as("ausgetretene Person reaktiviert: genau ein Eintrag")
        .isEqualTo(1);
    assertThat(historyEntries(dbId, ANNA_REF, PARTICIPANT_ADDED, BERND_NAME)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "i180_erwaehnung: Kommentar mit Erwaehnung eines Nicht-Mitglieds ergibt genau einen Eintrag"
          + " 'hat einen Beteiligten per Erwaehnung hinzugefuegt' (Akteur A, Detail Name B), keinen"
          + " mit dem POST-Text; die erneute Erwaehnung des nun Mitglieds ergibt keinen Eintrag")
  void i180_mention_non_member_one_entry_member_none() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token = annaToken();
    final UUID taskId = createTask(mvc, om, token, "I180-Erwaehnung");
    final long dbId = taskDbId(taskId);

    final int beforeMention = historyTotal(dbId);
    postCommentMentioning(mvc, om, token, taskId, BERND_REF).andExpect(status().isCreated());
    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = ?"
                    + " AND PARTICIPANT_REF = ? AND PARTICIPANT_FLAG = 1 AND EXIT_KIND IS NULL",
                dbId,
                BERND_REF))
        .as("B per Erwaehnung als Beteiligter aufgenommen")
        .isEqualTo(1);
    assertThat(historyTotal(dbId) - beforeMention).as("genau ein Eintrag").isEqualTo(1);
    assertThat(historyEntries(dbId, ANNA_REF, PARTICIPANT_MENTIONED, BERND_NAME)).isEqualTo(1);
    assertThat(historyEntries(dbId, ANNA_REF, PARTICIPANT_ADDED, BERND_NAME))
        .as("nicht der Text des POST /participants")
        .isZero();

    final int beforeMemberMention = historyTotal(dbId);
    postCommentMentioning(mvc, om, token, taskId, BERND_REF).andExpect(status().isCreated());
    assertThat(historyTotal(dbId) - beforeMemberMention)
        .as("Erwaehnung eines Mitglieds: kein Eintrag")
        .isZero();
  }

  @Test
  @DisplayName(
      "i180_austritt_unsubscribed: POST /exit UNSUBSCRIBED eines Beteiligten ergibt genau einen"
          + " Eintrag 'hat die Aufgabe verlassen' mit ihm als Akteur und seinem Namen (Bestand)")
  void i180_exit_unsubscribed_exactly_one_left_entry() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token = annaToken();
    final UUID taskId = createTask(mvc, om, token, "I180-Austritt");
    final long dbId = taskDbId(taskId);
    postParticipants(mvc, om, token, taskId, BERND_REF).andExpect(status().isNoContent());

    final int before = historyTotal(dbId);
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + buildJwt(BERND_REF, BERND_LOGIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("exitKind", "UNSUBSCRIBED"))))
        .andExpect(status().isNoContent());

    assertThat(historyTotal(dbId) - before).as("genau ein Eintrag").isEqualTo(1);
    assertThat(historyEntries(dbId, BERND_REF, LEFT, BERND_NAME)).isEqualTo(1);
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  /** Alle Verlaufseintraege der Aufgabe. */
  private int historyTotal(final long taskDbId) {
    return count("SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] WHERE TASK_ID = ?", taskDbId);
  }

  /** Verlaufseintraege der Aufgabe mit Akteur, Text und Detail (Namen-Snapshot). */
  private int historyEntries(
      final long taskDbId, final UUID actorRef, final String text, final String detail) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] WHERE TASK_ID = ? AND ACTOR_REF = ?"
            + " AND TEXT = ? AND CHANGE_DETAIL = ?",
        taskDbId,
        actorRef,
        text,
        detail);
  }

  private Integer count(final String sql, final Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  /** Interne TASK.ID zur externen UUID. */
  private long taskDbId(final UUID taskExternalId) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskExternalId);
  }

  private UUID createTask(
      final MockMvc mvc, final ObjectMapper om, final String token, final String title)
      throws Exception {
    final String resp =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", title))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(resp, JsonNode.class).get("id").traverse(om).readValueAs(UUID.class);
  }

  /** {@code PUT /assignees} mit genau den gegebenen Personen (Status prueft der Aufrufer). */
  private ResultActions putAssignees(
      final MockMvc mvc,
      final ObjectMapper om,
      final String token,
      final UUID taskId,
      final UUID... assignees)
      throws Exception {
    return mvc.perform(
        put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", List.of(assignees)))));
  }

  /** {@code POST /participants} mit den gegebenen Personen (Status prueft der Aufrufer). */
  private ResultActions postParticipants(
      final MockMvc mvc,
      final ObjectMapper om,
      final String token,
      final UUID taskId,
      final UUID... participantIds)
      throws Exception {
    return mvc.perform(
        post("/api/v1/tasks/" + taskId + "/participants")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("participantIds", List.of(participantIds)))));
  }

  /** Top-Level-Kommentar mit Erwaehnung genau einer Person (Status prueft der Aufrufer). */
  private ResultActions postCommentMentioning(
      final MockMvc mvc,
      final ObjectMapper om,
      final String token,
      final UUID taskId,
      final UUID mentioned)
      throws Exception {
    return mvc.perform(
        post("/api/v1/tasks/" + taskId + "/comments")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                om.writeValueAsString(
                    Map.of("text", "Bitte ansehen.", "mentions", List.of(mentioned)))));
  }

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  /** JWT der Erstellerin mit TASK_WRITE (Zuweisen, Hinzufuegen, Aufnahme per Erwaehnung). */
  private static String annaToken() {
    return buildJwt(
        ANNA_REF, ANNA_LOGIN, "task-management:TASK_WRITE", "task-management:TASK_READ");
  }

  private static String buildJwt(
      final UUID userUuid, final String loginName, final String... authorities) {
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
        .claim("lastActiveTenantId", TENANT_UUID.toString())
        .claim("profile", profileJson)
        .claim("authorities", List.of(authorities))
        .claim("global", List.of())
        .claim("byTenant", Map.of())
        .signWith(RSA_KEY_PAIR.getPrivate())
        .compact();
  }

  private static void seedUser(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final String loginName,
      final String fullName) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, LOGIN_NAME, VOLLER_NAME) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?)",
        id,
        id,
        externalId,
        TENANT_ID,
        loginName,
        fullName);
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  // ---- IT-Konfigurations-Context ------------------------------------------------------------

  /** Kontext wie {@code CommentControllerIT}: Zuweisen, Hinzufuegen, Austritt und Kommentar. */
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
        "de.brasi.nova.taskmanagement.attachment",
        "de.brasi.nova.taskmanagement.task",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.comment",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.notification",
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
    TaskMembershipGuard.class,
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "membership-history-it";
    }
  }
}
