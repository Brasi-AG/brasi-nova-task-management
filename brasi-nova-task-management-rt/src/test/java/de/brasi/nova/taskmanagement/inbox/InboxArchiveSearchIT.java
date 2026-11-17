package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test fuer {@code GET /api/v1/tasks/archive/search} (Intake #149) gegen echtes MSSQL
 * (Wegwerf-DB), Nachbar von {@link InboxSnapshotControllerIT} mit demselben Kontext-Aufbau.
 *
 * <p>Beweist: Treffer ueber Titel, Beschreibung, Tag-Bezeichnung und Anzeigename aktiver Teilnehmer
 * (case-insensitiv); jede Aufgabe hoechstens einmal; {@code %}, {@code _} und {@code [} werden
 * woertlich gesucht; nur eigene Archivaufgaben (Mitgliedschaft hart, keine aktiven, keine fremden,
 * kein verwaister State); Filter {@code done}/{@code left}/{@code all}; Paging ueber den Cursor
 * ohne Luecke und Dublette; 410 nach Generation-Bump; 400/401 bei ungueltiger Eingabe bzw. ohne
 * Anmeldung.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = InboxArchiveSearchIT.ItConfig.class,
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
class InboxArchiveSearchIT {

  private static final String TEST_KID = "archive-search-it-key";
  private static final String SEARCH_URL = "/api/v1/tasks/archive/search";

  /** Mandant (eigener Block 540, eigene Wegwerf-DB). */
  private static final long TENANT_ID = 540L;

  private static final UUID TENANT_UUID = UUID.randomUUID();

  /** Suchender Benutzer. */
  private static final UUID USER_S_REF = UUID.randomUUID();

  private static final String USER_S_LOGIN = "user-s-search@brasi.de";
  private static final long USER_S_DB_ID = 541L;

  /** Mitbeteiligte mit eindeutigem Anzeigenamen (Treffer ueber Teilnehmername). */
  private static final UUID USER_M_REF = UUID.randomUUID();

  private static final String USER_M_LOGIN = "user-m-search@brasi.de";
  private static final long USER_M_DB_ID = 542L;

  /** Ausgetretener Teilnehmer (Name darf NICHT treffen). */
  private static final UUID USER_E_REF = UUID.randomUUID();

  private static final String USER_E_LOGIN = "user-e-search@brasi.de";
  private static final long USER_E_DB_ID = 543L;

  /** Fremder Benutzer (seine Aufgaben sind fuer S unsichtbar). */
  private static final UUID USER_X_REF = UUID.randomUUID();

  private static final String USER_X_LOGIN = "user-x-search@brasi.de";
  private static final long USER_X_DB_ID = 544L;

  /** Nutzer fuer Paging + 410 (isolierte Generation). */
  private static final UUID USER_P_REF = UUID.randomUUID();

  private static final String USER_P_LOGIN = "user-p-search@brasi.de";
  private static final long USER_P_DB_ID = 545L;

  /**
   * ID-Block: Aufgaben +1..+14 und +300.., Ersteller-Zeilen von S +100+n, States +200+n, weitere
   * Teilnehmer +600+n, Paging +400../+500.., Tags +900.., Zuordnungen +950...
   */
  private static final long ID_BASE = 5400L;

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

  // Aufgaben-UUIDs (Seed einmal je Klasse, alle Tests lesen nur).
  private static final UUID T_TITLE = UUID.randomUUID();
  private static final UUID T_DESC = UUID.randomUUID();
  private static final UUID T_TAG = UUID.randomUUID();
  private static final UUID T_TAG_INACTIVE = UUID.randomUUID();
  private static final UUID T_NAME = UUID.randomUUID();
  private static final UUID T_NAME_EXITED = UUID.randomUUID();
  private static final UUID T_DUP = UUID.randomUUID();
  private static final UUID T_ESC = UUID.randomUUID();
  private static final UUID T_ESC_DECOY = UUID.randomUUID();
  private static final UUID T_FOREIGN = UUID.randomUUID();
  private static final UUID T_ACTIVE = UUID.randomUUID();
  private static final UUID T_ORPHAN = UUID.randomUUID();
  private static final UUID T_LEFT_UNSUB = UUID.randomUUID();
  private static final UUID T_LEFT_EXIT = UUID.randomUUID();

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
      pemTempFile = Files.createTempFile("archive-search-it-jwk-", ".pem");
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
          // Cleanup best-effort
        }
      }
    }
  }

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * Seed einmal je Klasse. Jede Aufgabe traegt genau die Eigenschaft, ueber die sie gefunden werden
   * soll; die Suchbegriffe ueberschneiden sich nicht, ausser wo der Test es verlangt.
   */
  @BeforeAll
  static void seed(@Autowired final JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_ID, TENANT_UUID);
    seedBenutzer(jt, USER_S_DB_ID, USER_S_REF, USER_S_LOGIN, "Sabine Suchende");
    seedBenutzer(jt, USER_M_DB_ID, USER_M_REF, USER_M_LOGIN, "Mara Mustermann");
    seedBenutzer(jt, USER_E_DB_ID, USER_E_REF, USER_E_LOGIN, "Egon Ehemaliger");
    seedBenutzer(jt, USER_X_DB_ID, USER_X_REF, USER_X_LOGIN, "Xaver Fremd");
    seedBenutzer(jt, USER_P_DB_ID, USER_P_REF, USER_P_LOGIN, "Paul Pager");

    final long open = dicId(jt, "DIC_TASK_STATUS", "OPEN");
    final long done = dicId(jt, "DIC_TASK_STATUS", "DONE");
    final long cancelled = dicId(jt, "DIC_TASK_STATUS", "CANCELLED");
    final LocalDateTime base = LocalDateTime.now().minusHours(2);

    // Titel (gemischte Gross-/Kleinschreibung beweist case-insensitiv).
    archivedFor(jt, 1, T_TITLE, done, base.plusMinutes(1), "QUARTALSBERICHT erstellen", null);
    // Beschreibung.
    archivedFor(jt, 2, T_DESC, done, base.plusMinutes(2), "Inventur", "Abgleich in Halle Sieben");
    // Tag ueber aktive Zuordnung; Tag-Katalogeintrag selbst darf deaktiviert sein.
    archivedFor(jt, 3, T_TAG, cancelled, base.plusMinutes(3), "Tagtraeger", null);
    seedTag(jt, ID_BASE + 900, "Steuerpruefung", "DEACTIVE");
    seedTagAssignment(jt, ID_BASE + 950, ID_BASE + 3, ID_BASE + 900, "ACTIVE");
    // Deaktivierte Zuordnung trifft NICHT.
    archivedFor(jt, 4, T_TAG_INACTIVE, done, base.plusMinutes(4), "Ohne Tag", null);
    seedTag(jt, ID_BASE + 901, "Steuerakte", "ACTIVE");
    seedTagAssignment(jt, ID_BASE + 951, ID_BASE + 4, ID_BASE + 901, "DEACTIVE");
    // Teilnehmername (Assignee M, aktiv).
    archivedFor(jt, 5, T_NAME, done, base.plusMinutes(5), "Namenstreffer", null);
    seedParticipant(jt, ID_BASE + 605, ID_BASE + 5, USER_M_REF, false, true, false, null);
    // Ausgetretener Teilnehmer E (kein Ersteller) trifft NICHT.
    archivedFor(jt, 6, T_NAME_EXITED, done, base.plusMinutes(6), "Exittreffer", null);
    seedParticipant(jt, ID_BASE + 606, ID_BASE + 6, USER_E_REF, false, true, true, "UNSUBSCRIBED");
    // Mehrfachtreffer (Titel + Beschreibung + zwei Tags + Name) -> genau EIN Item.
    archivedFor(jt, 7, T_DUP, done, base.plusMinutes(7), "Alpha Titel", "Alpha Beschreibung");
    seedTag(jt, ID_BASE + 902, "Alpha-Eins", "ACTIVE");
    seedTag(jt, ID_BASE + 903, "Alpha-Zwei", "ACTIVE");
    seedTagAssignment(jt, ID_BASE + 952, ID_BASE + 7, ID_BASE + 902, "ACTIVE");
    seedTagAssignment(jt, ID_BASE + 953, ID_BASE + 7, ID_BASE + 903, "ACTIVE");
    seedBenutzer(jt, ID_BASE + 800, UUID.randomUUID(), "alpha1@brasi.de", "Alpha Eins");
    // Escaping: Treffer nur mit woertlichem %, _ und [.
    archivedFor(jt, 8, T_ESC, done, base.plusMinutes(8), "Rabatt 50% auf Lager_A [neu]", null);
    archivedFor(jt, 9, T_ESC_DECOY, done, base.plusMinutes(9), "Rabatt 500 auf LagerXA neu", null);
    // Sichtbarkeit: fremde Aufgabe (S nicht Mitglied).
    seedTask(
        jt, ID_BASE + 10, T_FOREIGN, done, base.plusMinutes(10), "Quartalsbericht fremd", null);
    seedParticipant(jt, ID_BASE + 110, ID_BASE + 10, USER_X_REF, true, true, true, null);
    seedUserState(jt, ID_BASE + 210, ID_BASE + 10, USER_X_REF, null);
    // Sichtbarkeit: aktive (OPEN) Aufgabe von S ist nicht im Archiv.
    archivedFor(jt, 11, T_ACTIVE, open, base.plusMinutes(11), "Quartalsbericht offen", null);
    // Sichtbarkeit: verwaister State ohne Teilnehmerzeile.
    seedTask(jt, ID_BASE + 12, T_ORPHAN, done, base.plusMinutes(12), "Quartal verwaist", null);
    seedUserState(jt, ID_BASE + 212, ID_BASE + 12, USER_S_REF, null);
    // left ueber UNSUBSCRIBED_AT (Status OPEN).
    seedTask(
        jt, ID_BASE + 13, T_LEFT_UNSUB, open, base.plusMinutes(13), "Quartal abgemeldet", null);
    seedParticipant(jt, ID_BASE + 113, ID_BASE + 13, USER_S_REF, true, true, true, null);
    seedUserState(jt, ID_BASE + 213, ID_BASE + 13, USER_S_REF, base.plusMinutes(30));
    // left ueber EXIT_KIND der Teilnehmerzeile (Status OPEN).
    seedTask(jt, ID_BASE + 14, T_LEFT_EXIT, open, base.plusMinutes(14), "Quartal uebergeben", null);
    seedParticipant(jt, ID_BASE + 114, ID_BASE + 14, USER_S_REF, false, true, true, "HANDOVER");
    seedUserState(jt, ID_BASE + 214, ID_BASE + 14, USER_S_REF, null);

    // Paging-Satz fuer P: fuenf erledigte "Serie"-Aufgaben.
    for (int i = 0; i < 5; i++) {
      final long taskId = ID_BASE + 300 + i;
      seedTask(jt, taskId, UUID.randomUUID(), done, base.plusMinutes(40 + i), "Serie " + i, null);
      seedParticipant(jt, ID_BASE + 400 + i, taskId, USER_P_REF, true, true, true, null);
      seedUserState(jt, ID_BASE + 500 + i, taskId, USER_P_REF, null);
    }
  }

  @Test
  @DisplayName("treffer_ueber_titel_beschreibung_tag_und_teilnehmername")
  void treffer_ueber_titel_beschreibung_tag_und_teilnehmername() throws Exception {
    assertThat(search(userS(), "quartalsBERICHT", null)).containsExactly(T_TITLE.toString());
    assertThat(search(userS(), "halle sieben", null)).containsExactly(T_DESC.toString());
    // Tag-Treffer trotz deaktiviertem Katalog-Tag; deaktivierte Zuordnung trifft nicht.
    assertThat(search(userS(), "steuer", null)).containsExactly(T_TAG.toString());
    // Name des aktiven Assignees trifft; Name des ausgetretenen Teilnehmers trifft nicht.
    assertThat(search(userS(), "mustermann", null)).containsExactly(T_NAME.toString());
    assertThat(search(userS(), "ehemaliger", null)).isEmpty();
  }

  @Test
  @DisplayName("mehrfachtreffer_liefert_jede_aufgabe_genau_einmal")
  void mehrfachtreffer_liefert_jede_aufgabe_genau_einmal() throws Exception {
    // Seed: Teilnehmer "Alpha Eins" an der Dup-Aufgabe (dritte Trefferquelle).
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND) "
            + "SELECT ?, 'ACTIVE', 'search-it', getdate(), NEWID(), 1, ?, ?, b.EXTERNALID,"
            + " 'PERSON', ?, 0, 0, 1, NULL FROM [dbo].[PROD_BENUTZER] b WHERE b.ID = ?",
        ID_BASE + 607,
        ID_BASE + 607,
        TENANT_ID,
        ID_BASE + 7,
        TENANT_ID,
        ID_BASE + 800);
    assertThat(search(userS(), "alpha", null)).containsExactly(T_DUP.toString());
  }

  @Test
  @DisplayName("prozent_unterstrich_und_klammer_werden_woertlich_gesucht")
  void prozent_unterstrich_und_klammer_werden_woertlich_gesucht() throws Exception {
    assertThat(search(userS(), "50%", null)).containsExactly(T_ESC.toString());
    assertThat(search(userS(), "lager_a", null)).containsExactly(T_ESC.toString());
    assertThat(search(userS(), "[neu]", null)).containsExactly(T_ESC.toString());
    // Gegenprobe: der Lockvogel ist sichtbar und ueber einen eigenen Begriff auffindbar.
    assertThat(search(userS(), "laGERxa", null)).containsExactly(T_ESC_DECOY.toString());
  }

  @Test
  @DisplayName("nur_eigene_archivaufgaben_und_filter_done_left_all")
  void nur_eigene_archivaufgaben_und_filter_done_left_all() throws Exception {
    // all: eigenes Erledigtes + beide left-Wege; NICHT fremd, aktiv oder verwaist.
    assertThat(search(userS(), "quartal", "all"))
        .containsExactly(T_LEFT_EXIT.toString(), T_LEFT_UNSUB.toString(), T_TITLE.toString());
    assertThat(search(userS(), "quartal", null))
        .containsExactly(T_LEFT_EXIT.toString(), T_LEFT_UNSUB.toString(), T_TITLE.toString());
    assertThat(search(userS(), "quartal", "done")).containsExactly(T_TITLE.toString());
    assertThat(search(userS(), "quartal", "left"))
        .containsExactly(T_LEFT_EXIT.toString(), T_LEFT_UNSUB.toString());
    // Der fremde Benutzer findet seine eigene Aufgabe, aber keine von S.
    assertThat(search(jwt(USER_X_REF, USER_X_LOGIN), "quartal", null))
        .containsExactly(T_FOREIGN.toString());
  }

  @Test
  @DisplayName("paging_ueber_cursor_ohne_luecke_und_dublette_dann_410_nach_bump")
  void paging_ueber_cursor_ohne_luecke_und_dublette_dann_410_nach_bump() throws Exception {
    final MockMvc mvc = buildMvc();
    final String token = jwt(USER_P_REF, USER_P_LOGIN);
    final List<String> all = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    String firstCursor = null;
    do {
      final MockHttpServletRequestBuilder request =
          get(SEARCH_URL).queryParam("query", "serie").queryParam("limit", "2");
      if (cursor != null) {
        request.queryParam("cursor", cursor);
      }
      final JsonNode page = getJson(mvc, request, token);
      all.addAll(idsOf(page));
      cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
      if (pages == 0) {
        firstCursor = cursor;
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        assertThat(page.hasNonNull("asOf")).isTrue();
      }
      pages++;
    } while (cursor != null && pages < 10);
    assertThat(pages).isEqualTo(3);
    assertThat(all).hasSize(5).doesNotHaveDuplicates();

    // Mitgliedschafts-Generation des Anfragers aendert sich -> alter Cursor 410.
    bumpGeneration(USER_P_REF);
    mvc.perform(
            get(SEARCH_URL)
                .queryParam("query", "serie")
                .queryParam("limit", "2")
                .queryParam("cursor", firstCursor)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isGone());
  }

  @Test
  @DisplayName("ungueltige_eingaben_400_anonym_401_ohne_grant_200")
  void ungueltige_eingaben_400_anonym_401_ohne_grant_200() throws Exception {
    final MockMvc mvc = buildMvc();
    final String token = userS();
    mvc.perform(get(SEARCH_URL).header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(SEARCH_URL).queryParam("query", "  ").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(SEARCH_URL)
                .queryParam("query", "a".repeat(201))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(SEARCH_URL)
                .queryParam("query", "abc")
                .queryParam("filter", "pending")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(SEARCH_URL)
                .queryParam("query", "abc")
                .queryParam("cursor", "kaputt")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    mvc.perform(get(SEARCH_URL).queryParam("query", "abc")).andExpect(status().isUnauthorized());
    // Self-Service: angemeldet ohne task-management-Recht -> 200 (aktor-scoped).
    mvc.perform(
            get(SEARCH_URL).queryParam("query", "abc").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  private String userS() {
    return jwt(USER_S_REF, USER_S_LOGIN);
  }

  private List<String> search(final String token, final String query, final String filter)
      throws Exception {
    final MockHttpServletRequestBuilder request = get(SEARCH_URL).queryParam("query", query);
    if (filter != null) {
      request.queryParam("filter", filter);
    }
    return idsOf(getJson(buildMvc(), request, token));
  }

  private JsonNode getJson(
      final MockMvc mvc, final MockHttpServletRequestBuilder request, final String token)
      throws Exception {
    return new ObjectMapper()
        .readTree(
            mvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
  }

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  /** IDs der Items; toleriert wie {@link InboxSnapshotControllerIT} alle Collection-Wire-Formen. */
  private static List<String> idsOf(final JsonNode page) {
    JsonNode items = null;
    final JsonNode embedded = page.get("_embedded");
    if (embedded != null && embedded.get("tasks") != null) {
      items = embedded.get("tasks");
    } else if (page.get("content") != null) {
      items = page.get("content");
    } else if (page.get("items") != null) {
      items = page.get("items");
    }
    final List<String> ids = new ArrayList<>();
    if (items == null) {
      assertThat(page.has("hasMore")).as("Unbekannte Collection-Wire-Form: %s", page).isTrue();
      return ids;
    }
    for (final JsonNode item : items) {
      ids.add(item.get("id").asText());
    }
    return ids;
  }

  private void bumpGeneration(final UUID userRef) {
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_INBOX_GENERATION]"
            + " WHERE TENANT_ID = ? AND USER_REF = ?) "
            + "INSERT INTO [dbo].[TASK_INBOX_GENERATION]"
            + " (ID, EXTERNALID, VERSION, TENANT_ID, USER_REF, GENERATION)"
            + " VALUES (?, NEWID(), 1, ?, ?, 0)",
        TENANT_ID,
        userRef,
        ID_BASE + 990,
        TENANT_ID,
        userRef);
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_INBOX_GENERATION] SET GENERATION = GENERATION + 1"
            + " WHERE TENANT_ID = ? AND USER_REF = ?",
        TENANT_ID,
        userRef);
  }

  private static long dicId(final JdbcTemplate jt, final String table, final String code) {
    return jt.queryForObject(
        "SELECT ID FROM [dbo].[" + table + "] WHERE CODE = ?", Long.class, code);
  }

  /** Aufgabe von S mit vollstaendiger Mitgliedschaft (Ersteller, aktiver State). */
  private static void archivedFor(
      final JdbcTemplate jt,
      final int offset,
      final UUID uuid,
      final long statusId,
      final LocalDateTime lastActivityAt,
      final String title,
      final String description) {
    final long taskId = ID_BASE + offset;
    seedTask(jt, taskId, uuid, statusId, lastActivityAt, title, description);
    seedParticipant(jt, ID_BASE + 100 + offset, taskId, USER_S_REF, true, true, true, null);
    seedUserState(jt, ID_BASE + 200 + offset, taskId, USER_S_REF, null);
  }

  private static void seedTask(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final long statusId,
      final LocalDateTime lastActivityAt,
      final String title,
      final String description) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TITLE, DESCRIPTION, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID,"
            + " LAST_ACTIVITY_AT, COMMENT_COUNT) "
            + "VALUES (?, 'ACTIVE', 'search-it', ?, ?, 1, ?, ?, ?, ?,"
            + " (SELECT ID FROM [dbo].[DIC_TASK_PRIORITY] WHERE CODE = 'NORMAL'),"
            + " (SELECT ID FROM [dbo].[DIC_TASK_DIRECTION] WHERE CODE = 'CUSTOMER_INTERNAL'),"
            + " ?, ?, 0)",
        id,
        id,
        Timestamp.valueOf(lastActivityAt),
        externalId,
        TENANT_ID,
        title,
        description,
        statusId,
        TENANT_ID,
        Timestamp.valueOf(lastActivityAt));
  }

  private static void seedParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID participantRef,
      final boolean creator,
      final boolean assignee,
      final boolean participant,
      final String exitKind) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND) "
            + "VALUES (?, 'ACTIVE', 'search-it', DATEADD(hour, -3, getdate()), ?, 1, ?, ?, ?,"
            + " 'PERSON', ?, ?, ?, ?, ?)",
        id,
        id,
        UUID.randomUUID(),
        TENANT_ID,
        taskId,
        participantRef,
        TENANT_ID,
        creator ? 1 : 0,
        assignee ? 1 : 0,
        participant ? 1 : 0,
        exitKind);
  }

  private static void seedUserState(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID userRef,
      final LocalDateTime unsubscribedAt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT) "
            + "VALUES (?, 'ACTIVE', 'search-it', DATEADD(hour, -3, getdate()), ?, 1, ?, ?, ?, ?,"
            + " 0, 0, 0, ?)",
        id,
        id,
        UUID.randomUUID(),
        TENANT_ID,
        taskId,
        userRef,
        TENANT_ID,
        unsubscribedAt == null ? null : Timestamp.valueOf(unsubscribedAt));
  }

  private static void seedTag(
      final JdbcTemplate jt, final long id, final String name, final String entityStatus) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_TAG] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_TAG] "
            + "(ID, ENTITY_STATUS, BEZEICHNUNG, ENTRY_TYPE, EXTERNALID, VERSION, TENANT_ID) "
            + "VALUES (?, ?, ?, 'SYSTEM_PUBLIC', ?, 1, 0)",
        id,
        id,
        entityStatus,
        name,
        UUID.randomUUID());
  }

  private static void seedTagAssignment(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final long tagId,
      final String entityStatus) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_TAG_ASSIGNMENT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_TAG_ASSIGNMENT] "
            + "(ID, ENTITY_STATUS, TENANT_ID, TASK_ID, TAG_ID) VALUES (?, ?, ?, ?, ?)",
        id,
        id,
        entityStatus,
        TENANT_ID,
        taskId,
        tagId);
  }

  private static String jwt(final UUID userUuid, final String loginName) {
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
        .claim("authorities", List.of())
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

  private static void seedBenutzer(
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

  // ---- IT-Konfigurations-Context (wie InboxSnapshotControllerIT) ----------------------------

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
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.dictionary",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.tag",
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
    de.brasi.nova.taskmanagement.security.TaskMembershipGuard.class,
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "search-it";
    }
  }
}
