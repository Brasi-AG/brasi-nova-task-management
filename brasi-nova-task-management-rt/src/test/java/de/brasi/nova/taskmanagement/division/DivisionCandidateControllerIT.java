package de.brasi.nova.taskmanagement.division;

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
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test der Vorschlags-API {@code GET /api/v1/tasks/divisions} (Intake #190, Task 9)
 * gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Belegt: nur aktive Abteilungen des eigenen Mandanten; {@code memberCount} zaehlt nur
 * Mitglieder mit aktivem Benutzer im selben Mandanten; Sortierung nach Name (unbenannte zuletzt),
 * dann nach Abteilungs-UUID; {@code q} filtert woertlich ueber den Namen (Sonderzeichen {@code %},
 * {@code _}, {@code [} entschaerft, Lockvoegel samt Gegenprobe), unbenannte fallen dabei heraus;
 * {@code ids} liefert die Vorschau bekannter aktiver Abteilungen und gewinnt vor {@code q} und
 * {@code limit}; {@code limit} 1 bis 100, Vorgabe 20; 400 im KSF-Format, 401 anonym, 403 ohne
 * TASK_READ; der Pfad wird nicht von {@code /api/v1/tasks/{id}} verschluckt.
 *
 * <p>Die Projektion wird per JDBC gesaet wie in {@code DivisionProjectionIT}. Mandanten: 561 (A,
 * Haupt-Mandant), 562 (B, fremd), 563 (C, nur fuer die vollstaendige Liste ohne {@code q}), 564 (D,
 * nur fuer die Vorgabe von {@code limit}); in A sucht jeder Test mit eigenem Suchbegriff oder per
 * {@code ids}, weil sich die Methoden die Datenbank teilen. Kein Name in A ausser den Lockvoegeln
 * enthaelt {@code %}, {@code _} oder {@code [}.
 *
 * <p>Angenommene Signaturen: {@code DivisionCandidateController} (Paket {@code division}, {@code
 * GET /api/v1/tasks/divisions} mit {@code q}, {@code ids}, {@code limit}), {@code
 * DivisionCandidateService}; Antwort {@code {"items":[{"divisionId","name","memberCount"}]}}.
 * Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = DivisionCandidateControllerIT.ItConfig.class,
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
class DivisionCandidateControllerIT {

  private static final String TEST_KID = "division-candidate-it-key";
  private static final String URL = "/api/v1/tasks/divisions";

  private static final long TENANT_A_ID = 561L;
  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final long TENANT_B_ID = 562L;
  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final long TENANT_C_ID = 563L;
  private static final UUID TENANT_C_UUID = UUID.randomUUID();
  private static final long TENANT_D_ID = 564L;
  private static final UUID TENANT_D_UUID = UUID.randomUUID();

  /** Anfragende Person je Mandant (mit TASK_READ im Token). */
  private static final UUID ACTOR_A_REF = UUID.randomUUID();

  private static final UUID ACTOR_B_REF = UUID.randomUUID();
  private static final UUID ACTOR_C_REF = UUID.randomUUID();
  private static final UUID ACTOR_D_REF = UUID.randomUUID();

  /** Aktiver und inaktiver Benutzer in A, aktiver Benutzer in B (zaehlt in A nicht). */
  private static final UUID A_ACTIVE_REF = UUID.randomUUID();

  private static final UUID A_INACTIVE_REF = UUID.randomUUID();
  private static final UUID B_ACTIVE_REF = UUID.randomUUID();

  /** Aktive Benutzer in C fuer die Zaehlung der vollstaendigen Liste. */
  private static final UUID C_USER_1_REF = UUID.randomUUID();

  private static final UUID C_USER_2_REF = UUID.randomUUID();

  /** Benutzer in A ohne task-management-Recht (nur TASK_WRITE, kein TASK_READ). */
  private static final UUID NO_READ_REF = UUID.randomUUID();

  /** IDs der per JDBC gesaeten Abteilungs- und Mitgliederzeilen, weit weg von der KSF-Sequenz. */
  private static final AtomicLong JDBC_DIVISION_IDS = new AtomicLong(8_400_000_000L);

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
      pemTempFile = Files.createTempFile("division-candidate-it-jwk-", ".pem");
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

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    seedFirmaIfAbsent(jt, TENANT_C_ID, TENANT_C_UUID);
    seedFirmaIfAbsent(jt, TENANT_D_ID, TENANT_D_UUID);
    seedBenutzerIfAbsent(jt, 5611L, ACTOR_A_REF, "actor-a-division@brasi.de", TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5612L, A_ACTIVE_REF, "active-a-division@brasi.de", TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5613L, A_INACTIVE_REF, "inactive-a-division@brasi.de", TENANT_A_ID);
    jt.update("UPDATE [dbo].[PROD_BENUTZER] SET ENTITY_STATUS = 'INACTIVE' WHERE ID = ?", 5613L);
    seedBenutzerIfAbsent(jt, 5614L, NO_READ_REF, "no-read-division@brasi.de", TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5621L, ACTOR_B_REF, "actor-b-division@brasi.de", TENANT_B_ID);
    seedBenutzerIfAbsent(jt, 5622L, B_ACTIVE_REF, "active-b-division@brasi.de", TENANT_B_ID);
    seedBenutzerIfAbsent(jt, 5631L, ACTOR_C_REF, "actor-c-division@brasi.de", TENANT_C_ID);
    seedBenutzerIfAbsent(jt, 5632L, C_USER_1_REF, "user1-c-division@brasi.de", TENANT_C_ID);
    seedBenutzerIfAbsent(jt, 5633L, C_USER_2_REF, "user2-c-division@brasi.de", TENANT_C_ID);
    seedBenutzerIfAbsent(jt, 5641L, ACTOR_D_REF, "actor-d-division@brasi.de", TENANT_D_ID);
  }

  @Test
  @DisplayName(
      "ohne q: alle aktiven Abteilungen des Mandanten mit Mitgliederzahl, nach Name und dann UUID"
          + " sortiert, unbenannte zuletzt; der Grabstein (DEACTIVE) fehlt")
  void i190_lists_active_divisions_with_counts() throws Exception {
    // UUIDs unterscheiden sich nur im letzten Block: so stimmen die Sortierung von SQL Server
    // (uniqueidentifier vergleicht den letzten Block zuerst) und die erwartete Reihenfolge
    // ueberein.
    final UUID alpha = cRef(1);
    final UUID beta = cRef(2);
    final UUID gamma1 = cRef(3);
    final UUID gamma2 = cRef(4);
    final UUID unnamed1 = cRef(5);
    final UUID unnamed2 = cRef(6);
    final UUID tombstone = cRef(7);
    insertDivision(TENANT_C_ID, unnamed2, "ACTIVE", null, C_USER_2_REF);
    insertDivision(TENANT_C_ID, gamma2, "ACTIVE", "Gamma");
    insertDivision(TENANT_C_ID, alpha, "ACTIVE", "Alpha", C_USER_1_REF, C_USER_2_REF);
    insertDivision(TENANT_C_ID, tombstone, "DEACTIVE", "Aaa Grabstein");
    insertDivision(TENANT_C_ID, unnamed1, "ACTIVE", null);
    insertDivision(TENANT_C_ID, gamma1, "ACTIVE", "Gamma", C_USER_1_REF);
    insertDivision(TENANT_C_ID, beta, "ACTIVE", "Beta");

    final List<Item> items = items(readToken(TENANT_C_UUID, ACTOR_C_REF), get(URL));

    assertThat(items)
        .containsExactly(
            new Item(alpha, "Alpha", 2),
            new Item(beta, "Beta", 0),
            new Item(gamma1, "Gamma", 1),
            new Item(gamma2, "Gamma", 0),
            new Item(unnamed1, null, 0),
            new Item(unnamed2, null, 1));
  }

  @Test
  @DisplayName(
      "memberCount zaehlt nur Mitglieder mit aktivem Benutzer im selben Mandanten: aktiv ja, ohne"
          + " Benutzer, inaktiv und nur in Mandant B nein -> 1 (ueber q und ueber ids)")
  void i190_member_count_counts_only_active_users() throws Exception {
    final UUID division = UUID.randomUUID();
    insertDivision(
        TENANT_A_ID,
        division,
        "ACTIVE",
        "Zaehlung",
        A_ACTIVE_REF,
        UUID.randomUUID(),
        A_INACTIVE_REF,
        B_ACTIVE_REF);
    final String token = readToken(TENANT_A_UUID, ACTOR_A_REF);

    assertThat(items(token, get(URL).queryParam("q", "zaehlung")))
        .containsExactly(new Item(division, "Zaehlung", 1));
    assertThat(items(token, get(URL).queryParam("ids", division.toString())))
        .containsExactly(new Item(division, "Zaehlung", 1));
  }

  @Test
  @DisplayName(
      "q filtert woertlich ueber den Namen, ohne Ruecksicht auf Gross-/Kleinschreibung und mit"
          + " Leerzeichen aussen; unbenannte Abteilungen fallen bei gesetztem q heraus")
  void i190_q_filters_by_name_and_skips_unnamed() throws Exception {
    final UUID einkauf = UUID.randomUUID();
    final UUID verkauf = UUID.randomUUID();
    final UUID other = UUID.randomUUID();
    final UUID unnamed = UUID.randomUUID();
    insertDivision(TENANT_A_ID, einkauf, "ACTIVE", "Qfilter Einkauf", A_ACTIVE_REF);
    insertDivision(TENANT_A_ID, verkauf, "ACTIVE", "Qfilter Verkauf");
    insertDivision(TENANT_A_ID, other, "ACTIVE", "Anderes Team");
    insertDivision(TENANT_A_ID, unnamed, "ACTIVE", null);
    final String token = readToken(TENANT_A_UUID, ACTOR_A_REF);

    assertThat(items(token, get(URL).queryParam("q", "  QFILTER ")))
        .containsExactly(
            new Item(einkauf, "Qfilter Einkauf", 1), new Item(verkauf, "Qfilter Verkauf", 0));
    assertThat(items(token, get(URL).queryParam("q", "filter verk")))
        .containsExactly(new Item(verkauf, "Qfilter Verkauf", 0));
    assertThat(items(token, get(URL).queryParam("q", "qfilter")))
        .extracting(Item::divisionId)
        .doesNotContain(other, unnamed);
    // Gegenprobe: die unbenannte Abteilung ist sichtbar, nur nicht ueber q.
    assertThat(items(token, get(URL).queryParam("ids", unnamed.toString())))
        .containsExactly(new Item(unnamed, null, 0));
  }

  @Test
  @DisplayName(
      "ids liefert die Vorschau nur fuer bekannte aktive Abteilungen des Mandanten: unbekannte,"
          + " Grabstein und mandantenfremde fehlen ohne Fehler")
  void i190_ids_preview_omits_unknown() throws Exception {
    final UUID known = UUID.randomUUID();
    final UUID tombstone = UUID.randomUUID();
    final UUID foreign = UUID.randomUUID();
    insertDivision(TENANT_A_ID, known, "ACTIVE", "Vorschau", A_ACTIVE_REF);
    insertDivision(TENANT_A_ID, tombstone, "DEACTIVE", "Grabstein Vorschau");
    insertDivision(TENANT_B_ID, foreign, "ACTIVE", "Fremde Vorschau", B_ACTIVE_REF);

    final List<Item> items =
        items(
            readToken(TENANT_A_UUID, ACTOR_A_REF),
            get(URL)
                .queryParam(
                    "ids",
                    known.toString(),
                    UUID.randomUUID().toString(),
                    tombstone.toString(),
                    foreign.toString()));

    assertThat(items).containsExactly(new Item(known, "Vorschau", 1));
  }

  @Test
  @DisplayName(
      "ids gewinnt vor q und limit: zwei bekannte ids, ein q ohne Treffer und limit=1 -> beide"
          + " Abteilungen; ohne ids filtert q und kappt limit (Gegenproben)")
  void i190_ids_win_over_q_and_limit() throws Exception {
    final UUID alpha = UUID.randomUUID();
    final UUID beta = UUID.randomUUID();
    insertDivision(TENANT_A_ID, alpha, "ACTIVE", "Vorrang Alpha", A_ACTIVE_REF);
    insertDivision(TENANT_A_ID, beta, "ACTIVE", "Vorrang Beta");
    final String token = readToken(TENANT_A_UUID, ACTOR_A_REF);

    assertThat(
            items(
                token,
                get(URL)
                    .queryParam("ids", alpha.toString(), beta.toString())
                    .queryParam("q", "zzz-trifft-keinen-namen")
                    .queryParam("limit", "1")))
        .as("q filtert die Vorschau nicht, limit kappt sie nicht")
        .containsExactly(new Item(alpha, "Vorrang Alpha", 1), new Item(beta, "Vorrang Beta", 0));

    // Gegenproben: ohne ids wirken q und limit auf dieselben Abteilungen.
    assertThat(items(token, get(URL).queryParam("q", "zzz-trifft-keinen-namen"))).isEmpty();
    assertThat(items(token, get(URL).queryParam("q", "vorrang").queryParam("limit", "1")))
        .extracting(Item::divisionId)
        .containsExactly(alpha);
  }

  @Test
  @DisplayName(
      "Mandantentrennung: B sieht die Abteilung von A weder ueber q noch ueber ids, und umgekehrt")
  void i190_tenant_isolation() throws Exception {
    final UUID inA = UUID.randomUUID();
    final UUID inB = UUID.randomUUID();
    insertDivision(TENANT_A_ID, inA, "ACTIVE", "Mandantentrennung A", A_ACTIVE_REF);
    insertDivision(TENANT_B_ID, inB, "ACTIVE", "Mandantentrennung B", B_ACTIVE_REF);
    final String tokenA = readToken(TENANT_A_UUID, ACTOR_A_REF);
    final String tokenB = readToken(TENANT_B_UUID, ACTOR_B_REF);

    assertThat(items(tokenB, get(URL).queryParam("q", "mandantentrennung")))
        .containsExactly(new Item(inB, "Mandantentrennung B", 1));
    assertThat(items(tokenA, get(URL).queryParam("q", "mandantentrennung")))
        .containsExactly(new Item(inA, "Mandantentrennung A", 1));
    assertThat(items(tokenB, get(URL).queryParam("ids", inA.toString()))).isEmpty();
    assertThat(items(tokenA, get(URL).queryParam("ids", inB.toString()))).isEmpty();
  }

  @Test
  @DisplayName(
      "limit 0 oder 101 und mehr als 50 ids -> 400 im KSF-Format; limit 1 kappt die Treffer")
  void i190_limit_out_of_range_400() throws Exception {
    insertDivision(TENANT_A_ID, UUID.randomUUID(), "ACTIVE", "Qlimit A");
    insertDivision(TENANT_A_ID, UUID.randomUUID(), "ACTIVE", "Qlimit B");
    final String token = readToken(TENANT_A_UUID, ACTOR_A_REF);
    final MockMvc mvc = buildMvc();

    for (final String limit : List.of("0", "101")) {
      mvc.perform(get(URL).queryParam("limit", limit).header("Authorization", bearer(token)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"));
    }
    final List<String> tooMany = new ArrayList<>();
    for (int i = 0; i < 51; i++) {
      tooMany.add(UUID.randomUUID().toString());
    }
    mvc.perform(
            get(URL)
                .queryParam("ids", tooMany.toArray(String[]::new))
                .header("Authorization", bearer(token)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"));

    assertThat(items(token, get(URL).queryParam("q", "qlimit").queryParam("limit", "1")))
        .extracting(Item::name)
        .containsExactly("Qlimit A");
  }

  @Test
  @DisplayName(
      "anonym 401, ohne TASK_READ 403; mit TASK_READ 200 - der Pfad wird nicht als {id} der"
          + " Aufgaben-Route gelesen")
  void i190_without_task_read_403() throws Exception {
    final MockMvc mvc = buildMvc();

    mvc.perform(get(URL)).andExpect(status().isUnauthorized());

    final String tokenNoRead =
        buildJwt(
            TENANT_A_UUID, NO_READ_REF, "no-read-division@brasi.de", "task-management:TASK_WRITE");
    mvc.perform(get(URL).header("Authorization", bearer(tokenNoRead)))
        .andExpect(status().isForbidden());

    mvc.perform(get(URL).header("Authorization", bearer(readToken(TENANT_A_UUID, ACTOR_A_REF))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
  }

  @Test
  @DisplayName(
      "Sonderzeichen in q werden woertlich gesucht: % findet nur Namen mit %, _ nur Namen mit _,"
          + " [ nur Namen mit [; die Lockvoegel sind ueber eigene Begriffe auffindbar")
  void i190_like_wildcards_escaped() throws Exception {
    final UUID percent = UUID.randomUUID();
    final UUID percentDecoy = UUID.randomUUID();
    final UUID underscore = UUID.randomUUID();
    final UUID underscoreDecoy = UUID.randomUUID();
    final UUID bracket = UUID.randomUUID();
    insertDivision(TENANT_A_ID, percent, "ACTIVE", "Rabatt 50% Team");
    insertDivision(TENANT_A_ID, percentDecoy, "ACTIVE", "Rabatt 500 Team");
    insertDivision(TENANT_A_ID, underscore, "ACTIVE", "lager_a");
    insertDivision(TENANT_A_ID, underscoreDecoy, "ACTIVE", "lagerXa");
    insertDivision(TENANT_A_ID, bracket, "ACTIVE", "[neu] Gruppe");
    final String token = readToken(TENANT_A_UUID, ACTOR_A_REF);

    assertThat(items(token, get(URL).queryParam("q", "%")))
        .extracting(Item::divisionId)
        .containsExactly(percent);
    assertThat(items(token, get(URL).queryParam("q", "50%")))
        .extracting(Item::divisionId)
        .containsExactly(percent);
    assertThat(items(token, get(URL).queryParam("q", "_")))
        .extracting(Item::divisionId)
        .containsExactly(underscore);
    assertThat(items(token, get(URL).queryParam("q", "[neu]")))
        .extracting(Item::divisionId)
        .containsExactly(bracket);
    // Gegenprobe: die Lockvoegel existieren und sind ueber ihren eigenen Namen auffindbar.
    assertThat(items(token, get(URL).queryParam("q", "rabatt 500")))
        .extracting(Item::divisionId)
        .containsExactly(percentDecoy);
    assertThat(items(token, get(URL).queryParam("q", "lagerxa")))
        .extracting(Item::divisionId)
        .containsExactly(underscoreDecoy);
  }

  @Test
  @DisplayName("ohne limit gilt die Vorgabe 20: von 21 Abteilungen kommen die ersten 20 nach Name")
  void i190_default_limit_is_20() throws Exception {
    for (int i = 1; i <= 21; i++) {
      insertDivision(TENANT_D_ID, UUID.randomUUID(), "ACTIVE", String.format("Standard %02d", i));
    }

    final List<Item> items = items(readToken(TENANT_D_UUID, ACTOR_D_REF), get(URL));

    assertThat(items).hasSize(20);
    assertThat(items.get(0).name()).isEqualTo("Standard 01");
    assertThat(items.get(19).name()).isEqualTo("Standard 20");
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  /** Ein Eintrag der Antwort. */
  private record Item(UUID divisionId, String name, int memberCount) {}

  /** Fuehrt die Anfrage mit Token aus, erwartet 200 und liefert die Eintraege in Reihenfolge. */
  private List<Item> items(final String token, final MockHttpServletRequestBuilder request)
      throws Exception {
    final String json =
        buildMvc()
            .perform(request.header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    final List<Item> items = new ArrayList<>();
    for (final JsonNode item : new ObjectMapper().readTree(json).get("items")) {
      final JsonNode name = item.get("name");
      items.add(
          new Item(
              UUID.fromString(item.get("divisionId").asText()),
              name == null || name.isNull() ? null : name.asText(),
              item.get("memberCount").asInt()));
    }
    return items;
  }

  /** UUID einer Abteilung in Mandant C, die sich nur im letzten Block unterscheidet. */
  private static UUID cRef(final int n) {
    return UUID.fromString(String.format("c5630000-0000-0000-0000-%012d", n));
  }

  /**
   * Saet eine Abteilung der lokalen Projektion per JDBC: Kopf mit Status und Name ({@code null} =
   * unbenannt) und je Person eine Mitgliederzeile (MITARBEITER_REF).
   */
  private void insertDivision(
      final long tenantId,
      final UUID divisionRef,
      final String status,
      final String name,
      final UUID... members) {
    final long divisionId = JDBC_DIVISION_IDS.incrementAndGet();
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_ABTEILUNG] (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM,"
            + " EXTERNALID, VERSION, TENANT_ID, ABTEILUNG_REF, NAME, REVISION, ABGERUFEN_AM)"
            + " VALUES (?, ?, 'division-candidate-it', SYSDATETIME(), NEWID(), 1, ?, ?, ?, ?,"
            + " SYSDATETIME())",
        divisionId,
        status,
        tenantId,
        divisionRef,
        new SqlParameterValue(Types.NVARCHAR, name),
        new SqlParameterValue(Types.CHAR, "DEACTIVE".equals(status) ? null : "c".repeat(64)));
    for (final UUID member : members) {
      jdbcTemplate.update(
          "INSERT INTO [dbo].[TASK_ABTEILUNG_MITGLIED] (ID, ENTITY_STATUS, ANGELEGT_VON,"
              + " ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, ABTEILUNG_ID, MITARBEITER_REF,"
              + " KERNMITGLIED) VALUES (?, 'ACTIVE', 'division-candidate-it', SYSDATETIME(),"
              + " NEWID(), 1, ?, ?, ?, 0)",
          JDBC_DIVISION_IDS.incrementAndGet(),
          tenantId,
          divisionId,
          member);
    }
  }

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private static String bearer(final String token) {
    return "Bearer " + token;
  }

  /** Token mit TASK_READ. */
  private static String readToken(final UUID tenantUuid, final UUID userRef) {
    return buildJwt(tenantUuid, userRef, "reader-" + userRef, "task-management:TASK_READ");
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

  // ---- IT-Konfigurations-Kontext --------------------------------------------------------------

  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @ComponentScan(
      // Paket task fuer den Routing-Beweis gegen GET /api/v1/tasks/{id}; das Paket division wird
      // nicht gescannt (Consumer, Client, Resync), die Vorschlags-Beans kommen per @Import.
      basePackages = {
        "de.brasi.nova.taskmanagement.resolver",
        "de.brasi.nova.taskmanagement.attachment",
        "de.brasi.nova.taskmanagement.task",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.comment",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.time",
      },
      excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class)
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
    DivisionMentionItWiring.class,
    DivisionCandidateController.class,
    DivisionCandidateService.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "division-candidate-it";
    }
  }
}
