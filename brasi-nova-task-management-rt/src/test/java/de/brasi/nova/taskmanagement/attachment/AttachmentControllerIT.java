package de.brasi.nova.taskmanagement.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig;
import de.brasi.nova.taskmanagement.push.NoOpNotificationPushConfig;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import io.jsonwebtoken.Jwts;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import io.keel.framework.security.KsfSecurityAutoConfiguration;
import io.keel.framework.security.web.KsfWebSecurityAutoConfiguration;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integrationstest fuer den {@link AttachmentController} gegen echtes MSSQL (Wegwerf-DB) und ein
 * eigenes Temp-Verzeichnis als Basispfad (Intake #152, Tasks A7 bis A9).
 *
 * <p>Mandanten: A 560 ohne Zeile in {@code TASK_ANHANG_GRENZE} (Tarif STANDARD, 5 MB), B 561 in
 * der Config-Liste {@code task-management.attachments.enterprise-tenant-ids} (ENTERPRISE, 15 MB), C
 * 562 mit aktiver Zeile EXPERT ohne eigene Obergrenze (10 MB) und einer DEACTIVE-Zeile ENTERPRISE,
 * die nicht wirken darf. Uploads per MockMvc {@code multipart(...)}; Zaehler (VERSION, Generation,
 * Dateien) werden als Differenz vorher/nachher geprueft, weil alle Methoden der Klasse dieselbe
 * Datenbank und denselben Basispfad teilen (Lesson {@code it-context-pitfalls}, Punkt 5).
 *
 * <p>Der Kontext tauscht den {@link AttachmentCommandService} gegen {@link
 * SeamFailingCommandService} (Primary): dessen Seam {@code onAfterAttachmentsStoredForTest} bricht
 * nur fuer die in {@link SeamFailingCommandService#FAIL_ON_TASK} genannte Aufgabe ab, sonst wirkt
 * er wie die Produktion.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = AttachmentControllerIT.ItConfig.class,
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
class AttachmentControllerIT {

  private static final String TEST_KID = "attachment-it-key";

  /** Mandant A: keine Tabellenzeile, Tarif STANDARD (5 MB). */
  private static final long TENANT_A_ID = 560L;

  /** Mandant B: in der ENTERPRISE-Liste der Konfiguration (15 MB). */
  private static final long TENANT_B_ID = 561L;

  /** Mandant C: Tabellenzeile EXPERT ohne MAX_DATEI_BYTES (10 MB), dazu eine DEACTIVE-Zeile. */
  private static final long TENANT_C_ID = 562L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID TENANT_C_UUID = UUID.randomUUID();

  /** Ersteller der Aufgaben in Mandant A (TASK_WRITE fuer Anlegen und Hinzufuegen). */
  private static final UUID CREATOR_REF = UUID.randomUUID();

  private static final String CREATOR_LOGIN = "creator-attachment@brasi.de";

  /** Beteiligte Person ohne Rechte (nur Mitgliedschaft): laedt hoch, liest, entfernt Eigenes. */
  private static final UUID MEMBER_REF = UUID.randomUUID();

  private static final String MEMBER_LOGIN = "member-attachment@brasi.de";

  /** Weitere beteiligte Person: weder Hochladende noch Erstellerin (Rollengrenze 403). */
  private static final UUID OTHER_REF = UUID.randomUUID();

  private static final String OTHER_LOGIN = "other-attachment@brasi.de";

  /** Ausgetretene Person (READ-Mitglied, kein WRITE-Mitglied). */
  private static final UUID EX_REF = UUID.randomUUID();

  private static final String EX_LOGIN = "ex-attachment@brasi.de";

  /** Nutzer des Mandanten A ohne Teilnehmerzeile. */
  private static final UUID NON_MEMBER_REF = UUID.randomUUID();

  private static final String NON_MEMBER_LOGIN = "non-member-attachment@brasi.de";

  private static final UUID CREATOR_B_REF = UUID.randomUUID();
  private static final String CREATOR_B_LOGIN = "creator-b-attachment@brasi.de";
  private static final UUID CREATOR_C_REF = UUID.randomUUID();
  private static final String CREATOR_C_LOGIN = "creator-c-attachment@brasi.de";

  private static final long MIB = 1_048_576L;

  /** Echtes einseitiges PDF (PDFBox): vorschaufaehig, die Vorschau entsteht beim Hochladen. */
  private static final byte[] PDF_BYTES = realPdf();

  private static final byte[] TXT_BYTES =
      "Notiz zur Aufgabe\r\nZeile 2".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PNG_BYTES = png();
  private static final byte[] MZ_BYTES = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};

  /** IDs der per JDBC gesaeten Anhang-Zeilen, weit weg von der KSF-Sequenz. */
  private static final AtomicLong JDBC_ATTACHMENT_IDS = new AtomicLong(8_000_000_000L);

  /** Feste UUIDs der per JDBC gesaeten Kommentar-Anhaenge (je Test eine eigene). */
  private static final UUID COMMENT_ATTACHMENT_IN_LIST =
      UUID.fromString("e1520000-0000-4000-8000-000000000001");

  private static final UUID COMMENT_ATTACHMENT_SERVED =
      UUID.fromString("e1520000-0000-4000-8000-000000000002");
  private static final UUID COMMENT_ATTACHMENT_IN_DETAIL =
      UUID.fromString("e1520000-0000-4000-8000-000000000003");

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

  /** Basispfad der Anhaenge (neues Temp-Verzeichnis je Klasse). */
  private static Path baseDir;

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
      pemTempFile = Files.createTempFile("attachment-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
      baseDir = Files.createTempDirectory("attachment-it-store-");
    } catch (IOException e) {
      throw new UncheckedIOException("Konnte temporaere Dateien nicht anlegen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem", () -> "file:" + pemTempFile.toAbsolutePath());
    registry.add(
        "task-management.attachments.storage.base-dir", () -> baseDir.toAbsolutePath().toString());
    registry.add(
        "task-management.attachments.enterprise-tenant-ids", () -> String.valueOf(TENANT_B_ID));
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
      deleteQuietly(pemTempFile);
      deleteTreeQuietly(baseDir);
    }
  }

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AttachmentContentProvider contentProvider;

  @BeforeAll
  static void seedPrerequisites(@Autowired final JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    seedFirmaIfAbsent(jt, TENANT_C_ID, TENANT_C_UUID);
    seedBenutzerIfAbsent(jt, 5601L, CREATOR_REF, CREATOR_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5602L, MEMBER_REF, MEMBER_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5603L, OTHER_REF, OTHER_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5604L, EX_REF, EX_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5605L, NON_MEMBER_REF, NON_MEMBER_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5611L, CREATOR_B_REF, CREATOR_B_LOGIN, TENANT_B_ID);
    seedBenutzerIfAbsent(jt, 5621L, CREATOR_C_REF, CREATOR_C_LOGIN, TENANT_C_ID);

    // Mandant C: die DEACTIVE-Zeile (ENTERPRISE, 15 MB) liegt vor der aktiven und darf nie wirken
    // (Diskriminator fuer TaskAttachmentLimitQueryRepository#findActiveForTenant).
    jt.update(
        "INSERT INTO [dbo].[TASK_ANHANG_GRENZE]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TARIF, MAX_DATEI_BYTES)"
            + " VALUES (9001, 'DEACTIVE', 'attachment-it', SYSDATETIME(), NEWID(), 1, ?,"
            + " 'ENTERPRISE', ?)",
        TENANT_C_ID,
        15 * MIB);
    jt.update(
        "INSERT INTO [dbo].[TASK_ANHANG_GRENZE]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TARIF, MAX_DATEI_BYTES)"
            + " VALUES (9002, 'ACTIVE', 'attachment-it', SYSDATETIME(), NEWID(), 1, ?,"
            + " 'EXPERT', NULL)",
        TENANT_C_ID);
  }

  // ---- A7: Hochladen ------------------------------------------------------------------------

  @Test
  @DisplayName(
      "i152_upload_two_files_201_files_activity_history_bump: PDF und TXT als WRITE-Mitglied ->"
          + " 201, Zeilen mit Schluessel, Dateien mit Pruefsumme, Vorschau, VERSION +1,"
          + " LAST_ACTIVITY_AT, Historie je Datei, Bump fuer Leser und Akteur")
  void i152_upload_two_files_201_files_activity_history_bump() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Upload");
    addParticipants(mvc, creator, taskId, MEMBER_REF);

    final int versionBefore = taskVersion(taskId);
    final Timestamp lastActivityBefore = lastActivity(taskId);
    final long memberGenerationBefore = generation(TENANT_A_ID, MEMBER_REF);
    final long creatorGenerationBefore = generation(TENANT_A_ID, CREATOR_REF);

    final ResultActions res =
        upload(mvc, creator, taskId, pdfFile("Bericht.pdf"), file("Notiz.txt", TXT_BYTES));

    res.andExpect(status().isCreated())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].fileName").value("Bericht.pdf"))
        .andExpect(jsonPath("$[0].mimeType").value("application/pdf"))
        .andExpect(jsonPath("$[0].sizeBytes").value(PDF_BYTES.length))
        .andExpect(jsonPath("$[0].hasThumbnail").value(true))
        .andExpect(jsonPath("$[0].uploadedById").value(CREATOR_REF.toString()))
        .andExpect(jsonPath("$[0].removable").value(true))
        .andExpect(jsonPath("$[1].fileName").value("Notiz.txt"))
        .andExpect(jsonPath("$[1].mimeType").value("text/plain"))
        .andExpect(jsonPath("$[1].hasThumbnail").value(false));
    final JsonNode body = json(res);
    final UUID id0 = UUID.fromString(body.get(0).get("id").asText());
    final UUID id1 = UUID.fromString(body.get(1).get("id").asText());
    res.andExpect(
            jsonPath("$[0].links.content")
                .value(endsWith("/api/v1/tasks/" + taskId + "/attachments/" + id0 + "/content")))
        .andExpect(
            jsonPath("$[0].links.download")
                .value(endsWith("/api/v1/tasks/" + taskId + "/attachments/" + id0 + "/download")))
        .andExpect(
            jsonPath("$[0].links.thumbnail")
                .value(
                    endsWith("/api/v1/tasks/" + taskId + "/attachments/" + id0 + "/thumbnail")));
    assertThat(body.get(0).get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z");
    final JsonNode thumbnail1 = body.get(1).path("links").path("thumbnail");
    assertThat(thumbnail1.isNull() || thumbnail1.isMissingNode())
        .as("TXT ist nicht vorschaufaehig: links.thumbnail ist null: %s", body.get(1))
        .isTrue();

    final List<AttachmentRow> rows = activeTaskRows(taskId);
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .allSatisfy(
            r -> assertThat(r.storageKey()).matches("560/\\d+/\\d+\\.(pdf|txt)"));
    final AttachmentRow row0 = rowByUuid(rows, id0);
    final AttachmentRow row1 = rowByUuid(rows, id1);
    assertThat(row0.storageKey()).endsWith("/" + row0.id() + ".pdf");
    assertThat(sha256(baseDir.resolve(row0.storageKey()))).isEqualTo(row0.sha256());
    assertThat(sha256(baseDir.resolve(row1.storageKey()))).isEqualTo(row1.sha256());
    assertThat(baseDir.resolve(row0.storageKey())).hasBinaryContent(PDF_BYTES);
    assertThat(baseDir.resolve(AttachmentStorageKeys.thumbnailKey(row0.storageKey()))).exists();
    assertThat(row0.thumbnailPresent()).isTrue();
    assertThat(row1.thumbnailPresent()).isFalse();
    assertThat(baseDir.resolve(AttachmentStorageKeys.thumbnailKey(row1.storageKey())))
        .doesNotExist();
    assertThat(row0.uploadedByRef()).isEqualTo(CREATOR_REF);

    assertThat(taskVersion(taskId) - versionBefore).isEqualTo(1);
    assertThat(lastActivity(taskId)).isAfter(lastActivityBefore);
    assertThat(historyDetails(taskId, "Datei angehaengt"))
        .containsExactlyInAnyOrder("Bericht.pdf", "Notiz.txt");
    assertThat(generation(TENANT_A_ID, MEMBER_REF) - memberGenerationBefore).isEqualTo(1);
    assertThat(generation(TENANT_A_ID, CREATOR_REF) - creatorGenerationBefore).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i152_upload_created_matches_openapi_example: Felder der Live-Antwort (201) gleich den"
          + " Feldern des OpenAPI-Beispiels AttachmentApiExamples.UPLOAD_CREATED")
  void i152_upload_created_matches_openapi_example() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Beispiel");

    final JsonNode live =
        json(upload(mvc, creator, taskId, pdfFile("Bericht.pdf")).andExpect(status().isCreated()));
    final JsonNode example = new ObjectMapper().readTree(AttachmentApiExamples.UPLOAD_CREATED);

    assertThat(example.isArray()).as("Beispiel ist eine Liste: %s", example).isTrue();
    assertThat(fieldNames(live.get(0))).isEqualTo(fieldNames(example.get(0)));
    assertThat(fieldNames(live.get(0).get("links")))
        .isEqualTo(fieldNames(example.get(0).get("links")));
  }

  @Test
  @DisplayName(
      "i152_non_member_404_nothing_stored: Nutzer des Mandanten ohne Teilnehmerzeile -> 404"
          + " NOVA_NOT_FOUND, keine Zeile, keine Datei")
  void i152_non_member_404_nothing_stored() throws Exception {
    final MockMvc mvc = buildMvc();
    final UUID taskId = createTask(mvc, creatorToken(), "Anhang-IT Nicht-Mitglied");
    final Set<Path> filesBefore = filesUnder(baseDir.resolve("560"));
    final int versionBefore = taskVersion(taskId);

    upload(mvc, token(NON_MEMBER_REF, NON_MEMBER_LOGIN), taskId, pdfFile("Bericht.pdf"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));

    assertThat(attachmentRowCount(taskId)).isZero();
    assertThat(filesUnder(baseDir.resolve("560"))).isEqualTo(filesBefore);
    assertThat(taskVersion(taskId)).isEqualTo(versionBefore);
  }

  @Test
  @DisplayName(
      "i152_non_member_with_invalid_file_gets_404_not_400: die Mitgliedschaft wird vor jeder"
          + " Dateipruefung geprueft (kein Existenz-Leck ueber 400)")
  void i152_non_member_with_invalid_file_gets_404_not_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final UUID taskId = createTask(mvc, creatorToken(), "Anhang-IT Nicht-Mitglied ungueltig");

    upload(mvc, token(NON_MEMBER_REF, NON_MEMBER_LOGIN), taskId, file("x.exe", MZ_BYTES))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
    // Unbekannte Aufgabe: dieselbe neutrale 404
    upload(mvc, creatorToken(), UUID.randomUUID(), file("x.exe", MZ_BYTES))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
  }

  @Test
  @DisplayName(
      "i152_ex_member_upload_404: ausgetretene Person (READ-, nicht WRITE-Mitglied) -> 404,"
          + " nichts gespeichert")
  void i152_ex_member_upload_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Ausgetreten");
    addParticipants(mvc, creator, taskId, EX_REF);
    exitParticipant(taskId, EX_REF);

    upload(mvc, token(EX_REF, EX_LOGIN), taskId, pdfFile("Bericht.pdf"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
    assertThat(attachmentRowCount(taskId)).isZero();
  }

  @Test
  @DisplayName(
      "i152_invalid_second_file_400_nothing_stored: alles oder nichts - PDF und EXE -> 400,"
          + " VERSION unveraendert, keine Zeile, keine Datei")
  void i152_invalid_second_file_400_nothing_stored() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT alles oder nichts");
    final Set<Path> filesBefore = filesUnder(baseDir.resolve("560"));
    final int versionBefore = taskVersion(taskId);

    upload(mvc, creator, taskId, pdfFile("Bericht.pdf"), file("Programm.exe", MZ_BYTES))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value(containsString("„.exe“")));

    assertThat(taskVersion(taskId)).isEqualTo(versionBefore);
    assertThat(attachmentRowCount(taskId)).isZero();
    assertThat(filesUnder(baseDir.resolve("560"))).isEqualTo(filesBefore);
  }

  @Test
  @DisplayName(
      "i152_standard_limit_400_validation_key: Mandant A ohne Tabellenzeile, PDF mit 5 MiB + 1"
          + " Byte -> 400 NOVA_VALIDATION_FAILED, Text nennt 5 MB und STANDARD")
  void i152_standard_limit_400_validation_key() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT STANDARD");

    upload(mvc, creator, taskId, file("gross.pdf", paddedPdf(5 * MIB + 1)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.message").value(allOf(containsString("5 MB"), containsString("STANDARD"))));
    assertThat(attachmentRowCount(taskId)).isZero();

    // Genau an der Grenze: angenommen
    upload(mvc, creator, taskId, file("genau.txt", textOfSize(5 * MIB)))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName(
      "i152_config_enterprise_tenant_accepts_12mb: Mandant B in der ENTERPRISE-Liste der"
          + " Konfiguration -> 12 MB werden angenommen (201)")
  void i152_config_enterprise_tenant_accepts_12mb() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creatorB =
        buildJwt(
            TENANT_B_UUID,
            CREATOR_B_REF,
            CREATOR_B_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskId = createTask(mvc, creatorB, "Anhang-IT ENTERPRISE");

    upload(mvc, creatorB, taskId, file("gross.txt", textOfSize(12 * MIB)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$[0].sizeBytes").value(12 * MIB));

    final List<AttachmentRow> rows = activeTaskRows(taskId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).storageKey()).startsWith("561/");
    assertThat(Files.size(baseDir.resolve(rows.get(0).storageKey()))).isEqualTo(12 * MIB);
  }

  @Test
  @DisplayName(
      "i152_table_expert_rejects_11mb_accepts_9mb: Mandant C mit Tabellenzeile EXPERT ohne Max"
          + " (die DEACTIVE-Zeile ENTERPRISE wirkt nicht) -> 11 MB 400, 9 MB 201")
  void i152_table_expert_rejects_11mb_accepts_9mb() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creatorC =
        buildJwt(
            TENANT_C_UUID,
            CREATOR_C_REF,
            CREATOR_C_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskId = createTask(mvc, creatorC, "Anhang-IT EXPERT");

    upload(mvc, creatorC, taskId, file("elf.txt", textOfSize(11 * MIB)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.message").value(allOf(containsString("10 MB"), containsString("EXPERT"))));
    assertThat(attachmentRowCount(taskId)).isZero();

    upload(mvc, creatorC, taskId, file("neun.txt", textOfSize(9 * MIB)))
        .andExpect(status().isCreated());
    assertThat(activeTaskRows(taskId)).hasSize(1);
    assertThat(activeTaskRows(taskId).get(0).storageKey()).startsWith("562/");
  }

  @Test
  @DisplayName(
      "i152_magic_mismatch_400: PNG-Inhalt mit Endung .pdf -> 400 NOVA_VALIDATION_FAILED")
  void i152_magic_mismatch_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Magic");

    upload(mvc, creator, taskId, file("bild.pdf", PNG_BYTES))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.message")
                .value("Der Inhalt der Datei „bild.pdf“ passt nicht zum Dateityp PDF."));
    assertThat(attachmentRowCount(taskId)).isZero();
  }

  @Test
  @DisplayName(
      "i152_twenty_task_attachments_limit_ignores_comment_attachments: 19 aktive Aufgaben-Anhaenge,"
          + " 5 Kommentar-Anhaenge, 3 DEACTIVE -> 1 Datei 201, danach 1 Datei 400")
  void i152_twenty_task_attachments_limit_ignores_comment_attachments() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Hoechstzahl");
    final long taskDbId = taskDbId(taskId);
    final long commentDbId = commentDbId(postComment(mvc, creator, taskId, "Mit Anhaengen"));

    for (int i = 0; i < 19; i++) {
      insertAttachmentRow(taskDbId, null, "ACTIVE", UUID.randomUUID(), "aktiv-" + i + ".txt");
    }
    for (int i = 0; i < 5; i++) {
      insertAttachmentRow(
          taskDbId, commentDbId, "ACTIVE", UUID.randomUUID(), "kommentar-" + i + ".txt");
    }
    for (int i = 0; i < 3; i++) {
      insertAttachmentRow(taskDbId, null, "DEACTIVE", UUID.randomUUID(), "entfernt-" + i + ".txt");
    }

    upload(mvc, creator, taskId, file("zwanzig.txt", TXT_BYTES)).andExpect(status().isCreated());
    upload(mvc, creator, taskId, file("einundzwanzig.txt", TXT_BYTES))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.message")
                .value("Höchstens 20 aktive Anhänge je Aufgabe bzw. je Kommentar."));
    assertThat(activeTaskRows(taskId)).hasSize(20);
  }

  @Test
  @DisplayName(
      "i152_rollback_after_files_written_removes_files: Abbruch im Seam nach dem Schreiben ->"
          + " keine Zeile, nur die in dieser Transaktion geschriebenen Dateien sind weg, fremde"
          + " Dateien im Mandantenordner bleiben")
  void i152_rollback_after_files_written_removes_files() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Rollback");
    final Path tenantDir = baseDir.resolve("560");
    final Path foreignPdf = tenantDir.resolve("0").resolve("fremd-bleibt.pdf");
    final Path foreignTxt = tenantDir.resolve("fremd").resolve("fremd-bleibt.txt");
    Files.createDirectories(foreignPdf.getParent());
    Files.write(foreignPdf, PDF_BYTES);
    Files.createDirectories(foreignTxt.getParent());
    Files.write(foreignTxt, TXT_BYTES);
    final Set<Path> filesBefore = filesUnder(tenantDir);
    final int versionBefore = taskVersion(taskId);

    SeamFailingCommandService.FILES_AT_SEAM.set(null);
    SeamFailingCommandService.FAIL_ON_TASK.set(taskDbId(taskId));
    try {
      upload(mvc, creator, taskId, pdfFile("Bericht.pdf"), file("Notiz.txt", TXT_BYTES))
          .andExpect(status().isInternalServerError());
    } finally {
      SeamFailingCommandService.FAIL_ON_TASK.set(null);
    }

    // Positivkontrolle: am Seam lagen die Dateien dieser Transaktion schon auf der Platte.
    final Set<Path> atSeam = SeamFailingCommandService.FILES_AT_SEAM.get();
    assertThat(atSeam).as("Seam wurde nicht erreicht").isNotNull();
    final Set<Path> writtenInTransaction = new HashSet<>(atSeam);
    writtenInTransaction.removeAll(filesBefore);
    assertThat(writtenInTransaction)
        .extracting(p -> p.getFileName().toString())
        .anySatisfy(name -> assertThat(name).matches("\\d+\\.pdf"))
        .anySatisfy(name -> assertThat(name).matches("\\d+\\.txt"));

    assertThat(attachmentRowCount(taskId)).isZero();
    assertThat(filesUnder(tenantDir)).isEqualTo(filesBefore);
    assertThat(foreignPdf).hasBinaryContent(PDF_BYTES);
    assertThat(foreignTxt).hasBinaryContent(TXT_BYTES);
    assertThat(taskVersion(taskId)).isEqualTo(versionBefore);
  }

  // ---- A8: Lesen, Auslieferung, Vorschau, Detail ---------------------------------------------

  @Test
  @DisplayName(
      "i152_list_returns_task_attachments_only_chronological: GET /attachments -> nur die"
          + " Aufgaben-Anhaenge in Hochlade-Reihenfolge, kein Kommentar-Anhang")
  void i152_list_returns_task_attachments_only_chronological() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Liste");
    final UUID first = uploadOne(mvc, creator, taskId, file("a.txt", TXT_BYTES));
    final UUID second = uploadOne(mvc, creator, taskId, pdfFile("b.pdf"));
    final long commentDbId = commentDbId(postComment(mvc, creator, taskId, "Kommentar"));
    insertAttachmentRow(
        taskDbId(taskId), commentDbId, "ACTIVE", COMMENT_ATTACHMENT_IN_LIST, "kommentar.txt");

    mvc.perform(get(listUrl(taskId)).header("Authorization", "Bearer " + creator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(first.toString()))
        .andExpect(jsonPath("$[0].fileName").value("a.txt"))
        .andExpect(jsonPath("$[1].id").value(second.toString()))
        .andExpect(jsonPath("$[1].fileName").value("b.pdf"))
        .andExpect(
            jsonPath("$[1].links.content").value(endsWith(contentUrl(taskId, second, "content"))));
  }

  @Test
  @DisplayName(
      "i152_content_inline_headers: Inhalt mit Typ, Disposition inline (RFC 5987), nosniff, ETag,"
          + " Cache-Control private 1 Tag, Content-Length und unveraendertem Body; CR/LF im Namen"
          + " erzeugt keinen Header")
  void i152_content_inline_headers() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Inhalt");
    final String name = "Übersicht \"final\".pdf";
    final UUID attId = uploadOne(mvc, creator, taskId, file(name, PDF_BYTES));
    final AttachmentRow row = row(attId);
    assertThat(row.fileName()).isEqualTo(name);

    final MvcResult res = fetch(mvc, get(contentUrl(taskId, attId, "content")), creator);

    expect(
        res,
        status().isOk(),
        header().string("Content-Type", "application/pdf"),
        header()
            .string(
                "Content-Disposition",
                allOf(
                    startsWith("inline"),
                    containsString("filename*=UTF-8''%C3%9Cbersicht%20%22final%22.pdf"))),
        header().string("X-Content-Type-Options", "nosniff"),
        header().string("ETag", "W/\"" + row.sha256() + "\""),
        header()
            .string(
                "Cache-Control", allOf(containsString("private"), containsString("max-age=86400"))),
        header().longValue("Content-Length", PDF_BYTES.length));
    assertThat(res.getResponse().getContentAsByteArray()).isEqualTo(PDF_BYTES);

    // Steuerzeichen im Originalnamen: bereinigt gespeichert, kein zusaetzlicher Header
    final UUID injected =
        uploadOne(mvc, creator, taskId, file("Zeile\r\nX-Injected: 1.txt", TXT_BYTES));
    final MvcResult download = fetch(mvc, get(contentUrl(taskId, injected, "download")), creator);
    expect(
        download,
        status().isOk(),
        header().doesNotExist("X-Injected"),
        header().string("Content-Disposition", not(containsString("\n"))),
        header().string("Content-Disposition", not(containsString("\r"))));
    assertThat(row(injected).fileName()).doesNotContain("\r").doesNotContain("\n");
  }

  @Test
  @DisplayName(
      "i152_download_attachment_disposition: Download mit Disposition attachment und gleichem"
          + " Body")
  void i152_download_attachment_disposition() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Download");
    final UUID attId = uploadOne(mvc, creator, taskId, pdfFile("Bericht.pdf"));

    final MvcResult res = fetch(mvc, get(contentUrl(taskId, attId, "download")), creator);

    expect(
        res,
        status().isOk(),
        header().string("Content-Type", "application/pdf"),
        header()
            .string(
                "Content-Disposition",
                allOf(startsWith("attachment"), containsString("Bericht.pdf"))),
        header().string("X-Content-Type-Options", "nosniff"),
        header().string("ETag", "W/\"" + row(attId).sha256() + "\""),
        header().longValue("Content-Length", PDF_BYTES.length));
    assertThat(res.getResponse().getContentAsByteArray()).isEqualTo(PDF_BYTES);
  }

  @Test
  @DisplayName(
      "i152_if_none_match_304_without_body: passender If-None-Match -> 304 ohne Body, mit ETag"
          + " und Cache-Control")
  void i152_if_none_match_304_without_body() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT 304");
    final UUID attId = uploadOne(mvc, creator, taskId, pdfFile("Bericht.pdf"));
    final String etag = "W/\"" + row(attId).sha256() + "\"";

    final MvcResult res =
        fetch(
            mvc, get(contentUrl(taskId, attId, "content")).header("If-None-Match", etag), creator);

    expect(
        res,
        status().isNotModified(),
        header().string("ETag", etag),
        header().string("Cache-Control", containsString("private")));
    assertThat(res.getResponse().getContentAsByteArray()).isEmpty();

    // Vorschau: eigener ETag mit Breite
    final String thumbnailEtag = "W/\"" + row(attId).sha256() + "-t600\"";
    final MvcResult thumb =
        fetch(
            mvc,
            get(contentUrl(taskId, attId, "thumbnail")).header("If-None-Match", thumbnailEtag),
            creator);
    expect(thumb, status().isNotModified(), header().string("ETag", thumbnailEtag));
    assertThat(thumb.getResponse().getContentAsByteArray()).isEmpty();
  }

  @Test
  @DisplayName(
      "i152_non_member_404_even_with_matching_etag: Nicht-Mitglied bekommt auch mit passendem"
          + " ETag die neutrale 404 (Liste, Inhalt, Vorschau)")
  void i152_non_member_404_even_with_matching_etag() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT fremd ETag");
    final UUID attId = uploadOne(mvc, creator, taskId, pdfFile("Bericht.pdf"));
    final String etag = "W/\"" + row(attId).sha256() + "\"";
    final String nonMember = token(NON_MEMBER_REF, NON_MEMBER_LOGIN);

    for (final String kind : List.of("content", "download", "thumbnail")) {
      final MvcResult res =
          fetch(
              mvc, get(contentUrl(taskId, attId, kind)).header("If-None-Match", etag), nonMember);
      expect(res, status().isNotFound(), jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
    }
    mvc.perform(get(listUrl(taskId)).header("Authorization", "Bearer " + nonMember))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
  }

  @Test
  @DisplayName(
      "i152_ex_member_reads_list_content_thumbnail: ausgetretene Person liest Liste, Inhalt und"
          + " Vorschau (READ-Mitglied)")
  void i152_ex_member_reads_list_content_thumbnail() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Ausgetreten liest");
    final UUID attId = uploadOne(mvc, creator, taskId, pdfFile("Bericht.pdf"));
    addParticipants(mvc, creator, taskId, EX_REF);
    exitParticipant(taskId, EX_REF);
    final String ex = token(EX_REF, EX_LOGIN);

    mvc.perform(get(listUrl(taskId)).header("Authorization", "Bearer " + ex))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(attId.toString()))
        .andExpect(jsonPath("$[0].removable").value(false));
    final MvcResult content = fetch(mvc, get(contentUrl(taskId, attId, "content")), ex);
    expect(content, status().isOk());
    assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(PDF_BYTES);
    expect(
        fetch(mvc, get(contentUrl(taskId, attId, "thumbnail")), ex),
        status().isOk(),
        header().string("Content-Type", "image/jpeg"));
  }

  @Test
  @DisplayName(
      "i152_thumbnail_jpeg_cached_10_days: Vorschau als image/jpeg, Cache-Control private"
          + " 10 Tage, ETag mit Breite")
  void i152_thumbnail_jpeg_cached_10_days() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Vorschau");
    final UUID attId = uploadOne(mvc, creator, taskId, pdfFile("Bericht.pdf"));
    final AttachmentRow row = row(attId);

    final MvcResult res = fetch(mvc, get(contentUrl(taskId, attId, "thumbnail")), creator);

    expect(
        res,
        status().isOk(),
        header().string("Content-Type", "image/jpeg"),
        header()
            .string(
                "Cache-Control",
                allOf(containsString("private"), containsString("max-age=864000"))),
        header().string("ETag", "W/\"" + row.sha256() + "-t600\""),
        header().string("X-Content-Type-Options", "nosniff"));
    final byte[] jpeg = res.getResponse().getContentAsByteArray();
    assertThat(jpeg).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
    final Path thumb = baseDir.resolve(AttachmentStorageKeys.thumbnailKey(row.storageKey()));
    assertThat(jpeg).isEqualTo(Files.readAllBytes(thumb));
  }

  @Test
  @DisplayName(
      "i152_thumbnail_office_404: DOCX ist nicht vorschaufaehig -> hasThumbnail false, kein"
          + " Vorschau-Link, GET /thumbnail 404")
  void i152_thumbnail_office_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Office");

    final JsonNode body =
        json(
            upload(mvc, creator, taskId, file("Vertrag.docx", docx()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].hasThumbnail").value(false))
                .andExpect(
                    jsonPath("$[0].mimeType")
                        .value(
                            "application/vnd.openxmlformats-officedocument"
                                + ".wordprocessingml.document")));
    final UUID attId = UUID.fromString(body.get(0).get("id").asText());
    final JsonNode thumbnailLink = body.get(0).path("links").path("thumbnail");
    assertThat(thumbnailLink.isNull() || thumbnailLink.isMissingNode()).isTrue();

    expect(
        fetch(mvc, get(contentUrl(taskId, attId, "thumbnail")), creator),
        status().isNotFound(),
        jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
  }

  @Test
  @DisplayName(
      "i152_thumbnail_self_heals_when_missing: fehlt die Vorschau-Datei, erzeugt der Abruf sie"
          + " neu (200, Datei wieder da), ohne die Zeile zu aendern")
  void i152_thumbnail_self_heals_when_missing() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Selbstheilung");
    final UUID attId = uploadOne(mvc, creator, taskId, pdfFile("Bericht.pdf"));
    final AttachmentRow before = row(attId);
    final Path thumb = baseDir.resolve(AttachmentStorageKeys.thumbnailKey(before.storageKey()));
    assertThat(thumb).exists();
    Files.delete(thumb);

    final MvcResult res = fetch(mvc, get(contentUrl(taskId, attId, "thumbnail")), creator);

    expect(res, status().isOk(), header().string("Content-Type", "image/jpeg"));
    assertThat(res.getResponse().getContentAsByteArray()).isNotEmpty();
    assertThat(thumb).exists();
    final AttachmentRow after = row(attId);
    assertThat(after.version()).isEqualTo(before.version());
    assertThat(after.thumbnailPresent()).isEqualTo(before.thumbnailPresent());
  }

  @Test
  @DisplayName(
      "i152_missing_content_file_is_neutral_404: aktive Zeile, Datei fehlt auf der Platte ->"
          + " 404 NOVA_NOT_FOUND statt 500")
  void i152_missing_content_file_is_neutral_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Datei fehlt");
    final UUID attId = uploadOne(mvc, creator, taskId, file("Notiz.txt", TXT_BYTES));
    Files.delete(baseDir.resolve(row(attId).storageKey()));

    for (final String kind : List.of("content", "download")) {
      expect(
          fetch(mvc, get(contentUrl(taskId, attId, kind)), creator),
          status().isNotFound(),
          jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
    }
  }

  @Test
  @DisplayName(
      "i152_foreign_or_deactivated_attachment_404: Anhang einer anderen Aufgabe, DEACTIVE-Zeile"
          + " oder unbekannte UUID -> 404; keine UUID -> 400")
  void i152_foreign_or_deactivated_attachment_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT fremd 1");
    final UUID otherTaskId = createTask(mvc, creator, "Anhang-IT fremd 2");
    final UUID own = uploadOne(mvc, creator, taskId, pdfFile("eigen.pdf"));
    final UUID foreign = uploadOne(mvc, creator, otherTaskId, pdfFile("fremd.pdf"));

    expect(
        fetch(mvc, get(contentUrl(taskId, foreign, "content")), creator),
        status().isNotFound(),
        jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
    expect(
        fetch(mvc, get(contentUrl(taskId, UUID.randomUUID(), "content")), creator),
        status().isNotFound(),
        jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));

    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_ANHANG] SET ENTITY_STATUS = 'DEACTIVE' WHERE EXTERNALID = ?", own);
    for (final String kind : List.of("content", "download", "thumbnail")) {
      expect(
          fetch(mvc, get(contentUrl(taskId, own, kind)), creator),
          status().isNotFound(),
          jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
    }

    mvc.perform(
            get("/api/v1/tasks/{id}/attachments/kein-uuid/content", taskId)
                .header("Authorization", "Bearer " + creator))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName(
      "i152_comment_attachment_served_via_task_endpoint: ein Kommentar-Anhang wird ueber den"
          + " Aufgaben-Endpunkt ausgeliefert, steht aber nicht in der Liste")
  void i152_comment_attachment_served_via_task_endpoint() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Kommentar-Anhang");
    final long commentDbId = commentDbId(postComment(mvc, creator, taskId, "Mit Datei"));
    final byte[] content = "Anhang am Kommentar".getBytes(StandardCharsets.UTF_8);
    final long id = JDBC_ATTACHMENT_IDS.incrementAndGet();
    final String key = AttachmentStorageKeys.contentKey(TENANT_A_ID, id, "txt");
    contentProvider.writeContent(key, new ByteArrayResource(content));
    insertAttachmentRowWithId(
        id,
        taskDbId(taskId),
        commentDbId,
        COMMENT_ATTACHMENT_SERVED,
        "kommentar.txt",
        content,
        key);

    final MvcResult res =
        fetch(mvc, get(contentUrl(taskId, COMMENT_ATTACHMENT_SERVED, "content")), creator);
    expect(
        res,
        status().isOk(),
        header().string("Content-Type", containsString("text/plain")),
        header().string("ETag", "W/\"" + sha256Hex(content) + "\""));
    assertThat(res.getResponse().getContentAsByteArray()).isEqualTo(content);

    mvc.perform(get(listUrl(taskId)).header("Authorization", "Bearer " + creator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName(
      "i152_detail_contains_attachments_with_links_and_removable: Detail traegt attachments mit"
          + " Links (ohne Kommentar-Anhaenge); removable fuer die Erstellerin true, fuer ein"
          + " anderes Mitglied false")
  void i152_detail_contains_attachments_with_links_and_removable() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Anhang-IT Detail");
    addParticipants(mvc, creator, taskId, MEMBER_REF);
    final UUID attId = uploadOne(mvc, creator, taskId, pdfFile("Bericht.pdf"));
    final long commentDbId = commentDbId(postComment(mvc, creator, taskId, "Kommentar"));
    insertAttachmentRow(
        taskDbId(taskId), commentDbId, "ACTIVE", COMMENT_ATTACHMENT_IN_DETAIL, "kommentar.txt");

    mvc.perform(get("/api/v1/tasks/{id}", taskId).header("Authorization", "Bearer " + creator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attachments.length()").value(1))
        .andExpect(jsonPath("$.attachments[0].id").value(attId.toString()))
        .andExpect(jsonPath("$.attachments[0].fileName").value("Bericht.pdf"))
        .andExpect(
            jsonPath("$.attachments[0].links.download")
                .value(endsWith(contentUrl(taskId, attId, "download"))))
        .andExpect(jsonPath("$.attachments[0].removable").value(true));

    mvc.perform(
            get("/api/v1/tasks/{id}", taskId)
                .header("Authorization", "Bearer " + token(MEMBER_REF, MEMBER_LOGIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attachments.length()").value(1))
        .andExpect(jsonPath("$.attachments[0].removable").value(false));
  }

  // ---- Hilfen: Requests ----------------------------------------------------------------------

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private static String creatorToken() {
    return buildJwt(
        TENANT_A_UUID,
        CREATOR_REF,
        CREATOR_LOGIN,
        "task-management:TASK_WRITE",
        "task-management:TASK_READ");
  }

  /** Token eines Mandant-A-Nutzers ohne jedes task-management-Recht (nur Mitgliedschaft). */
  private static String token(final UUID userRef, final String login) {
    return buildJwt(TENANT_A_UUID, userRef, login);
  }

  private static ResultActions upload(
      final MockMvc mvc, final String token, final UUID taskId, final MockMultipartFile... files)
      throws Exception {
    final MockMultipartHttpServletRequestBuilder request =
        multipart("/api/v1/tasks/{id}/attachments", taskId);
    for (final MockMultipartFile file : files) {
      request.file(file);
    }
    return mvc.perform(request.header("Authorization", "Bearer " + token));
  }

  private static MockMultipartFile file(final String name, final byte[] content) {
    return new MockMultipartFile("files", name, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
  }

  private static MockMultipartFile pdfFile(final String name) {
    return file(name, PDF_BYTES);
  }

  private static UUID createTask(final MockMvc mvc, final String token, final String title)
      throws Exception {
    final String response =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(Map.of("title", title))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(new ObjectMapper().readTree(response).get("id").asText());
  }

  /** Nimmt Personen als reine Beteiligte auf (POST /participants, legt auch den State an). */
  private static void addParticipants(
      final MockMvc mvc, final String token, final UUID taskId, final UUID... refs)
      throws Exception {
    mvc.perform(
            post("/api/v1/tasks/{id}/participants", taskId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    new ObjectMapper()
                        .writeValueAsString(Map.of("participantIds", List.of(refs)))))
        .andExpect(status().is2xxSuccessful());
  }

  private static UUID postComment(
      final MockMvc mvc, final String token, final UUID taskId, final String text)
      throws Exception {
    final String response =
        mvc.perform(
                post("/api/v1/tasks/{id}/comments", taskId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(Map.of("text", text))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(new ObjectMapper().readTree(response).get("id").asText());
  }

  /** Laedt eine Datei hoch (201) und liefert die UUID des neuen Anhangs. */
  private static UUID uploadOne(
      final MockMvc mvc, final String token, final UUID taskId, final MockMultipartFile file)
      throws Exception {
    final JsonNode body = json(upload(mvc, token, taskId, file).andExpect(status().isCreated()));
    return UUID.fromString(body.get(0).get("id").asText());
  }

  private static String listUrl(final UUID taskId) {
    return "/api/v1/tasks/" + taskId + "/attachments";
  }

  /** {@code kind} = {@code content}, {@code download} oder {@code thumbnail}. */
  private static String contentUrl(final UUID taskId, final UUID attachmentId, final String kind) {
    return "/api/v1/tasks/" + taskId + "/attachments/" + attachmentId + "/" + kind;
  }

  /**
   * Ruft einen Binaer-Endpunkt ab. Die Antwort ist ein {@code StreamingResponseBody}: der erste
   * Durchlauf startet die asynchrone Verarbeitung, {@code asyncDispatch} wartet auf das Kopieren
   * und schliesst den Request ab. Ohne Async (304, Fehler) zaehlt der erste Durchlauf.
   */
  private static MvcResult fetch(
      final MockMvc mvc, final MockHttpServletRequestBuilder request, final String token)
      throws Exception {
    final MvcResult first =
        mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
    if (!first.getRequest().isAsyncStarted()) {
      return first;
    }
    return mvc.perform(asyncDispatch(first)).andReturn();
  }

  private static void expect(final MvcResult result, final ResultMatcher... matchers)
      throws Exception {
    for (final ResultMatcher matcher : matchers) {
      matcher.match(result);
    }
  }

  private static JsonNode json(final ResultActions res) throws Exception {
    return new ObjectMapper()
        .readTree(res.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static Set<String> fieldNames(final JsonNode node) {
    final Set<String> names = new java.util.TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  // ---- Hilfen: Datenbank ---------------------------------------------------------------------

  /** Eine Zeile aus {@code TASK_ANHANG}. */
  record AttachmentRow(
      long id,
      UUID uuid,
      String fileName,
      String storageKey,
      String sha256,
      boolean thumbnailPresent,
      UUID uploadedByRef,
      String entityStatus,
      Long commentId,
      int version) {}

  private static final String ATTACHMENT_COLUMNS =
      "a.ID, a.EXTERNALID, a.DATEINAME, a.SPEICHERSCHLUESSEL, a.SHA256, a.HAT_VORSCHAU,"
          + " a.HOCHGELADEN_VON_REF, a.ENTITY_STATUS, a.KOMMENTAR_ID, a.VERSION";

  private List<AttachmentRow> queryRows(final String where, final Object... args) {
    return jdbcTemplate.query(
        "SELECT "
            + ATTACHMENT_COLUMNS
            + " FROM [dbo].[TASK_ANHANG] a JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID WHERE "
            + where,
        (rs, i) ->
            new AttachmentRow(
                rs.getLong("ID"),
                UUID.fromString(rs.getString("EXTERNALID")),
                rs.getString("DATEINAME"),
                rs.getString("SPEICHERSCHLUESSEL"),
                rs.getString("SHA256"),
                rs.getBoolean("HAT_VORSCHAU"),
                UUID.fromString(rs.getString("HOCHGELADEN_VON_REF")),
                rs.getString("ENTITY_STATUS"),
                rs.getObject("KOMMENTAR_ID", Long.class),
                rs.getInt("VERSION")),
        args);
  }

  /** Aktive Aufgaben-Anhaenge (ohne Kommentar-Anhaenge) einer Aufgabe. */
  private List<AttachmentRow> activeTaskRows(final UUID taskId) {
    return queryRows(
        "t.EXTERNALID = ? AND a.ENTITY_STATUS = 'ACTIVE' AND a.KOMMENTAR_ID IS NULL", taskId);
  }

  private AttachmentRow row(final UUID attachmentId) {
    final List<AttachmentRow> rows = queryRows("a.EXTERNALID = ?", attachmentId);
    assertThat(rows).as("Zeile zu Anhang %s", attachmentId).hasSize(1);
    return rows.get(0);
  }

  private static AttachmentRow rowByUuid(final List<AttachmentRow> rows, final UUID uuid) {
    return rows.stream()
        .filter(r -> r.uuid().equals(uuid))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Keine Zeile zu Anhang " + uuid));
  }

  /** Alle Zeilen (jeder Status, auch Kommentar-Anhaenge) einer Aufgabe. */
  private int attachmentRowCount(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_ANHANG] a JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ?",
        Integer.class,
        taskId);
  }

  /**
   * Saet eine Anhang-Zeile per JDBC (ohne Datei, Schluessel NULL); nur fuer Zaehlung und
   * Sichtbarkeit.
   */
  private long insertAttachmentRow(
      final long taskDbId,
      final Long commentDbId,
      final String entityStatus,
      final UUID uuid,
      final String fileName) {
    return insertAttachmentRow(
        taskDbId,
        commentDbId,
        entityStatus,
        uuid,
        fileName,
        "txt",
        "text/plain",
        TXT_BYTES.length,
        sha256Hex(TXT_BYTES),
        null,
        CREATOR_REF);
  }

  private long insertAttachmentRow(
      final long taskDbId,
      final Long commentDbId,
      final String entityStatus,
      final UUID uuid,
      final String fileName,
      final String extension,
      final String mimeType,
      final long sizeBytes,
      final String sha256,
      final String storageKey,
      final UUID uploadedByRef) {
    final long id = JDBC_ATTACHMENT_IDS.incrementAndGet();
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_ANHANG]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, KOMMENTAR_ID, DATEINAME, MIME_TYP, DATEIENDUNG, GROESSE_BYTES, SHA256,"
            + " SPEICHERSCHLUESSEL, HAT_VORSCHAU, HOCHGELADEN_VON_REF)"
            + " VALUES (?, ?, 'attachment-it', SYSDATETIME(), ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,"
            + " ?)",
        id,
        entityStatus,
        uuid,
        TENANT_A_ID,
        taskDbId,
        new SqlParameterValue(Types.BIGINT, commentDbId),
        fileName,
        mimeType,
        extension,
        sizeBytes,
        sha256,
        new SqlParameterValue(Types.NVARCHAR, storageKey),
        uploadedByRef);
    return id;
  }

  /** Saet eine Anhang-Zeile mit vorgegebener ID und Schluessel (Datei liegt schon im Speicher). */
  private void insertAttachmentRowWithId(
      final long id,
      final long taskDbId,
      final Long commentDbId,
      final UUID uuid,
      final String fileName,
      final byte[] content,
      final String storageKey) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_ANHANG]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, KOMMENTAR_ID, DATEINAME, MIME_TYP, DATEIENDUNG, GROESSE_BYTES, SHA256,"
            + " SPEICHERSCHLUESSEL, HAT_VORSCHAU, HOCHGELADEN_VON_REF)"
            + " VALUES (?, 'ACTIVE', 'attachment-it', SYSDATETIME(), ?, 1, ?, ?, ?, ?,"
            + " 'text/plain', 'txt', ?, ?, ?, 0, ?)",
        id,
        uuid,
        TENANT_A_ID,
        taskDbId,
        new SqlParameterValue(Types.BIGINT, commentDbId),
        fileName,
        content.length,
        sha256Hex(content),
        storageKey,
        CREATOR_REF);
  }

  private long taskDbId(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskId);
  }

  private long commentDbId(final UUID commentId) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[TASK_COMMENT] WHERE EXTERNALID = ?", Long.class, commentId);
  }

  private int taskVersion(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT VERSION FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskId);
  }

  private Timestamp lastActivity(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT LAST_ACTIVITY_AT FROM [dbo].[TASK] WHERE EXTERNALID = ?", Timestamp.class, taskId);
  }

  /** Details ({@code CHANGE_DETAIL}) der Historieneintraege mit dem gegebenen Text. */
  private List<String> historyDetails(final UUID taskId, final String text) {
    return jdbcTemplate.queryForList(
        "SELECT h.CHANGE_DETAIL FROM [dbo].[TASK_HISTORY_ENTRY] h"
            + " JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID WHERE t.EXTERNALID = ? AND h.TEXT = ?",
        String.class,
        taskId,
        text);
  }

  /** Inbox-Generation einer Person; 0, solange noch keine Zeile existiert. */
  private long generation(final long tenantId, final UUID userRef) {
    final List<Long> generations =
        jdbcTemplate.queryForList(
            "SELECT GENERATION FROM [dbo].[TASK_INBOX_GENERATION]"
                + " WHERE TENANT_ID = ? AND USER_REF = ?",
            Long.class,
            tenantId,
            userRef);
    return generations.isEmpty() ? 0L : generations.get(0);
  }

  /** Laesst eine Person austreten (Zeile bleibt ACTIVE, READ-Mitglied, kein WRITE-Mitglied). */
  private void exitParticipant(final UUID taskId, final UUID ref) {
    final int updated =
        jdbcTemplate.update(
            "UPDATE p SET p.EXIT_KIND = 'UNSUBSCRIBED', p.EXIT_AT = SYSDATETIME(),"
                + " p.ASSIGNEE_FLAG = 0"
                + " FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ?",
            taskId,
            ref);
    assertThat(updated).as("Teilnehmerzeile fuer den Austritt").isEqualTo(1);
  }

  // ---- Hilfen: Dateien und Testdaten ---------------------------------------------------------

  /** Alle regulaeren Dateien unterhalb eines Ordners (leer, wenn es ihn nicht gibt). */
  static Set<Path> filesUnder(final Path dir) {
    if (!Files.isDirectory(dir)) {
      return Set.of();
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile).collect(Collectors.toSet());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String sha256(final Path file) throws IOException {
    return sha256Hex(Files.readAllBytes(file));
  }

  private static String sha256Hex(final byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] realPdf() {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      document.addPage(new PDPage(PDRectangle.A4));
      document.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** PDF-Kopf {@code %PDF-1.7}, mit Leerzeichen auf genau {@code size} Bytes aufgefuellt. */
  private static byte[] paddedPdf(final long size) {
    final byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1);
    final byte[] content = new byte[Math.toIntExact(size)];
    Arrays.fill(content, (byte) ' ');
    System.arraycopy(head, 0, content, 0, head.length);
    return content;
  }

  /** Text ohne NUL-Byte mit genau {@code size} Bytes. */
  private static byte[] textOfSize(final long size) {
    final byte[] content = new byte[Math.toIntExact(size)];
    Arrays.fill(content, (byte) 'a');
    return content;
  }

  /** Minimales DOCX: ZIP mit {@code [Content_Types].xml} und {@code word/document.xml}. */
  private static byte[] docx() {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private static byte[] png() {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private static void deleteQuietly(final Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // Aufraeumen best effort
    }
  }

  private static void deleteTreeQuietly(final Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(AttachmentControllerIT::deleteQuietly);
    } catch (IOException ignored) {
      // Aufraeumen best effort
    }
  }

  // ---- Hilfen: Sicherheit und Seed -----------------------------------------------------------

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

  // ---- Seam-Unterklasse ----------------------------------------------------------------------

  /**
   * Test-Unterklasse des {@link AttachmentCommandService}: bricht im Seam nach dem Schreiben der
   * Dateien ab, aber nur fuer die Aufgabe in {@link #FAIL_ON_TASK}; vorher merkt sie sich die
   * Dateien unter dem Mandantenordner 560 (Positivkontrolle des Rollback-Tests).
   */
  static class SeamFailingCommandService extends AttachmentCommandService {

    /** Interne ID der Aufgabe, deren Upload im Seam abbricht; {@code null} = nie. */
    static final AtomicReference<Long> FAIL_ON_TASK = new AtomicReference<>();

    /** Dateien unter {@code 560} zum Zeitpunkt des Seams. */
    static final AtomicReference<Set<Path>> FILES_AT_SEAM = new AtomicReference<>();

    SeamFailingCommandService(
        final UserAccessor userAccessor,
        final AttachmentTaskAccess taskAccess,
        final TaskAttachmentQueryRepository attachmentRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final AttachmentWriter writer,
        final BaseTaskRepository taskRepository,
        final TaskHistoryService historyService,
        final InboxGenerationService inboxGenerationService,
        final InboxSignalService inboxSignalService,
        final AttachmentModelMapper modelMapper,
        final AttachmentProperties properties) {
      super(
          userAccessor,
          taskAccess,
          attachmentRepository,
          userStateQueryRepository,
          participantQueryRepository,
          writer,
          taskRepository,
          historyService,
          inboxGenerationService,
          inboxSignalService,
          modelMapper,
          properties);
    }

    @Override
    protected void onAfterAttachmentsStoredForTest(final Long taskId) {
      if (taskId != null && taskId.equals(FAIL_ON_TASK.get())) {
        FILES_AT_SEAM.set(filesUnder(baseDir.resolve("560")));
        throw new IllegalStateException("Testabbruch nach dem Schreiben der Dateien.");
      }
    }
  }

  // ---- IT-Konfigurations-Kontext -------------------------------------------------------------

  @SpringBootConfiguration
  @AutoConfigurationPackage(
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
        "de.brasi.nova.taskmanagement.attachment",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.time",
      },
      excludeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ANNOTATION,
            classes = SpringBootConfiguration.class)
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
    // Pflicht: ohne AOP laeuft der KSF-AuditAspect nicht, und das zweite save derselben
    // Anhang-Instanz (Update mit Schluessel) endet im Versionskonflikt.
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
      return () -> "attachment-it";
    }

    /** Ersetzt den gescannten Kommando-Service (Primary) durch die Seam-Unterklasse. */
    @Bean
    @Primary
    AttachmentCommandService seamFailingAttachmentCommandService(
        final UserAccessor userAccessor,
        final AttachmentTaskAccess taskAccess,
        final TaskAttachmentQueryRepository attachmentRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final AttachmentWriter writer,
        final BaseTaskRepository taskRepository,
        final TaskHistoryService historyService,
        final InboxGenerationService inboxGenerationService,
        final InboxSignalService inboxSignalService,
        final AttachmentModelMapper modelMapper,
        final AttachmentProperties properties) {
      return new SeamFailingCommandService(
          userAccessor,
          taskAccess,
          attachmentRepository,
          userStateQueryRepository,
          participantQueryRepository,
          writer,
          taskRepository,
          historyService,
          inboxGenerationService,
          inboxSignalService,
          modelMapper,
          properties);
    }
  }
}
