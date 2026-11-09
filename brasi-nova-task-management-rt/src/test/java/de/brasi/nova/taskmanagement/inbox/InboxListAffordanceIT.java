package de.brasi.nova.taskmanagement.inbox;

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
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
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
 * Affordance-Integration-Test fuer die TASK-LISTE (PLAN #141 Slice 4, P2-Empirie): {@code GET
 * /api/v1/tasks} ist eine enrichment-faehige HAL-Collection ({@code _embedded.tasks}) und traegt
 * (a) je Listenzeile die Task-Item-rels (Item-entityClass=Task, je Item membership-gegatet) und (b)
 * am Collection-Level die in Slice 3 deklarierten {@code create}/{@code create-from-source}-rels
 * (authority-gegatet). Die LEERE Liste emittiert {@code {}} (kein {@code _embedded}, KEINE
 * Collection-rels) - B1-Edge, da der {@code InboxSnapshotController} NICHT der Task-Rule-Owner ist
 * (entityClass nur aus Items ableitbar).
 *
 * <p>Bootet - wie {@link de.brasi.nova.taskmanagement.task.TaskDetailAffordanceIT} - den VOLLEN
 * Spring-Boot-Autoconfig-Graph (globaler {@code NovaFindResponseAdvice} + {@code @EnableNovaHateoas}-
 * Scan + {@code SelfLinkEnricher} + KSF-HAL-Converter), damit der Produktions-HAL-Wire real ist. Die
 * Behavior-ITs ({@code InboxSnapshotControllerIT}) booten bewusst OHNE HAL (dort serialisiert
 * {@code CollectionModel} als {@code content}); dieser IT beweist den Produktions-Wire scharf.
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = InboxListAffordanceIT.ItConfig.class,
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
      "ksf.security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.security.jwt.enabled=true",
      "ksf.module-registration.enabled=false",
      "ksf.web.dev.enabled=true",
      "logging.level.io.keel.framework.web.mvc=DEBUG",
      "logging.level.io.keel.framework.web.hateoas=DEBUG",
      "logging.level.io.keel.framework.web.enrichment=DEBUG",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InboxListAffordanceIT {

  private static final String TEST_KID = "inbox-list-affordance-it-key";

  private static final long TENANT_A_ID = 520L;
  private static final UUID TENANT_A_UUID = UUID.randomUUID();

  /** Write-Member mit voller Autoritaet (TASK_WRITE + TASK_CREATE). */
  private static final UUID USER_ADMIN_REF = UUID.randomUUID();

  private static final String USER_ADMIN_LOGIN = "inbox-aff-admin@brasi.de";
  private static final long USER_ADMIN_DB_ID = 521L;

  /** Aktiver Teilnehmer OHNE TASK_WRITE/TASK_CREATE (nur TASK_READ). */
  private static final UUID USER_READER_REF = UUID.randomUUID();

  private static final String USER_READER_LOGIN = "inbox-aff-reader@brasi.de";
  private static final long USER_READER_DB_ID = 522L;

  /** Voll berechtigt, aber Mitglied KEINER Aufgabe (-> leere Liste). */
  private static final UUID USER_EMPTY_REF = UUID.randomUUID();

  private static final String USER_EMPTY_LOGIN = "inbox-aff-empty@brasi.de";
  private static final long USER_EMPTY_DB_ID = 523L;

  /** Archiv-Actor: Write-Berechtigung, DONE- und LEFT-Aufgaben per JDBC geseedet. */
  private static final UUID USER_ARCH_REF = UUID.randomUUID();

  private static final String USER_ARCH_LOGIN = "inbox-aff-arch@brasi.de";
  private static final long USER_ARCH_DB_ID = 524L;

  private static final long ARCH_ID_BASE = 5240L;

  /** Golden-Rel-Menge eines Listen-Items fuer einen Write-Member mit TASK_WRITE (wie Produktion). */
  private static final java.util.Set<String> EXPECTED_WRITE_MEMBER_ITEM_RELS =
      new java.util.TreeSet<>(java.util.List.of(
              "add-comment",
                  "assignees",
                  "comments",
                  "exit",
                  "flag",
                  "mute",
                  "participants",
                  "pin",
                  "self",
                  "update"));

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
   * Spy auf den echten {@link de.brasi.nova.taskmanagement.security.TaskMembershipGuard}: der Guard
   * ist 1:1 {@code matchesContextTenant && existsWriteMember} (TaskMembershipGuardTest), jeder
   * {@code isWriteMember}-Aufruf entspricht damit genau einer DB-wirksamen Membership-Pruefung. Der
   * Spy wird in die TaskWriteMembershipSpec-Bean injiziert (Spring ersetzt das Bean in-place);
   * KSF-Repository-Proxies waeren dagegen nicht robust zaehlbar.
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
      pemTempFile = Files.createTempFile("inbox-list-affordance-it-jwk-", ".pem");
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
    seedBenutzerIfAbsent(jt, USER_ADMIN_DB_ID, USER_ADMIN_REF, USER_ADMIN_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_READER_DB_ID, USER_READER_REF, USER_READER_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_EMPTY_DB_ID, USER_EMPTY_REF, USER_EMPTY_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_ARCH_DB_ID, USER_ARCH_REF, USER_ARCH_LOGIN, TENANT_A_ID);
    final Integer tableExists =
        jt.queryForObject(
            "SELECT COUNT(*) FROM sys.objects"
                + " WHERE object_id = OBJECT_ID('dbo.system_controller_regel') AND type = 'U'",
            Integer.class);
    assertThat(tableExists)
        .as("system_controller_regel muss als leere Struktur-Kopie existieren (Deploy-Provisioning)")
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

  // ---- B1-Edge: LEERE /tasks -> {} kein _embedded, keine Collection-rels (ZUERST, vor Create) ----

  @Test
  @Order(1)
  @DisplayName(
      "empty_list_kein_embedded_keine_collection_rels:"
          + " voll berechtigter Nicht-Mitglied -> GET /tasks leer -> kein _embedded, KEIN create"
          + " (B1-Edge: InboxSnapshotController ist NICHT Rule-Owner, entityClass nur aus Items)")
  void empty_list_kein_embedded_keine_collection_rels() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String empty =
        buildJwt(
            USER_EMPTY_REF,
            USER_EMPTY_LOGIN,
            "task-management:TASK_READ",
            "task-management:TASK_WRITE",
            "task-management:TASK_CREATE");

    final String body =
        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer " + empty))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[INBOX-AFF-empty] GET /tasks (leer) -> " + body);

    final JsonNode tasks = root.path("_embedded").path("tasks");
    assertThat(tasks.isMissingNode() || tasks.size() == 0)
        .as("leere /tasks: keine Task-Items. Body=" + body)
        .isTrue();
    assertThat(root.path("_links").has("create"))
        .as(
            "B1-Edge: eine LEERE Nicht-Rule-Owner-Collection traegt KEIN create (entityClass nicht"
                + " ableitbar). Body="
                + body)
        .isFalse();
    assertThat(root.path("_links").has("create-from-source"))
        .as("B1-Edge: leere Liste -> kein create-from-source")
        .isFalse();
  }

  // ---- Write-Member: HAL _embedded.tasks + Item-write-rels + Collection create/create-from-source -

  @Test
  @Order(2)
  @DisplayName(
      "write_member_hal_wire_item_rels_und_collection_rels:"
          + " TAG=Task-Write-Member mit TASK_WRITE+TASK_CREATE legt Aufgabe an; GET /tasks -> 200,"
          + " _embedded.tasks, Item traegt self+update (Membership), Collection traegt"
          + " create+create-from-source (Authority); R8 createdAt genau ein ISO-Instant")
  void write_member_hal_wire_item_rels_und_collection_rels() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String admin =
        buildJwt(
            USER_ADMIN_REF,
            USER_ADMIN_LOGIN,
            "task-management:TASK_READ",
            "task-management:TASK_WRITE",
            "task-management:TASK_CREATE");

    final UUID taskId = createTask(mvc, om, admin, "Inbox-Affordance-Write");

    final String body =
        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[INBOX-AFF-write] GET /tasks -> " + body);

    // (a) Produktions-HAL-Wire: Items unter _embedded.tasks.
    final JsonNode tasks = root.path("_embedded").path("tasks");
    assertThat(tasks.isArray())
        .as("BLOCKER: GET /tasks liefert HAL _embedded.tasks (voller Autoconfig). Body=" + body)
        .isTrue();
    final JsonNode item = findById(tasks, taskId);
    assertThat(item).as("angelegte Aufgabe muss im _embedded.tasks-Item erscheinen").isNotNull();

    // (b) Item-rels via Item-entityClass=Task (je Item membership-gegatet). Der Actor ist
    // Write-Member seiner eigenen Aufgabe -> self + update sichtbar (P2-Item-rel-Empirie).
    final JsonNode itemLinks = item.path("_links");
    assertThat(itemLinks.path("self").path("href").isTextual())
        .as("Item-self muss da sein (self-Link auf das Detail). Item=" + item)
        .isTrue();
    assertThat(itemLinks.path("update").path("href").isTextual())
        .as(
            "P2: Listen-Item (Nicht-Rule-Owner) traegt die Task-Item-write-rel update fuer den"
                + " Write-Member (Item-entityClass=Task). Fehlt sie -> Finding, NICHT aufweichen.")
        .isTrue();

    // (c) Collection-rels create + create-from-source (Slice-3-deklariert, authority-gegatet).
    assertThat(root.path("_links").path("create").path("href").isTextual())
        .as("P2: nicht-leere /tasks-Collection traegt create (TASK_WRITE). Body=" + body)
        .isTrue();
    assertThat(root.path("_links").path("create-from-source").path("href").isTextual())
        .as("P2: nicht-leere /tasks-Collection traegt create-from-source (TASK_CREATE). Body=" + body)
        .isTrue();

    // (e) R8-Wire-Regression ueber HTTP-HAL: GENAU EIN createdAt, ISO-8601-Instant (mit 'T' und 'Z'),
    // kein LocalDateTime-Leak; modelState additiv; kein geerbtes version.
    final JsonNode createdAt = item.path("createdAt");
    assertThat(createdAt.isTextual())
        .as("R8: createdAt bleibt ein ISO-8601-Instant-String. Item=" + item)
        .isTrue();
    final String createdAtStr = createdAt.asText();
    assertThat(createdAtStr).contains("T");
    assertThat(createdAtStr.endsWith("Z"))
        .as("R8: createdAt ist ein UTC-Instant mit 'Z' (kein nackter LocalDateTime). Wert=" + createdAtStr)
        .isTrue();
    assertThat(item.has("lastActivityAt"))
        .as("lastActivityAt (Instant, KEINE BaseModel-Kollision) bleibt erhalten")
        .isTrue();
    assertThat(item.has("modelState")).as("modelState (BaseModel) additiv").isTrue();
    assertThat(item.has("version"))
        .as("R8/Wire: geerbtes version darf NICHT im Listen-Item-Wire erscheinen")
        .isFalse();
    // Fachliche Alt-Felder stichprobenartig erhalten.
    assertThat(item.path("id").asText()).isEqualTo(taskId.toString());
    assertThat(item.path("title").asText()).isEqualTo("Inbox-Affordance-Write");
    assertThat(item.has("isPinned")).as("is-Marker-Wirename erhalten").isTrue();
  }

  // ---- Per-Authority-Gatung der Collection-rels: TASK_WRITE ohne TASK_CREATE -------------------

  @Test
  @Order(3)
  @DisplayName(
      "collection_rels_sind_per_authority_gegatet:"
          + " Nutzer mit TASK_WRITE ABER OHNE TASK_CREATE legt eine Aufgabe an; GET /tasks -> die"
          + " Collection traegt create (TASK_WRITE) ABER NICHT create-from-source (kein TASK_CREATE)")
  void collection_rels_sind_per_authority_gegatet() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // TASK_WRITE erlaubt die manuelle Anlage (POST /tasks); der Ersteller wird Write-Member und sieht
    // die Aufgabe in seiner /tasks-Liste (kein roher Participant-Insert noetig). TASK_CREATE fehlt
    // bewusst -> create-from-source muss unterdrueckt sein, create aber nicht.
    final String writeOnly =
        buildJwt(
            USER_READER_REF,
            USER_READER_LOGIN,
            "task-management:TASK_READ",
            "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, writeOnly, "Inbox-Affordance-WriteOnly");

    final String body =
        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer " + writeOnly))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[INBOX-AFF-writeonly] GET /tasks -> " + body);

    final JsonNode item = findById(root.path("_embedded").path("tasks"), taskId);
    assertThat(item)
        .as("die selbst angelegte Aufgabe muss in der /tasks-Liste des Erstellers erscheinen")
        .isNotNull();

    // create ist per TASK_WRITE gegatet -> vorhanden.
    assertThat(root.path("_links").path("create").path("href").isTextual())
        .as("create (TASK_WRITE) muss vorhanden sein. Body=" + body)
        .isTrue();
    // create-from-source ist per TASK_CREATE gegatet -> fehlt (per-authority-Trennung bewiesen).
    assertThat(root.path("_links").has("create-from-source"))
        .as("ohne TASK_CREATE darf die Collection KEIN create-from-source tragen. Body=" + body)
        .isFalse();
    // Item-rels, die TASK_WRITE-Autoritaet verlangen, erscheinen (Write-Member + TASK_WRITE):
    // assignees/participants (AND-Spec). self/update ebenfalls (Write-Member).
    assertThat(item.path("_links").path("assignees").path("href").isTextual())
        .as("assignees (TASK_WRITE + Write-Member) sichtbar fuer den Ersteller")
        .isTrue();
  }

  // ---- Per-Item-Differenzierung (Slice-4-Auflage) ---------------------------------------------

  @Test
  @Order(4)
  @DisplayName(
      "archiv_all_per_item_differenziert_done_hat_update_left_nicht:"
          + " derselbe Actor hat eine DONE-Aufgabe (Write-Member, EXIT_KIND NULL) und eine LEFT-Aufgabe"
          + " (EXIT_KIND gesetzt); /tasks/archive?filter=all -> DONE-Item traegt update, LEFT-Item"
          + " nicht, beide tragen self")
  void archiv_all_per_item_differenziert_done_hat_update_left_nicht() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final long doneStatus = lookupId("DIC_TASK_STATUS", "DONE");
    final long normal = lookupId("DIC_TASK_PRIORITY", "NORMAL");
    final long direction = lookupId("DIC_TASK_DIRECTION", "CUSTOMER_INTERNAL");

    final long doneTaskId = ARCH_ID_BASE + 1;
    final UUID doneUuid = UUID.randomUUID();
    seedTask(doneTaskId, doneUuid, doneStatus, normal, direction, "Archiv-DONE");
    seedParticipant(doneTaskId + 1000, doneTaskId, USER_ARCH_REF, null);
    // DONE-Archiv-Mitgliedschaft braucht einen aktiven UserState (wie InboxSnapshotControllerIT).
    seedUserState(doneTaskId + 2000, doneTaskId, USER_ARCH_REF);

    final long leftTaskId = ARCH_ID_BASE + 2;
    final UUID leftUuid = UUID.randomUUID();
    // Bewusst ebenfalls Status DONE: DONE- und LEFT-Item unterscheiden sich NUR im EXIT_KIND, so
    // dass der Unterschied der Item-Rels (update) allein auf der Membership (Write-Member vs.
    // ausgetreten) beruht und nicht auf dem Task-Status.
    seedTask(leftTaskId, leftUuid, doneStatus, normal, direction, "Archiv-LEFT");
    seedParticipant(leftTaskId + 1000, leftTaskId, USER_ARCH_REF, "UNSUBSCRIBED");

    final String token =
        buildJwt(
            USER_ARCH_REF,
            USER_ARCH_LOGIN,
            "task-management:TASK_READ",
            "task-management:TASK_WRITE");
    org.mockito.Mockito.clearInvocations(membershipGuardSpy);
    final String body =
        mvc.perform(get("/api/v1/tasks/archive?filter=all").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    System.out.println("[INBOX-AFF-archive] GET /tasks/archive?filter=all -> " + body);
    final JsonNode tasks = om.readTree(body).path("_embedded").path("tasks");
    // N+1-Fix (Pflicht-Test 7): je Item genau EINE DB-wirksame Membership-Pruefung in EINEM Request,
    // und der Schluessel trennt die Tasks (DONE-Item update ja, LEFT-Item nein).
    org.mockito.Mockito.verify(membershipGuardSpy, org.mockito.Mockito.times(tasks.size()))
        .isWriteMember(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.anyLong());
    final JsonNode done = findById(tasks, doneUuid);
    final JsonNode left = findById(tasks, leftUuid);
    assertThat(done).as("DONE-Item im Archiv. Body=" + body).isNotNull();
    assertThat(left).as("LEFT-Item im Archiv. Body=" + body).isNotNull();

    assertThat(done.path("_links").path("self").path("href").asText())
        .as("DONE-Item self")
        .contains(doneUuid.toString());
    assertThat(done.path("_links").path("update").path("href").isTextual())
        .as("DONE-Item (Write-Member) traegt update. Item=" + done)
        .isTrue();
    assertThat(left.path("_links").path("self").path("href").asText())
        .as("LEFT-Item traegt self. Item=" + left)
        .contains(leftUuid.toString());
    assertThat(left.path("_links").has("comments"))
        .as("LEFT-Item traegt comments (UNCONDITIONAL). Item=" + left)
        .isTrue();
    for (final String writeRel :
        new String[] {
          "update", "assignees", "participants", "pin", "flag", "mute", "exit", "add-comment"
        }) {
      assertThat(left.path("_links").has(writeRel))
          .as("LEFT-Item (kein Write-Member) darf '" + writeRel + "' nicht tragen. Item=" + left)
          .isFalse();
    }
  }

  @Test
  @Order(5)
  @DisplayName(
      "tasks_liste_item_links_tragen_eigene_item_id:"
          + " >=2 eigene Items -> self/update-href jedes Items enthaelt die EIGENE ID und nie die"
          + " ID eines anderen Items (keine Vertauschung/Wiederverwendung)")
  void tasks_liste_item_links_tragen_eigene_item_id() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String admin =
        buildJwt(
            USER_ADMIN_REF,
            USER_ADMIN_LOGIN,
            "task-management:TASK_READ",
            "task-management:TASK_WRITE",
            "task-management:TASK_CREATE");
    final UUID first = createTask(mvc, om, admin, "Inbox-Aff-Identitaet-1");
    final UUID second = createTask(mvc, om, admin, "Inbox-Aff-Identitaet-2");

    final String body =
        mvc.perform(get("/api/v1/tasks?limit=100").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode tasks = om.readTree(body).path("_embedded").path("tasks");
    assertThat(tasks.size()).as("mindestens 2 Items").isGreaterThanOrEqualTo(2);
    assertThat(findById(tasks, first)).isNotNull();
    assertThat(findById(tasks, second)).isNotNull();

    for (final JsonNode item : tasks) {
      final String ownId = item.path("id").asText();
      for (final String rel : new String[] {"self", "update"}) {
        final String href = item.path("_links").path(rel).path("href").asText("");
        assertThat(href).as("Item " + ownId + " " + rel + "-href").contains(ownId);
        for (final JsonNode other : tasks) {
          final String otherId = other.path("id").asText();
          if (!otherId.equals(ownId)) {
            assertThat(href)
                .as("Item " + ownId + " " + rel + " darf fremde ID " + otherId + " nicht tragen")
                .doesNotContain(otherId);
          }
        }
      }
    }
  }

  @Test
  @Order(6)
  @DisplayName(
      "tasks_liste_membership_pruefung_je_item_genau_einmal:"
          + " >=3 eigene Items -> GET /tasks fuehrt genau N (nicht 6N/8N) isWriteMember-Pruefungen aus,"
          + " Rel-Menge je Item unveraendert")
  void tasks_liste_membership_pruefung_je_item_genau_einmal() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String admin =
        buildJwt(
            USER_ADMIN_REF,
            USER_ADMIN_LOGIN,
            "task-management:TASK_READ",
            "task-management:TASK_WRITE",
            "task-management:TASK_CREATE");
    createTask(mvc, om, admin, "Inbox-Aff-N1-1");
    createTask(mvc, om, admin, "Inbox-Aff-N1-2");
    createTask(mvc, om, admin, "Inbox-Aff-N1-3");

    org.mockito.Mockito.clearInvocations(membershipGuardSpy);
    final String body =
        mvc.perform(get("/api/v1/tasks?limit=100").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode tasks = om.readTree(body).path("_embedded").path("tasks");
    final int n = tasks.size();
    assertThat(n).as("mindestens 3 Items").isGreaterThanOrEqualTo(3);

    final int calls =
        org.mockito.Mockito.mockingDetails(membershipGuardSpy).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("isWriteMember"))
            .toList()
            .size();
    System.out.println("[INBOX-AFF-N1] items=" + n + " isWriteMember-Calls=" + calls);
    assertThat(calls).as("genau eine Membership-Pruefung je Item (nicht 6N/8N)").isEqualTo(n);

    // Golden: Rel-Menge je Item exakt die bisherige (Write-Member + TASK_WRITE).
    for (final JsonNode item : tasks) {
      final java.util.Set<String> rels = new java.util.TreeSet<>();
      item.path("_links").fieldNames().forEachRemaining(rels::add);
      System.out.println("[INBOX-AFF-N1] item rels=" + rels);
      assertThat(rels)
          .as("Rel-Menge Item " + item.path("id").asText())
          .isEqualTo(EXPECTED_WRITE_MEMBER_ITEM_RELS);
    }
  }

  // ---- Hilfen --------------------------------------------------------------------------------

  private long lookupId(final String table, final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[" + table + "] WHERE CODE = ?", Long.class, code);
  }

  private void seedTask(
      final long id,
      final UUID externalId,
      final long statusId,
      final long priorityId,
      final long directionId,
      final String title) {
    final java.sql.Timestamp now = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TITLE, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID,"
            + " LAST_ACTIVITY_AT, COMMENT_COUNT) "
            + "VALUES (?, 'ACTIVE', 'inbox-aff-it', ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 0)",
        id, now, externalId, TENANT_A_ID, title, statusId, priorityId, directionId, TENANT_A_ID,
        now);
  }

  private void seedParticipant(
      final long id, final long taskId, final UUID participantRef, final String exitKind) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND) "
            + "VALUES (?, 'ACTIVE', 'inbox-aff-it', getdate(), ?, 1, ?, ?, ?, 'PERSON', ?, 1, 1, 1, ?)",
        id, UUID.randomUUID(), TENANT_A_ID, taskId, participantRef, TENANT_A_ID, exitKind);
  }

  private void seedUserState(final long id, final long taskId, final UUID userRef) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT) "
            + "VALUES (?, 'ACTIVE', 'inbox-aff-it', getdate(), ?, 1, ?, ?, ?, ?, 0, 0, 0, NULL)",
        id, UUID.randomUUID(), TENANT_A_ID, taskId, userRef, TENANT_A_ID);
  }

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

  private static JsonNode findById(final JsonNode items, final UUID id) {
    if (items == null || !items.isArray()) {
      return null;
    }
    for (final JsonNode node : items) {
      if (id.toString().equals(node.path("id").asText())) {
        return node;
      }
    }
    return null;
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
        .claim("lastActiveTenantId", TENANT_A_UUID.toString())
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

  // ---- IT-Context: identisch zu TaskDetailAffordanceIT.ItConfig (voller Autoconfig + HATEOAS) -----

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
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class),
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
    io.keel.framework.api.security.AuditorProvider inboxListAffordanceAuditorProvider() {
      return () -> "inbox-list-affordance-it";
    }
  }
}
