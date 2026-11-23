package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integrationstest fuer den Gesehen-Marker und den Austrittszeitpunkt (Intake #150) gegen echtes
 * MSSQL (Wegwerf-DB).
 *
 * <p>Beweist:
 *
 * <ul>
 *   <li>{@code PUT /api/v1/tasks/{id}/seen}: 204, idempotent, setzt {@code SEEN_AT} des Anfragers;
 *       neutrale 404 fuer unbekannte und fremde Aufgabe sowie Nicht-Mitglieder (ohne State-Zeile);
 *       401 ohne JWT, 400 ohne UUID im Pfad; die Fehlerantworten entsprechen den
 *       OpenAPI-Beispielen.
 *   <li>{@code isNew} in {@code GET /comments}: erster Besuch alle fremden neu, eigene nie, nach
 *       dem Markieren nur spaetere fremde Kommentare.
 *   <li>{@code lastSeenAt} im Detail; lesende Aufrufe (Detail, Kommentare, Snapshot, Archiv)
 *       veraendern {@code SEEN_AT} nicht.
 *   <li>{@code exit.exitedAt} bei {@code unsubscribed} und {@code handover} in Detail und Archiv,
 *       {@code exit = null} nach Wiedereintritt, Historie bleibt.
 *   <li>Ein Ausgetretener liest nach seinem Austritt entstandene Kommentare und Historieneintraege
 *       und darf die Aufgabe als gesehen markieren.
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskSeenMarkerIT.ItConfig.class,
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
class TaskSeenMarkerIT {

  private static final String TEST_KID = "seen-marker-it-key";

  private static final long TENANT_A_ID = 1501L;
  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final long TENANT_B_ID = 1502L;
  private static final UUID TENANT_B_UUID = UUID.randomUUID();

  /** Ersteller und Kommentator (mit TASK_WRITE). */
  private static final UUID USER_A_REF = UUID.randomUUID();

  private static final String USER_A_LOGIN = "user-a-seen@brasi.de";
  private static final long USER_A_DB_ID = 15011L;

  /** Mitglied ohne Rechte (Selbstbedienung): markiert, liest, tritt aus. */
  private static final UUID USER_B_REF = UUID.randomUUID();

  private static final String USER_B_LOGIN = "user-b-seen@brasi.de";
  private static final long USER_B_DB_ID = 15012L;

  /** Nutzer desselben Mandanten ohne Mitgliedschaft. */
  private static final UUID USER_D_REF = UUID.randomUUID();

  private static final String USER_D_LOGIN = "user-d-seen@brasi.de";
  private static final long USER_D_DB_ID = 15014L;

  /** Nutzer eines fremden Mandanten. */
  private static final UUID USER_C_REF = UUID.randomUUID();

  private static final String USER_C_LOGIN = "user-c-seen@brasi.de";
  private static final long USER_C_DB_ID = 15013L;

  /** Aufgaben-UUID in den OpenAPI-Beispielen von {@code seen} (wird ersetzt). */
  private static final String EXAMPLE_TASK_ID = "3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b";

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
      pemTempFile = Files.createTempFile("seen-marker-it-jwk-", ".pem");
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
          // Aufraeumen nach bestem Bemuehen
        }
      }
    }
  }

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuditTimeConverter auditTimeConverter;

  private final ObjectMapper om = new ObjectMapper();

  @BeforeAll
  static void seedPrerequisites(@Autowired final JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_C_DB_ID, USER_C_REF, USER_C_LOGIN, TENANT_B_ID);
    seedBenutzerIfAbsent(jt, USER_D_DB_ID, USER_D_REF, USER_D_LOGIN, TENANT_A_ID);
  }

  // ---- Gesehen-Marker und isNew -----------------------------------------------------------------

  @Test
  @DisplayName(
      "seen_204_idempotent_is_new_und_last_seen_at: erster Besuch alle fremden neu, eigene nie;"
          + " nach PUT seen nur spaetere fremde; GETs veraendern SEEN_AT nicht")
  void seen_204_idempotent_is_new_und_last_seen_at() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = tokenA();
    final String tokenB = tokenB();
    final UUID taskUuid = createTask(mvc, tokenA, "Seen-IT-1");
    putAssignees(mvc, tokenA, taskUuid, USER_B_REF);

    final UUID ownB = postComment(mvc, tokenB, taskUuid, "eigener Kommentar von B");
    final UUID fromA1 = postComment(mvc, tokenA, taskUuid, "Kommentar von A vor dem Markieren");

    // Erster Besuch von B: kein Marker -> der fremde Kommentar ist neu, der eigene nie.
    assertThat(seenAt(taskUuid, USER_B_REF)).isNull();
    assertThat(isNewById(getComments(mvc, tokenB, taskUuid)))
        .containsEntry(ownB, false)
        .containsEntry(fromA1, true);
    final JsonNode detailBefore = getDetail(mvc, tokenB, taskUuid);
    assertThat(detailBefore.has("lastSeenAt")).as("Feld lastSeenAt im Wire").isTrue();
    assertThat(detailBefore.get("lastSeenAt").isNull()).isTrue();
    // GET /tasks/{id} setzt READ_AT, aber nie SEEN_AT.
    assertThat(seenAt(taskUuid, USER_B_REF)).isNull();
    assertThat(readAt(taskUuid, USER_B_REF)).isNotNull();

    // PUT seen -> 204 ohne Inhalt, SEEN_AT gesetzt.
    mvc.perform(put("/api/v1/tasks/" + taskUuid + "/seen").header("Authorization", bearer(tokenB)))
        .andExpect(status().isNoContent())
        .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    final LocalDateTime firstSeen = seenAt(taskUuid, USER_B_REF);
    assertThat(firstSeen).isNotNull();

    // Idempotent: zweiter Aufruf erneut 204, Zeitpunkt nicht frueher.
    mvc.perform(put("/api/v1/tasks/" + taskUuid + "/seen").header("Authorization", bearer(tokenB)))
        .andExpect(status().isNoContent());
    final LocalDateTime secondSeen = seenAt(taskUuid, USER_B_REF);
    assertThat(secondSeen).isAfterOrEqualTo(firstSeen);
    assertThat(userStateRowCount(taskUuid, USER_B_REF)).isEqualTo(1);

    // Detail liefert lastSeenAt als UTC-Instant des gespeicherten Werts.
    final JsonNode detail = getDetail(mvc, tokenB, taskUuid);
    assertThat(Instant.parse(detail.get("lastSeenAt").asText()))
        .isEqualTo(auditTimeConverter.toInstant(secondSeen));
    assertThat(detail.get("lastSeenAt").asText()).endsWith("Z");

    // Nach dem Markieren: alter fremder Kommentar nicht mehr neu, spaeterer fremder neu, eigene
    // nie.
    final UUID fromA2 = postComment(mvc, tokenA, taskUuid, "Kommentar von A nach dem Markieren");
    final UUID ownB2 = postComment(mvc, tokenB, taskUuid, "zweiter eigener Kommentar von B");
    final Map<UUID, Boolean> afterSeen = isNewById(getComments(mvc, tokenB, taskUuid));
    assertThat(afterSeen)
        .containsEntry(ownB, false)
        .containsEntry(fromA1, false)
        .containsEntry(fromA2, true)
        .containsEntry(ownB2, false);

    // Lesende Aufrufe (Detail, Kommentare, Snapshot, Archiv) veraendern SEEN_AT nie.
    getDetail(mvc, tokenB, taskUuid);
    getComments(mvc, tokenB, taskUuid);
    getJson(mvc, get("/api/v1/tasks/snapshot"), tokenB);
    getJson(mvc, get("/api/v1/tasks/archive"), tokenB);
    assertThat(seenAt(taskUuid, USER_B_REF)).isEqualTo(secondSeen);
    // Der Marker ist persoenlich: A hat weiter keinen.
    assertThat(seenAt(taskUuid, USER_A_REF)).isNull();
    assertThat(isNewById(getComments(mvc, tokenA, taskUuid)))
        .containsEntry(ownB, true)
        .containsEntry(fromA1, false)
        .containsEntry(fromA2, false)
        .containsEntry(ownB2, true);
  }

  @Test
  @DisplayName(
      "seen_404_fuer_unbekannt_fremd_und_nicht_mitglied_ohne_state: neutrale 404 wie die"
          + " Beispiele, keine State-Zeile; 401 ohne JWT, 400 ohne UUID")
  void seen_404_fuer_unbekannt_fremd_und_nicht_mitglied_ohne_state() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = tokenA();
    final UUID taskUuid = createTask(mvc, tokenA, "Seen-IT-404");
    final SoftAssertions soft = new SoftAssertions();

    // Unbekannte Aufgabe.
    final UUID unknown = UUID.randomUUID();
    assertMatchesExample(
        soft,
        mvc.perform(
                put("/api/v1/tasks/" + unknown + "/seen").header("Authorization", bearer(tokenA)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        ParticipantApiExamples.NOT_FOUND_SEEN,
        unknown);

    // Nicht-Mitglied desselben Mandanten: gleiche 404, keine State-Zeile angelegt.
    final String tokenD = buildJwt(TENANT_A_UUID, USER_D_REF, USER_D_LOGIN);
    final String nonMember =
        mvc.perform(
                put("/api/v1/tasks/" + taskUuid + "/seen").header("Authorization", bearer(tokenD)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertMatchesExample(soft, nonMember, ParticipantApiExamples.NOT_FOUND_SEEN, taskUuid);
    soft.assertThat(userStateRowCount(taskUuid, USER_D_REF)).isZero();

    // Fremder Mandant: gleiche 404.
    final String tokenC = buildJwt(TENANT_B_UUID, USER_C_REF, USER_C_LOGIN);
    final String foreign =
        mvc.perform(
                put("/api/v1/tasks/" + taskUuid + "/seen").header("Authorization", bearer(tokenC)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertMatchesExample(soft, foreign, ParticipantApiExamples.NOT_FOUND_SEEN, taskUuid);
    soft.assertThat(userStateRowCount(taskUuid, USER_C_REF)).isZero();

    // Ohne JWT: 401 problem+json wie das Beispiel.
    final String anonymous =
        mvc.perform(put("/api/v1/tasks/" + taskUuid + "/seen"))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    soft.assertThat(om.readTree(anonymous))
        .isEqualTo(om.readTree(ParticipantApiExamples.UNAUTHORIZED));

    // Keine UUID im Pfad: 400 wie das Beispiel.
    final String badRequest =
        mvc.perform(put("/api/v1/tasks/keine-uuid/seen").header("Authorization", bearer(tokenA)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertMatchesExample(soft, badRequest, ParticipantApiExamples.BAD_REQUEST_SEEN, taskUuid);
    soft.assertAll();
  }

  // ---- Austrittszeitpunkt und Lesen nach Austritt --------------------------------------------

  @Test
  @DisplayName(
      "ausgetretener_liest_nachher_entstandene_kommentare_und_historie: exit unsubscribed mit"
          + " exitedAt, Kommentar und Historie nach dem Austritt sichtbar, seen erlaubt")
  void ausgetretener_liest_nachher_entstandene_kommentare_und_historie() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = tokenA();
    final String tokenB = tokenB();
    final UUID taskUuid = createTask(mvc, tokenA, "Seen-IT-Austritt");
    putAssignees(mvc, tokenA, taskUuid, USER_B_REF);

    exit(mvc, tokenB, taskUuid, Map.of("exitKind", "UNSUBSCRIBED"));
    final LocalDateTime exitAt = exitAt(taskUuid, USER_B_REF);
    assertThat(exitAt).isNotNull();
    final int historyAtExit = getDetail(mvc, tokenB, taskUuid).get("history").size();

    // Nach dem Austritt: Kommentar und Historieneintrag (Titel-Patch) durch A.
    final UUID afterExit = postComment(mvc, tokenA, taskUuid, "Kommentar nach dem Austritt");
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Seen-IT-Austritt umbenannt\"}"))
        .andExpect(status().isNoContent());

    final JsonNode detail = getDetail(mvc, tokenB, taskUuid);
    assertThat(detail.get("exit").get("kind").asText()).isEqualTo("unsubscribed");
    final Instant exitedAt = Instant.parse(detail.get("exit").get("exitedAt").asText());
    assertThat(exitedAt).isEqualTo(auditTimeConverter.toInstant(exitAt));
    assertThat(detail.get("title").asText()).isEqualTo("Seen-IT-Austritt umbenannt");
    assertThat(detail.get("history").size()).isGreaterThan(historyAtExit);
    final List<Instant> historyTimes = new ArrayList<>();
    for (final JsonNode entry : detail.get("history")) {
      historyTimes.add(Instant.parse(entry.get("createdAt").asText()));
    }
    assertThat(historyTimes).anySatisfy(t -> assertThat(t).isAfter(exitedAt));

    // Kommentare nach dem Austritt werden geliefert und sind fuer B (ohne Marker) neu.
    assertThat(isNewById(getComments(mvc, tokenB, taskUuid))).containsEntry(afterExit, true);

    // Ausgetretener darf als gesehen markieren (READ-Guard), bleibt dabei ausgetreten.
    mvc.perform(put("/api/v1/tasks/" + taskUuid + "/seen").header("Authorization", bearer(tokenB)))
        .andExpect(status().isNoContent());
    assertThat(isNewById(getComments(mvc, tokenB, taskUuid))).containsEntry(afterExit, false);
    assertThat(getDetail(mvc, tokenB, taskUuid).get("exit").get("kind").asText())
        .isEqualTo("unsubscribed");
    assertThat(unsubscribedAt(taskUuid, USER_B_REF)).isNotNull();
  }

  @Test
  @DisplayName(
      "exited_at_bei_handover_im_detail_und_archiv_null_nach_wiedereintritt: Historie bleibt")
  void exited_at_bei_handover_im_detail_und_archiv_null_nach_wiedereintritt() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = tokenA();
    final String tokenB = tokenB();
    final UUID taskUuid = createTask(mvc, tokenA, "Seen-IT-Handover");
    putAssignees(mvc, tokenA, taskUuid, USER_B_REF);

    final Map<String, Object> handover = new HashMap<>();
    handover.put("exitKind", "HANDOVER");
    handover.put("handedOverToIds", List.of(USER_A_REF));
    exit(mvc, tokenB, taskUuid, handover);
    final Instant expected = auditTimeConverter.toInstant(exitAt(taskUuid, USER_B_REF));

    final JsonNode detail = getDetail(mvc, tokenB, taskUuid);
    assertThat(detail.get("exit").get("kind").asText()).isEqualTo("handover");
    assertThat(Instant.parse(detail.get("exit").get("exitedAt").asText())).isEqualTo(expected);
    final int historyAfterExit = detail.get("history").size();

    // Archiv "verlassen": gleiche Austritts-Info inkl. exitedAt in der Listen-Zeile.
    final JsonNode archive =
        getJson(mvc, get("/api/v1/tasks/archive").queryParam("filter", "left"), tokenB);
    final JsonNode item = findItem(archive, taskUuid);
    assertThat(item).as("Aufgabe im Archiv 'verlassen': %s", archive).isNotNull();
    assertThat(item.get("exit").get("kind").asText()).isEqualTo("handover");
    assertThat(Instant.parse(item.get("exit").get("exitedAt").asText())).isEqualTo(expected);

    // Wiedereintritt per Zuweisung: exit (samt exitedAt) ist null, Historie bleibt erhalten.
    putAssignees(mvc, tokenA, taskUuid, USER_A_REF, USER_B_REF);
    final JsonNode reentered = getDetail(mvc, tokenB, taskUuid);
    assertThat(reentered.get("exit").isNull())
        .as("exit nach Wiedereintritt: %s", reentered)
        .isTrue();
    assertThat(reentered.get("history").size()).isGreaterThanOrEqualTo(historyAfterExit);
    assertThat(exitAt(taskUuid, USER_B_REF)).isNull();
  }

  // ---- Hilfsmethoden ------------------------------------------------------------------------

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private static String bearer(final String token) {
    return "Bearer " + token;
  }

  private static String tokenA() {
    return buildJwt(
        TENANT_A_UUID,
        USER_A_REF,
        USER_A_LOGIN,
        "task-management:TASK_WRITE",
        "task-management:TASK_READ");
  }

  /** B ist ein Mitglied ohne task-management-Recht (Selbstbedienung). */
  private static String tokenB() {
    return buildJwt(TENANT_A_UUID, USER_B_REF, USER_B_LOGIN);
  }

  private UUID createTask(final MockMvc mvc, final String token, final String title)
      throws Exception {
    final String response =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", title))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(om.readTree(response).get("id").asText());
  }

  private void putAssignees(
      final MockMvc mvc, final String token, final UUID taskUuid, final UUID... assignees)
      throws Exception {
    mvc.perform(
            put("/api/v1/tasks/" + taskUuid + "/assignees")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(assignees)))))
        .andExpect(status().isNoContent());
  }

  private void exit(
      final MockMvc mvc, final String token, final UUID taskUuid, final Map<String, Object> body)
      throws Exception {
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/exit")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
        .andExpect(status().isNoContent());
  }

  private UUID postComment(
      final MockMvc mvc, final String token, final UUID taskUuid, final String text)
      throws Exception {
    final String response =
        mvc.perform(
                post("/api/v1/tasks/" + taskUuid + "/comments")
                    .header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("text", text))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode created = om.readTree(response);
    // Die Antwort auf das Anlegen ist fuer den Autor nie neu.
    assertThat(created.get("isNew").asBoolean(true)).isFalse();
    return UUID.fromString(created.get("id").asText());
  }

  private JsonNode getComments(final MockMvc mvc, final String token, final UUID taskUuid)
      throws Exception {
    return getJson(mvc, get("/api/v1/tasks/" + taskUuid + "/comments"), token);
  }

  private JsonNode getDetail(final MockMvc mvc, final String token, final UUID taskUuid)
      throws Exception {
    return getJson(mvc, get("/api/v1/tasks/" + taskUuid), token);
  }

  private JsonNode getJson(
      final MockMvc mvc, final MockHttpServletRequestBuilder request, final String token)
      throws Exception {
    return om.readTree(
        mvc.perform(request.header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  /** isNew je Kommentar-Id; jedes Element muss das boolesche Feld tragen. */
  private static Map<UUID, Boolean> isNewById(final JsonNode comments) {
    final Map<UUID, Boolean> result = new HashMap<>();
    for (final JsonNode comment : comments) {
      assertThat(comment.get("isNew")).as("isNew in %s", comment).isNotNull();
      assertThat(comment.get("isNew").isBoolean()).isTrue();
      result.put(UUID.fromString(comment.get("id").asText()), comment.get("isNew").asBoolean());
    }
    return result;
  }

  /** Listen-Zeile einer Aufgabe; toleriert alle Collection-Wire-Formen (HAL, content, items). */
  private static JsonNode findItem(final JsonNode page, final UUID taskUuid) {
    JsonNode items = null;
    final JsonNode embedded = page.get("_embedded");
    if (embedded != null && embedded.get("tasks") != null) {
      items = embedded.get("tasks");
    } else if (page.get("content") != null) {
      items = page.get("content");
    } else if (page.get("items") != null) {
      items = page.get("items");
    }
    if (items == null) {
      return null;
    }
    for (final JsonNode item : items) {
      if (taskUuid.toString().equals(item.get("id").asText())) {
        return item;
      }
    }
    return null;
  }

  /**
   * Vergleicht eine Fehlerantwort mit einem OpenAPI-Beispiel: gleiche Feldmenge und gleiche stabile
   * Werte (ausser {@code timestamp} und {@code traceId}); die Beispiel-UUID im Pfad wird ersetzt.
   */
  private void assertMatchesExample(
      final SoftAssertions soft, final String liveJson, final String exampleJson, final UUID taskId)
      throws Exception {
    final JsonNode live = om.readTree(liveJson);
    final JsonNode example = om.readTree(exampleJson.replace(EXAMPLE_TASK_ID, taskId.toString()));
    final Set<String> exampleFields = new TreeSet<>();
    example.fieldNames().forEachRemaining(exampleFields::add);
    final Set<String> liveFields = new TreeSet<>();
    live.fieldNames().forEachRemaining(liveFields::add);
    soft.assertThat(liveFields).as("Felder der Antwort: %s", live).isEqualTo(exampleFields);
    for (final String stable :
        List.of("status", "error", "message", "path", "errorKey", "details")) {
      soft.assertThat(live.get(stable))
          .as("Feld %s der Antwort: %s", stable, live)
          .isEqualTo(example.get(stable));
    }
  }

  private LocalDateTime seenAt(final UUID taskUuid, final UUID userRef) {
    return stateTime("SEEN_AT", taskUuid, userRef);
  }

  private LocalDateTime readAt(final UUID taskUuid, final UUID userRef) {
    return stateTime("READ_AT", taskUuid, userRef);
  }

  private LocalDateTime unsubscribedAt(final UUID taskUuid, final UUID userRef) {
    return stateTime("UNSUBSCRIBED_AT", taskUuid, userRef);
  }

  /** Liest eine Zeitspalte des State des Benutzers roh als {@link LocalDateTime}. */
  private LocalDateTime stateTime(final String column, final UUID taskUuid, final UUID userRef) {
    final List<LocalDateTime> values =
        jdbcTemplate.queryForList(
            "SELECT s."
                + column
                + " FROM [dbo].[TASK_USER_STATE] s JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ?",
            LocalDateTime.class,
            taskUuid,
            userRef);
    assertThat(values).as("genau eine State-Zeile").hasSize(1);
    return values.get(0);
  }

  private LocalDateTime exitAt(final UUID taskUuid, final UUID userRef) {
    return jdbcTemplate.queryForObject(
        "SELECT p.EXIT_AT FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t"
            + " ON p.TASK_ID = t.ID WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ?",
        LocalDateTime.class,
        taskUuid,
        userRef);
  }

  private int userStateRowCount(final UUID taskUuid, final UUID userRef) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND s.USER_REF = ?",
        Integer.class,
        taskUuid,
        userRef);
  }

  private static String buildJwt(
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
        id,
        id,
        externalId,
        id);
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
        id,
        id,
        externalId,
        tenantId,
        loginName);
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  // ---- IT-Konfigurations-Kontext ------------------------------------------------------------

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
    de.brasi.nova.taskmanagement.security.TaskMembershipGuard.class,
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
    // Intake #190: CommentService haengt am DivisionMentionResolver (Paket division).
    de.brasi.nova.taskmanagement.division.DivisionMentionItWiring.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "seen-marker-it";
    }
  }
}
