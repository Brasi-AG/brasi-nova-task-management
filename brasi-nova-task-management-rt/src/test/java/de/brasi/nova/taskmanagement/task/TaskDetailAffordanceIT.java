package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.jsonwebtoken.Jwts;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
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
 * Affordance-Integration-Test (PLAN #141 Slice 1): {@code GET /api/v1/tasks/{id}} ist
 * pipeline-angereichert und traegt {@code _links.self} - OHNE die bestehende objekt-level
 * Enforcement ({@code TaskMembershipGuard}) oder den Wire-Vertrag des {@link TaskDetailModel} zu
 * veraendern. Die Links sind rein additiv (Anzeige).
 *
 * <p>Im Unterschied zu {@link TaskFacadeControllerIT} (handverlesene Autoconfig-Liste, OHNE den
 * HATEOAS-Enrichment-Graph) bootet dieser IT den VOLLEN Spring-Boot-Autoconfig-Graph (wie die reale
 * {@code BrasiNovaApplication} und der Slice-0-{@code ProbeWebContext}): der globale {@code
 * NovaFindResponseAdvice}, der {@code @EnableNovaHateoas}-Scan und der {@code SelfLinkEnricher} sind
 * damit genauso live wie in Produktion. Zusaetzlich laeuft die reale KSF-Web-Security-Filterchain
 * (JWT, Tenant-Binding) scharf, sodass Links nur im gebundenen Tenant-/Request-Kontext entstehen.
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskDetailAffordanceIT.ItConfig.class,
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
      // Reale KSF-Web-Security-Filterchain (FILTER-Modus, JWT) - identisch zu TaskFacadeControllerIT.
      "ksf.web-security.enabled=true",
      "ksf.web-security.filter-chain.enabled=true",
      "ksf.web-security.principal.profile-claim.enabled=true",
      "ksf.web-security.tenant.context-binding=FILTER",
      "ksf.web-security.tenant.binding.source=JWT",
      "ksf.web-security.tenant.binding.required=false",
      "ksf.security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.security.jwt.enabled=true",
      "ksf.module-registration.enabled=false",
      // Dev-Modus: HATEOAS-Self-Check meldet Regelprobleme laut statt still.
      "ksf.web.dev.enabled=true",
      // Enrichment-/Response-Diagnose sichtbar (NOVA-RESP/NOVA-LINK).
      "logging.level.io.keel.framework.web.mvc=DEBUG",
      "logging.level.io.keel.framework.web.hateoas=DEBUG",
      "logging.level.io.keel.framework.web.enrichment=DEBUG",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class TaskDetailAffordanceIT {

  private static final String TEST_KID = "task-detail-affordance-it-key";

  private static final long TENANT_A_ID = 510L;
  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "aff-a@brasi.de";
  private static final long USER_A_DB_ID = 511L;

  /** Zweiter Tenant-A-Nutzer: wird als (Ex-)Teilnehmer der Aufgabe gesetzt. */
  private static final UUID USER_A2_REF = UUID.randomUUID();

  private static final String USER_A2_LOGIN = "aff-a2@brasi.de";
  private static final long USER_A2_DB_ID = 512L;

  /** Tenant-A-Nutzer OHNE Teilnehmer-Rolle (Non-Member -> 404). */
  private static final UUID USER_NON_MEMBER_REF = UUID.randomUUID();

  private static final String USER_NON_MEMBER_LOGIN = "aff-non@brasi.de";
  private static final long USER_NON_MEMBER_DB_ID = 513L;

  /** Fremder Tenant B (-> 404). */
  private static final long TENANT_B_ID = 514L;

  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "aff-b@brasi.de";
  private static final long USER_B_DB_ID = 515L;

  private static final KeyPair RSA_KEY_PAIR;

  static {
    try {
      final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      RSA_KEY_PAIR = gen.generateKeyPair();
    } catch (final NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static HikariDataSource master;
  private static String dbName;
  private static Path pemTempFile;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * Spy auf den echten TaskMembershipGuard (1:1 {@code existsWriteMember}/{@code existsReadMember}
   * hinter {@code matchesContextTenant}): zaehlt DB-wirksame Membership-Pruefungen robust, ohne die
   * KSF-Repository-Proxies anzufassen.
   */
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private de.brasi.nova.taskmanagement.security.TaskMembershipGuard membershipGuardSpy;

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
      pemTempFile = Files.createTempFile("task-detail-affordance-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new RuntimeException("Konnte temporaere PEM-Datei nicht erstellen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem", () -> "file:" + pemTempFile.toAbsolutePath());
  }

  @BeforeAll
  static void seedPrerequisites(@Autowired final JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_A2_DB_ID, USER_A2_REF, USER_A2_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(
        jt, USER_NON_MEMBER_DB_ID, USER_NON_MEMBER_REF, USER_NON_MEMBER_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_B_ID);
    // FINDING 2 (Codex-IMPL-Gate): Der KSF-Laufzeit-Regelresolver liest beim Link-Aufbau
    // system_controller_regel (DefaultLinkRuntimeResolver.addTopLevelLinks ->
    // HateoasRuleService.getActiveRules); fehlt die Tabelle, wirft der JDBC-Read und die GESAMTE
    // addLinks-Anreicherung wird im fail-safe-catch von DefaultNovaResponseEnrichmentPipeline
    // verschluckt - der self-Link verschwindet still (GET bleibt 200). Diese System-Tabelle ist
    // KEINE Consumer-Flyway-Tabelle: im realen Deploy liegt sie als leere Struktur-Kopie der
    // diamant-DB vor (Startkarte: "SYS_SEQUENCE und SYSTEM*-Tabellen als leere Struktur-Kopie"),
    // genau wie PROD_FIRMA/PROD_BENUTZER. Der IT taeuscht sie daher NICHT mehr ad-hoc vor, sondern
    // RefSchemaItDb legt die leere Struktur-Kopie zentral an (= reales Deploy-Provisioning). Hier
    // nur noch beweisen, dass dieser Mechanismus wirklich gegriffen hat: die Tabelle MUSS existieren
    // (sonst traegt der GET den self-Link nur wegen einer Test-Vortaeuschung - genau der Befund).
    final Integer tableExists =
        jt.queryForObject(
            "SELECT COUNT(*) FROM sys.objects"
                + " WHERE object_id = OBJECT_ID('dbo.system_controller_regel') AND type = 'U'",
            Integer.class);
    org.assertj.core.api.Assertions.assertThat(tableExists)
        .as(
            "FINDING 2: system_controller_regel muss als leere Struktur-Kopie existieren"
                + " (RefSchemaItDb spiegelt das Deploy-Provisioning); nur dann beweist der self-Link"
                + " den realen Deploy-Pfad statt einer IT-Vortaeuschung.")
        .isEqualTo(1);
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
        } catch (final IOException ignored) {
          // best-effort cleanup
        }
      }
    }
  }

  /** Die pre-existenten Wire-Felder des TaskDetailModel (Vertrag VOR Slice 1). */
  private static final List<String> PRE_EXISTING_FIELDS =
      List.of(
          "id",
          "title",
          "description",
          "statusCode",
          "priorityCode",
          "directionCode",
          "startDate",
          "dueDate",
          "lastActivityAt",
          "commentCount",
          "history",
          "assignees",
          "tagIds",
          "tags",
          "createdById",
          "participantIds",
          "isPinned",
          "isFlagged",
          "isMuted",
          "exit",
          "source");

  // ---- AC: Write-Member GET -> 200 + _links.self + volle Wire-Regression --------------------

  @Test
  @DisplayName(
      "write_member_detail_traegt_self_link_und_vollen_wire_vertrag:"
          + " GET /tasks/{id} -> 200, _links.self vorhanden (href auf Detail), alle Alt-Felder"
          + " unveraendert, modelState additiv")
  void write_member_detail_traegt_self_link_und_vollen_wire_vertrag() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String writeMember =
        buildJwt(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, writeMember, "Affordance-Self-Link");

    final String body =
        mvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + writeMember))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[AFF-wire] GET /tasks/" + taskId + " -> " + body);

    // 1) self-Link vorhanden und zeigt auf das Detail dieser Aufgabe.
    final JsonNode selfHref = root.path("_links").path("self").path("href");
    assertThat(selfHref.isTextual())
        .as("BLOCKER: _links.self.href fehlt -> Enrichment-Pipeline nicht aktiv. Body=" + body)
        .isTrue();
    assertThat(selfHref.asText()).contains("/api/v1/tasks/" + taskId);

    // 2) Volle Wire-Regression: jedes Alt-Feld weiterhin unter identischem Namen vorhanden.
    for (final String field : PRE_EXISTING_FIELDS) {
      assertThat(root.has(field))
          .as("Alt-Feld '" + field + "' muss im Detail-Wire erhalten bleiben")
          .isTrue();
    }
    assertThat(root.path("id").asText()).isEqualTo(taskId.toString());
    assertThat(root.path("title").asText()).isEqualTo("Affordance-Self-Link");
    assertThat(root.path("statusCode").asText()).isEqualTo("OPEN");
    assertThat(root.path("directionCode").asText()).isEqualTo("CUSTOMER_INTERNAL");
    assertThat(root.path("history").isArray()).isTrue();
    assertThat(root.path("assignees").isArray()).isTrue();
    assertThat(root.path("tagIds").isArray()).isTrue();
    assertThat(root.path("tags").isArray()).isTrue();
    assertThat(root.path("participantIds").isArray()).isTrue();
    assertThat(root.path("commentCount").isInt()).isTrue();

    // 3) Boolesche Marker behalten die is-Praefix-Wirenamen (KEIN bean-gestripptes pinned/flagged).
    assertThat(root.has("isPinned")).as("JSON-Key muss 'isPinned' heissen").isTrue();
    assertThat(root.has("isFlagged")).as("JSON-Key muss 'isFlagged' heissen").isTrue();
    assertThat(root.has("isMuted")).as("JSON-Key muss 'isMuted' heissen").isTrue();
    assertThat(root.has("pinned")).as("kein bean-gestripptes 'pinned'").isFalse();
    assertThat(root.has("flagged")).as("kein bean-gestripptes 'flagged'").isFalse();
    assertThat(root.has("muted")).as("kein bean-gestripptes 'muted'").isFalse();

    // 4) modelState ist ADDITIV (BaseModel) - Alt-Felder werden dadurch NICHT verdraengt.
    assertThat(root.has("modelState")).as("modelState (BaseModel) additiv vorhanden").isTrue();
    assertThat(root.path("modelState").path("key").asText()).isEqualTo("ACTIVE");

    // 4b) FINDING 3: EXAKTES Top-Level-Feldset gepinnt. Erlaubt sind GENAU die 21 Alt-Felder plus
    // die beiden additiven Keys modelState (BaseModel-Lebenszyklus) und _links (HATEOAS). Jedes
    // andere Top-Level-Feld (insb. das geerbte version:null, aber auch ein kuenftig versehentlich
    // zusaetzlich serialisiertes BaseModel-Feld) laesst den Test fallen. version ist bewusst via
    // @JsonIgnoreProperties("version") aus dem Wire genommen -> hier explizit als abwesend gepinnt.
    final java.util.Set<String> allowedTopLevel = new java.util.LinkedHashSet<>(PRE_EXISTING_FIELDS);
    allowedTopLevel.add("modelState");
    allowedTopLevel.add("_links");
    final java.util.List<String> actualTopLevel = new java.util.ArrayList<>();
    root.fieldNames().forEachRemaining(actualTopLevel::add);
    assertThat(actualTopLevel)
        .as(
            "Top-Level-Wire muss GENAU die 21 Alt-Felder + modelState + _links tragen"
                + " (kein version:null, kein sonstiges unerwartetes BaseModel-Feld). Ist=%s",
            actualTopLevel)
        .containsExactlyInAnyOrderElementsOf(allowedTopLevel);
    assertThat(root.has("version"))
        .as("FINDING 3: geerbtes version darf NICHT im Detail-Wire erscheinen (JsonIgnore)")
        .isFalse();

    // 5) Slice 3: Write-Member sieht ALLE permission-/membership-gegateten Schreib-Rels.
    final JsonNode links = root.path("_links");
    // Unconditional rels (immer fuer Read-Member sichtbar):
    assertThat(links.has("comments")).as("comments-Rel muss vorhanden sein").isTrue();
    assertThat(links.path("comments").path("href").asText())
        .as("comments href muss die Task-id enthalten")
        .contains("/api/v1/tasks/" + taskId);
    // Write-Member-Rels (IS_WRITE_MEMBER-Bedingung):
    assertThat(links.has("update")).as("update-Rel muss fuer Write-Member vorhanden sein").isTrue();
    assertThat(links.path("update").path("href").asText())
        .as("update href muss die Task-id enthalten")
        .contains("/api/v1/tasks/" + taskId);
    assertThat(links.has("add-comment"))
        .as("add-comment-Rel muss fuer Write-Member vorhanden sein")
        .isTrue();
    assertThat(links.has("exit"))
        .as("exit-Rel muss fuer Write-Member vorhanden sein")
        .isTrue();
    assertThat(links.has("pin"))
        .as("pin-Rel muss fuer Write-Member vorhanden sein")
        .isTrue();
    assertThat(links.has("flag"))
        .as("flag-Rel muss fuer Write-Member vorhanden sein")
        .isTrue();
    assertThat(links.has("mute"))
        .as("mute-Rel muss fuer Write-Member vorhanden sein")
        .isTrue();
    // TASK_WRITE+Member-Rels (HAS_WRITE_AUTHORITY_AND_IS_MEMBER): der JWT traegt TASK_WRITE.
    assertThat(links.has("assignees"))
        .as("assignees-Rel muss fuer TASK_WRITE+Member vorhanden sein")
        .isTrue();
    assertThat(links.has("participants"))
        .as("participants-Rel muss fuer TASK_WRITE+Member vorhanden sein")
        .isTrue();
    // delete-Rel existiert nicht (kein soft-delete-Endpunkt fuer Tasks).
    assertThat(links.has("delete")).as("delete-Rel darf nicht erscheinen (kein Task-Delete)").isFalse();
  }

  @Test
  @DisplayName(
      "detail_membership_pruefung_aus_specs_genau_einmal_enforcement_ungecacht:"
          + " GET /tasks/{id} (Write-Member, TASK_WRITE) -> isWriteMember aus den Specs genau 1x;"
          + " requireReadMember (Enforcement) weiterhin ungecacht")
  void detail_membership_pruefung_aus_specs_genau_einmal_enforcement_ungecacht() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String writeMember =
        buildJwt(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskId = createTask(mvc, om, writeMember, "Affordance-N1-Detail");

    org.mockito.Mockito.clearInvocations(membershipGuardSpy);
    final String body =
        mvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + writeMember))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode links = om.readTree(body).path("_links");
    assertThat(links.has("update")).as("Write-Rel update weiterhin vorhanden").isTrue();
    assertThat(links.has("assignees")).as("AND-Rel assignees weiterhin vorhanden").isTrue();

    final java.util.Map<String, Long> counts = new java.util.TreeMap<>();
    org.mockito.Mockito.mockingDetails(membershipGuardSpy)
        .getInvocations()
        .forEach(i -> counts.merge(i.getMethod().getName(), 1L, Long::sum));
    System.out.println("[AFF-N1-detail] Guard-Aufrufe=" + counts);
    assertThat(counts.get("isWriteMember"))
        .as("Specs: genau eine Write-Membership-Pruefung je Task und Request")
        .isEqualTo(1L);
    assertThat(counts.get("requireReadMember"))
        .as("Enforcement requireReadMember bleibt ungecacht (Anzahl wie bisher)")
        .isEqualTo(EXPECTED_REQUIRE_READ_MEMBER_CALLS);
  }

  private static final long EXPECTED_REQUIRE_READ_MEMBER_CALLS = 2L;

  // ---- AC: Ex-Member GET -> 200 + self, keine Schreib-Rels (Enrichment bricht Ex-Member nicht) --

  @Test
  @DisplayName(
      "ex_member_detail_200_mit_self_ohne_write_rels:"
          + " ausgetretener Teilnehmer darf das Detail lesen (200), self vorhanden, keine Write-Rels")
  void ex_member_detail_200_mit_self_ohne_write_rels() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String writeMember =
        buildJwt(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, writeMember, "Affordance-ExMember");
    final Long internalId =
        jdbcTemplate.queryForObject(
            "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskId);

    // USER_A2 als PERSON-Teilnehmer der Aufgabe setzen und als ausgetreten markieren (HANDOVER).
    // Ex-Member bleibt READ-Member (Archiv-Lesung erlaubt) -> 200 (Policy unveraendert). Spalten
    // exakt nach V1-DDL (ID ist nicht-IDENTITY -> Wert mitgeben; PARTICIPANT_KIND/
    // PARTICIPANT_TENANT_ID sind READ-Member-Pflichtkonjunkte; ANGELEGT_* via DB-Default).
    final long participantId = 900000L + USER_A2_DB_ID;
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT)"
            + " VALUES (?, 'ACTIVE', 'affordance-it', SYSDATETIME(), NEWID(), 1, ?,"
            + " ?, ?, 'PERSON', ?, 0, 0, 1, 'HANDOVER', '2020-01-01T00:00:00')",
        participantId,
        TENANT_A_ID,
        internalId,
        USER_A2_REF,
        TENANT_A_ID);

    final String exMember =
        buildJwt(TENANT_A_UUID, USER_A2_REF, USER_A2_LOGIN, "task-management:TASK_READ");
    final String body =
        mvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + exMember))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[AFF-exmember] GET /tasks/" + taskId + " -> " + body);

    assertThat(root.path("_links").path("self").path("href").isTextual())
        .as("Ex-Member-Read muss weiterhin self tragen; Enrichment bricht die Lesung nicht")
        .isTrue();
    assertThat(root.path("exit").path("kind").asText())
        .as("Ex-Member sieht seinen Austritt (Live-Read)")
        .isEqualTo("handover");
    // Slice 3: Ex-Member ist kein WRITE-Member mehr -> kein IS_WRITE_MEMBER -> keine Schreib-Rels.
    final JsonNode links = root.path("_links");
    // comments ist UNCONDITIONAL -> auch fuer Ex-Member (Read-Member) sichtbar.
    assertThat(links.has("comments"))
        .as("Ex-Member sieht weiterhin comments-Rel (UNCONDITIONAL)")
        .isTrue();
    // Write-Member-Rels duerfen NICHT erscheinen (IS_WRITE_MEMBER=false fuer Ex-Member).
    assertThat(links.has("update")).as("Ex-Member sieht keine update-Rel").isFalse();
    assertThat(links.has("add-comment")).as("Ex-Member sieht keine add-comment-Rel").isFalse();
    assertThat(links.has("exit")).as("Ex-Member sieht keine exit-Rel").isFalse();
    assertThat(links.has("pin")).as("Ex-Member sieht keine pin-Rel").isFalse();
    assertThat(links.has("flag")).as("Ex-Member sieht keine flag-Rel").isFalse();
    assertThat(links.has("mute")).as("Ex-Member sieht keine mute-Rel").isFalse();
    assertThat(links.has("assignees")).as("Ex-Member sieht keine assignees-Rel").isFalse();
    assertThat(links.has("participants")).as("Ex-Member sieht keine participants-Rel").isFalse();
    // delete-Rel darf nie erscheinen.
    assertThat(links.has("delete")).isFalse();
    assertThat(links.has("edit")).isFalse();
  }

  // ---- AC: Ex-Member MIT TASK_WRITE - Membership-Haelfte der UND-Bedingung ---------------------

  @Test
  @DisplayName(
      "ex_member_mit_task_write_sieht_keine_write_rels:"
          + " ausgetretener Teilnehmer MIT TASK_WRITE-Grant -> 200, self/comments ja, aber KEIN"
          + " assignees/participants/update/pin/flag/mute/exit/add-comment (Membership-Haelfte der"
          + " UND-Bedingung an der echten Rule-Verdrahtung)")
  void ex_member_mit_task_write_sieht_keine_write_rels() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String creator =
        buildJwt(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskId = createTask(mvc, om, creator, "Affordance-ExMember-MitWriteAuthority");
    final Long internalId =
        jdbcTemplate.queryForObject(
            "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskId);

    final long participantId = 900200L + USER_A2_DB_ID;
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT)"
            + " VALUES (?, 'ACTIVE', 'affordance-it', SYSDATETIME(), NEWID(), 1, ?,"
            + " ?, ?, 'PERSON', ?, 0, 0, 1, 'HANDOVER', '2020-01-01T00:00:00')",
        participantId,
        TENANT_A_ID,
        internalId,
        USER_A2_REF,
        TENANT_A_ID);

    // Ex-Member traegt die Authority TASK_WRITE -> nur die Membership-Haelfte kann Rels verhindern.
    final String exMemberWithWrite =
        buildJwt(
            TENANT_A_UUID,
            USER_A2_REF,
            USER_A2_LOGIN,
            "task-management:TASK_READ",
            "task-management:TASK_WRITE");
    final String body =
        mvc.perform(
                get("/api/v1/tasks/" + taskId)
                    .header("Authorization", "Bearer " + exMemberWithWrite))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode links = om.readTree(body).path("_links");
    System.out.println("[AFF-exmember-write] GET /tasks/" + taskId + " -> " + body);

    assertThat(links.path("self").path("href").isTextual()).as("self vorhanden").isTrue();
    assertThat(links.has("comments")).as("comments (UNCONDITIONAL) vorhanden").isTrue();
    for (final String rel :
        new String[] {
          "assignees", "participants", "update", "pin", "flag", "mute", "exit", "add-comment"
        }) {
      assertThat(links.has(rel))
          .as(
              "Ex-Member MIT TASK_WRITE darf '" + rel + "' nicht sehen (Membership-Haelfte). Links="
                  + links)
          .isFalse();
    }
  }

  // ---- AC: Write-Member OHNE TASK_WRITE - Slice-3-NIT (Alt-Auflage Slice 6) ------------------
  //
  // Beweist die AUTHORITY-Haelfte der UND-Verknuepfung in TaskWriteAuthorityAndMembershipSpec:
  // ein aktiver Write-Member (EXIT_KIND IS NULL) OHNE die Authority TASK_WRITE sieht die
  // IS_WRITE_MEMBER-Rels (update/pin/flag/mute/exit/add-comment), aber NICHT die
  // HAS_WRITE_AUTHORITY_AND_IS_MEMBER-Rels (assignees/participants). Die MEMBERSHIP-Haelfte beweist
  // ex_member_mit_task_write_sieht_keine_write_rels (Ex-Member MIT Authority).

  @Test
  @DisplayName(
      "write_member_ohne_task_write_sieht_membership_rels_aber_nicht_authority_rels:"
          + " aktiver Write-Member ohne TASK_WRITE-Grant sieht update/pin/flag/mute/exit/"
          + "add-comment (IS_WRITE_MEMBER), NICHT assignees/participants (Authority-Haelfte der"
          + " UND-Bedingung)")
  void write_member_ohne_task_write_sieht_membership_rels_aber_nicht_authority_rels()
      throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    // Task anlegen mit TASK_WRITE-Grant (USER_A ist Creator -> Write-Member).
    final String writeMember =
        buildJwt(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, writeMember, "Affordance-WriteMember-NoAuthority");
    final Long internalId =
        jdbcTemplate.queryForObject(
            "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskId);

    // USER_A2 als aktiven Teilnehmer einfuegen (EXIT_KIND IS NULL = aktiver Write-Member nach
    // Guard-Definition). Kein TASK_WRITE in seinem JWT -> IS_WRITE_MEMBER=true, HAS_WRITE_AUTHORITY=false.
    final long participantId = 900100L + USER_A2_DB_ID;
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT)"
            + " VALUES (?, 'ACTIVE', 'affordance-it', SYSDATETIME(), NEWID(), 1, ?,"
            + " ?, ?, 'PERSON', ?, 0, 0, 1, NULL, NULL)",
        participantId,
        participantId,
        TENANT_A_ID,
        internalId,
        USER_A2_REF,
        TENANT_A_ID);

    // JWT fuer USER_A2: TASK_READ aber KEIN TASK_WRITE (Slice-3-NIT-Konstellation).
    final String memberNoWrite =
        buildJwt(TENANT_A_UUID, USER_A2_REF, USER_A2_LOGIN, "task-management:TASK_READ");

    final String body =
        mvc.perform(
                get("/api/v1/tasks/" + taskId)
                    .header("Authorization", "Bearer " + memberNoWrite))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println(
        "[AFF-member-no-write] GET /tasks/" + taskId + " -> " + body);

    final JsonNode links = root.path("_links");

    // IS_WRITE_MEMBER-Rels sichtbar (aktiver Write-Member; keine TASK_WRITE-Autoritaet noetig).
    assertThat(links.has("update"))
        .as("Slice-3-NIT: Write-Member OHNE TASK_WRITE sieht IS_WRITE_MEMBER-Rel 'update'")
        .isTrue();
    assertThat(links.has("pin"))
        .as("Slice-3-NIT: Write-Member OHNE TASK_WRITE sieht IS_WRITE_MEMBER-Rel 'pin'")
        .isTrue();
    assertThat(links.has("flag"))
        .as("Slice-3-NIT: Write-Member OHNE TASK_WRITE sieht IS_WRITE_MEMBER-Rel 'flag'")
        .isTrue();
    assertThat(links.has("mute"))
        .as("Slice-3-NIT: Write-Member OHNE TASK_WRITE sieht IS_WRITE_MEMBER-Rel 'mute'")
        .isTrue();
    assertThat(links.has("exit"))
        .as("Slice-3-NIT: Write-Member OHNE TASK_WRITE sieht IS_WRITE_MEMBER-Rel 'exit'")
        .isTrue();
    assertThat(links.has("add-comment"))
        .as("Slice-3-NIT: Write-Member OHNE TASK_WRITE sieht IS_WRITE_MEMBER-Rel 'add-comment'")
        .isTrue();

    // HAS_WRITE_AUTHORITY_AND_IS_MEMBER-Rels NICHT sichtbar (TASK_WRITE fehlt -> UND false).
    assertThat(links.has("assignees"))
        .as(
            "Slice-3-NIT: Write-Member OHNE TASK_WRITE darf 'assignees' NICHT sehen"
                + " (AND-Bedingung: IS_MEMBER=true, HAS_WRITE_AUTHORITY=false -> false)")
        .isFalse();
    assertThat(links.has("participants"))
        .as(
            "Slice-3-NIT: Write-Member OHNE TASK_WRITE darf 'participants' NICHT sehen"
                + " (AND-Bedingung: IS_MEMBER=true, HAS_WRITE_AUTHORITY=false -> false)")
        .isFalse();
  }

  // ---- AC: Non-Member + Fremd-Tenant -> 404 (Enforcement unveraendert) -----------------------

  @Test
  @DisplayName(
      "non_member_und_fremder_tenant_detail_404:"
          + " Enforcement unveraendert - kein Member bzw. fremder Mandant -> neutrale 404")
  void non_member_und_fremder_tenant_detail_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String writeMember =
        buildJwt(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, writeMember, "Affordance-404");

    // Non-Member im selben Mandanten (reiner TASK_READ-Grant, keine Participant-Row) -> 404.
    final String nonMember =
        buildJwt(
            TENANT_A_UUID, USER_NON_MEMBER_REF, USER_NON_MEMBER_LOGIN, "task-management:TASK_READ");
    mvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + nonMember))
        .andExpect(status().isNotFound());

    // Fremder Mandant (Tenant B) -> 404.
    final String foreign =
        buildJwt(TENANT_B_UUID, USER_B_REF, USER_B_LOGIN, "task-management:TASK_READ");
    mvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + foreign))
        .andExpect(status().isNotFound());
  }

  // ---- Hilfen --------------------------------------------------------------------------------

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private UUID createTask(
      final MockMvc mvc, final ObjectMapper om, final String token, final String title)
      throws Exception {
    final String response =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", title))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(om.readTree(response).get("id").asText());
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

  // ---- IT-Context: wie TaskFacadeControllerIT.ItConfig, PLUS der HATEOAS-Enrichment-Graph -----
  //
  // Mirror der bewiesenen, repo-tragenden Autoconfig-Liste aus TaskFacadeControllerIT; zusaetzlich
  // explizit die Web-/HATEOAS-Enrichment-Autoconfigs (NovaFindResponseAdvice + @EnableNovaHateoas-
  // Scan + SelfLinkEnricher), die dort bewusst FEHLEN. Dadurch ist die Link-Pipeline genauso live
  // wie in Produktion, ohne den vollen @EnableAutoConfiguration-Graph zu ziehen.

  @SpringBootConfiguration
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
      exclude = {
        org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration.class
      })
  @org.springframework.boot.autoconfigure.AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @ComponentScan(
      // Produktions-Pakete (NICHT das Modul pauschal): sonst zieht der Scan die Test-only
      // security.it-Probes (WhoAmIProbeController) ein, die Test-Repos verlangen.
      basePackages = {
        "de.brasi.nova.taskmanagement.resolver",
        "de.brasi.nova.taskmanagement.task",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.comment",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.security",
        "de.brasi.nova.taskmanagement.time",
      },
      excludeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ANNOTATION,
            classes = SpringBootConfiguration.class),
        // Test-only Security-Probes (security.it) explizit ausschliessen.
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "de\\.brasi\\.nova\\.taskmanagement\\.security\\.it\\..*")
      })
  @EnableTransactionManagement
  @io.keel.framework.persistence.factory.EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @org.springframework.context.annotation.Import({
    de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig.class,
  })
  static class ItConfig {

    @Bean
    io.keel.framework.api.security.AuditorProvider affordanceAuditorProvider() {
      return () -> "task-detail-affordance-it";
    }
  }
}
