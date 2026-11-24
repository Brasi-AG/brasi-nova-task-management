package de.brasi.nova.taskmanagement.comment;

import static de.brasi.nova.taskmanagement.support.AttachmentCountAssertions.assertCountMatchesRows;
import static de.brasi.nova.taskmanagement.support.AttachmentCountAssertions.attachmentCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.attachment.AttachmentModelMapper;
import de.brasi.nova.taskmanagement.attachment.AttachmentProperties;
import de.brasi.nova.taskmanagement.attachment.AttachmentWriter;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationMuteFilter;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig;
import de.brasi.nova.taskmanagement.push.NoOpNotificationPushConfig;
import de.brasi.nova.taskmanagement.repository.BaseTaskCommentRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController;
import de.brasi.nova.taskmanagement.support.AwaitStreamingBodyFilter;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import de.brasi.nova.taskmanagement.web.MultipartErrorAdvice;
import io.jsonwebtoken.Jwts;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import io.keel.framework.security.KsfSecurityAutoConfiguration;
import io.keel.framework.security.web.KsfWebSecurityAutoConfiguration;
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
import java.sql.Types;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integrationstest fuer {@code POST /api/v1/tasks/{id}/comments/with-attachments} (Intake #152 Teil
 * B3) gegen echtes MSSQL (Wegwerf-DB) und ein eigenes Temp-Verzeichnis als Basispfad der Anhaenge.
 *
 * <p>Vertrag: Kommentar und Anhaenge entstehen in einer Transaktion, alles oder nichts. Der Teil
 * {@code comment} (JSON, Content-Type {@code application/json}) folgt den Regeln der JSON-Variante
 * (Text Pflicht, Erwaehnungen, Benachrichtigungen unveraendert), die Teile {@code files} den Regeln
 * des Hochladens an der Aufgabe (Typ, Groesse, Inhalt, hoechstens 20 je Kommentar;
 * Aufgaben-Anhaenge zaehlen nicht mit). Die Mitgliedschaft wird vor jeder Dateipruefung verlangt
 * (Nicht-Mitglied: neutrale 404, nie 400). Kommentar-Anhaenge stehen in der Kommentarliste, nicht
 * in der Liste der Aufgaben-Anhaenge und nicht im Detail, werden ueber den Aufgaben-Pfad
 * ausgeliefert (auch an Ausgetretene) und sind dort nicht entfernbar.
 *
 * <p>Der Kontext ersetzt den {@link CommentService} durch {@link SeamFailingCommentService}
 * (Primary): dessen Seam {@code onAfterAttachmentsStoredForTest} bricht nur ab, wenn {@link
 * SeamFailingCommentService#FAIL_NEXT} gesetzt ist (einmalig), sonst wirkt er wie die Produktion.
 * {@link MultipartErrorAdvice} wird importiert, weil das Paket {@code web} nicht gescannt wird.
 *
 * <p>Alle Methoden teilen Datenbank und Basispfad; Zaehler (Kommentare, Benachrichtigungen,
 * Dateien) werden deshalb als Differenz vorher/nachher geprueft (Lesson {@code
 * it-context-pitfalls}, Punkt 5).
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = CommentAttachmentIT.ItConfig.class,
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
class CommentAttachmentIT {

  private static final String TEST_KID = "comment-attachment-it-key";

  /** Mandant A: keine Zeile in {@code TASK_ANHANG_GRENZE}, Tarif STANDARD. */
  private static final long TENANT_A_ID = 560L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();

  /** Erstellerin der Aufgaben (TASK_WRITE fuer Anlegen, Hinzufuegen und Zuweisen). */
  private static final UUID CREATOR_REF = UUID.randomUUID();

  private static final String CREATOR_LOGIN = "creator-comment-attachment@brasi.de";

  /** Beteiligte Person ohne Rechte (nur Mitgliedschaft); in einem Test Assignee. */
  private static final UUID MEMBER_REF = UUID.randomUUID();

  private static final String MEMBER_LOGIN = "member-comment-attachment@brasi.de";

  /** Weitere beteiligte Person (Ziel einer Erwaehnung). */
  private static final UUID OTHER_REF = UUID.randomUUID();

  private static final String OTHER_LOGIN = "other-comment-attachment@brasi.de";

  /** Ausgetretene Person (READ-Mitglied, kein WRITE-Mitglied). */
  private static final UUID EX_REF = UUID.randomUUID();

  private static final String EX_LOGIN = "ex-comment-attachment@brasi.de";

  /** Nutzer des Mandanten A ohne Teilnehmerzeile. */
  private static final UUID NON_MEMBER_REF = UUID.randomUUID();

  private static final String NON_MEMBER_LOGIN = "non-member-comment-attachment@brasi.de";

  /** Nutzer des Mandanten A ohne Teilnehmerzeile, mit eigenem Namen (Erwaehnung im Verlauf). */
  private static final UUID MENTIONED_REF = UUID.randomUUID();

  private static final String MENTIONED_LOGIN = "mentioned-comment-attachment@brasi.de";
  private static final String MENTIONED_NAME = "Anhang Erwaehnte Person";

  /** Wie AssigneeService.HISTORY_PARTICIPANT_MENTIONED (dort nur im Paket sichtbar). */
  private static final String HISTORY_MENTIONED =
      "hat einen Beteiligten per Erwaehnung hinzugefuegt";

  /** Verlaufstext des Hinzufuegens per POST /participants (darf hier nicht entstehen). */
  private static final String HISTORY_ADDED = "hat einen Beteiligten hinzugefuegt";

  private static final String WITH_ATTACHMENTS_URL = "/api/v1/tasks/{id}/comments/with-attachments";

  /** Text der 400 bei mehr als 20 Dateien (aus {@code AttachmentUploadPreparer#prepare}). */
  private static final String MAX_PER_COMMENT_MESSAGE =
      "Höchstens 20 aktive Anhänge je Aufgabe bzw. je Kommentar.";

  /** Echtes einseitiges PDF (PDFBox): vorschaufaehig. */
  private static final byte[] PDF_BYTES = realPdf();

  private static final byte[] TXT_BYTES =
      "Notiz zum Kommentar\r\nZeile 2".getBytes(StandardCharsets.UTF_8);
  private static final byte[] MZ_BYTES = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};

  /** Minimale MP4-ftyp-Box: Groesse 24, {@code ftyp}, Major Brand {@code isom}, Marken (#184). */
  private static final byte[] MP4_BYTES = {
    0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'm', 'p',
    '4', '1'
  };

  /** CSV mit Umlauten und Semikolon als Trenner (#184). */
  private static final byte[] CSV_BYTES =
      "Name;Straße;Betrag\r\nMüller;Hauptstraße 1;12,50\r\n".getBytes(StandardCharsets.UTF_8);

  /** IDs der per JDBC gesaeten Anhang-Zeilen, weit weg von der KSF-Sequenz. */
  private static final AtomicLong JDBC_ATTACHMENT_IDS = new AtomicLong(8_300_000_000L);

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
      pemTempFile = Files.createTempFile("comment-attachment-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
      baseDir = Files.createTempDirectory("comment-attachment-it-store-");
    } catch (IOException e) {
      throw new UncheckedIOException("Konnte temporaere Dateien nicht anlegen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem", () -> "file:" + pemTempFile.toAbsolutePath());
    registry.add(
        "task-management.attachments.storage.base-dir", () -> baseDir.toAbsolutePath().toString());
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

  @BeforeAll
  static void seedPrerequisites(@Autowired final JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedBenutzerIfAbsent(jt, 5701L, CREATOR_REF, CREATOR_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5702L, MEMBER_REF, MEMBER_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5703L, OTHER_REF, OTHER_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5704L, EX_REF, EX_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5705L, NON_MEMBER_REF, NON_MEMBER_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, 5706L, MENTIONED_REF, MENTIONED_LOGIN, MENTIONED_NAME, TENANT_A_ID);
  }

  // ---- Anlegen mit Anhaengen ----------------------------------------------------------------

  @Test
  @DisplayName(
      "i152_multipart_comment_201_with_attachments: Kommentar mit PDF und TXT -> 201 mit"
          + " attachments; Zeilen tragen die Kommentar-ID, Dateien liegen mit Pruefsumme im"
          + " Speicher; genau ein Kommentar mehr; Kommentarliste zeigt die Anhaenge, Liste der"
          + " Aufgaben-Anhaenge und Detail nicht")
  void i152_multipart_comment_201_with_attachments() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Anlegen");
    final int commentRowsBefore = commentRowCount(taskId);
    final int commentCountBefore = commentCount(taskId);

    final ResultActions res =
        postWithAttachments(
            mvc,
            creator,
            taskId,
            commentJson("Siehe Anhang"),
            pdfFile("Bericht.pdf"),
            file("Notiz.txt", TXT_BYTES));

    res.andExpect(status().isCreated())
        .andExpect(jsonPath("$.text").value("Siehe Anhang"))
        .andExpect(jsonPath("$.taskId").value(taskId.toString()))
        .andExpect(jsonPath("$.authorRef").value(CREATOR_REF.toString()))
        .andExpect(jsonPath("$.isNew").value(false))
        .andExpect(jsonPath("$.attachments.length()").value(2))
        .andExpect(jsonPath("$.attachments[0].fileName").value("Bericht.pdf"))
        .andExpect(jsonPath("$.attachments[0].mimeType").value("application/pdf"))
        .andExpect(jsonPath("$.attachments[0].removable").value(false))
        .andExpect(
            jsonPath("$.attachments[0].links.content").value(containsString("/attachments/")))
        .andExpect(jsonPath("$.attachments[1].fileName").value("Notiz.txt"))
        .andExpect(jsonPath("$.attachments[1].removable").value(false));
    final JsonNode body = json(res);
    final UUID commentId = UUID.fromString(body.get("id").asText());
    final UUID pdfId = UUID.fromString(body.get("attachments").get(0).get("id").asText());
    res.andExpect(
        jsonPath("$.attachments[0].links.content")
            .value(endsWith("/api/v1/tasks/" + taskId + "/attachments/" + pdfId + "/content")));

    final List<AttachmentRow> rows = attachmentRows(taskId);
    assertThat(rows).hasSize(2);
    final long commentDbId = commentDbId(commentId);
    assertThat(rows)
        .allSatisfy(
            r -> {
              assertThat(r.commentId()).isEqualTo(commentDbId);
              assertThat(r.entityStatus()).isEqualTo("ACTIVE");
              assertThat(r.uploadedByRef()).isEqualTo(CREATOR_REF);
              assertThat(r.storageKey()).matches("560/\\d+/\\d+\\.(pdf|txt)");
              assertThat(baseDir.resolve(r.storageKey())).exists();
              assertThat(sha256(baseDir.resolve(r.storageKey()))).isEqualTo(r.sha256());
            });
    final AttachmentRow pdfRow = rowByUuid(rows, pdfId);
    assertThat(baseDir.resolve(pdfRow.storageKey())).hasBinaryContent(PDF_BYTES);

    assertThat(commentRowCount(taskId) - commentRowsBefore).isEqualTo(1);
    assertThat(commentCount(taskId) - commentCountBefore).isEqualTo(1);

    mvc.perform(get("/api/v1/tasks/{id}/comments", taskId).header("Authorization", bearer(creator)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(commentId.toString()))
        .andExpect(jsonPath("$[0].attachments.length()").value(2))
        .andExpect(jsonPath("$[0].attachments[0].id").value(pdfId.toString()));
    mvc.perform(
            get("/api/v1/tasks/{id}/attachments", taskId).header("Authorization", bearer(creator)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/v1/tasks/{id}", taskId).header("Authorization", bearer(creator)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attachments.length()").value(0));
  }

  /**
   * Intake #184: Ein Kommentar mit MP4 und CSV wird angenommen. Die Anhaenge tragen {@code
   * video/mp4} bzw. {@code text/csv}, haben kein Vorschaubild und werden ueber den Aufgaben-Pfad
   * mit ihrem Typ ausgeliefert.
   */
  @Test
  @DisplayName(
      "i184_comment_with_mp4_and_csv: Kommentar mit MP4 und CSV -> 201, mimeType video/mp4 bzw."
          + " text/csv, hasThumbnail false; Zeilen tragen die Kommentar-ID; Inhalt wird mit dem Typ"
          + " ausgeliefert; Kommentarliste zeigt beide Anhaenge")
  void i184_comment_with_mp4_and_csv() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT MP4 und CSV");
    final int commentRowsBefore = commentRowCount(taskId);

    final ResultActions res =
        postWithAttachments(
            mvc,
            creator,
            taskId,
            commentJson("Video und Tabelle"),
            file("Video.mp4", MP4_BYTES),
            file("Daten.csv", CSV_BYTES));

    res.andExpect(status().isCreated())
        .andExpect(jsonPath("$.text").value("Video und Tabelle"))
        .andExpect(jsonPath("$.attachments.length()").value(2))
        .andExpect(jsonPath("$.attachments[0].fileName").value("Video.mp4"))
        .andExpect(jsonPath("$.attachments[0].mimeType").value("video/mp4"))
        .andExpect(jsonPath("$.attachments[0].hasThumbnail").value(false))
        .andExpect(jsonPath("$.attachments[0].removable").value(false))
        .andExpect(jsonPath("$.attachments[1].fileName").value("Daten.csv"))
        .andExpect(jsonPath("$.attachments[1].mimeType").value("text/csv"))
        .andExpect(jsonPath("$.attachments[1].hasThumbnail").value(false))
        .andExpect(jsonPath("$.attachments[1].removable").value(false));
    final JsonNode body = json(res);
    final UUID commentId = UUID.fromString(body.get("id").asText());
    final UUID mp4Id = UUID.fromString(body.get("attachments").get(0).get("id").asText());
    final UUID csvId = UUID.fromString(body.get("attachments").get(1).get("id").asText());
    for (final int index : new int[] {0, 1}) {
      final JsonNode thumbnailLink =
          body.get("attachments").get(index).path("links").path("thumbnail");
      assertThat(thumbnailLink.isNull() || thumbnailLink.isMissingNode())
          .as("Kein Vorschau-Link: %s", body.get("attachments").get(index))
          .isTrue();
    }

    final List<AttachmentRow> rows = attachmentRows(taskId);
    assertThat(rows).hasSize(2);
    final long commentDbId = commentDbId(commentId);
    assertThat(rows)
        .allSatisfy(
            r -> {
              assertThat(r.commentId()).isEqualTo(commentDbId);
              assertThat(r.entityStatus()).isEqualTo("ACTIVE");
              assertThat(sha256(baseDir.resolve(r.storageKey()))).isEqualTo(r.sha256());
            });
    final AttachmentRow mp4Row = rowByUuid(rows, mp4Id);
    final AttachmentRow csvRow = rowByUuid(rows, csvId);
    assertThat(mp4Row.storageKey()).matches("560/\\d+/\\d+\\.mp4");
    assertThat(csvRow.storageKey()).matches("560/\\d+/\\d+\\.csv");
    assertThat(baseDir.resolve(mp4Row.storageKey())).hasBinaryContent(MP4_BYTES);
    assertThat(baseDir.resolve(csvRow.storageKey())).hasBinaryContent(CSV_BYTES);
    assertThat(commentRowCount(taskId) - commentRowsBefore).isEqualTo(1);

    // Auslieferung ueber den Aufgaben-Pfad mit dem Typ des Anhangs
    final MvcResult mp4Content =
        fetch(
            mvc,
            get("/api/v1/tasks/{id}/attachments/{attachmentId}/content", taskId, mp4Id),
            creator);
    assertThat(mp4Content.getResponse().getStatus()).isEqualTo(200);
    assertThat(mp4Content.getResponse().getContentType()).contains("video/mp4");
    assertThat(mp4Content.getResponse().getContentAsByteArray()).isEqualTo(MP4_BYTES);
    final MvcResult csvContent =
        fetch(
            mvc,
            get("/api/v1/tasks/{id}/attachments/{attachmentId}/content", taskId, csvId),
            creator);
    assertThat(csvContent.getResponse().getStatus()).isEqualTo(200);
    assertThat(csvContent.getResponse().getContentType()).contains("text/csv");
    assertThat(csvContent.getResponse().getContentAsByteArray()).isEqualTo(CSV_BYTES);

    mvc.perform(get("/api/v1/tasks/{id}/comments", taskId).header("Authorization", bearer(creator)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].attachments.length()").value(2))
        .andExpect(jsonPath("$[0].attachments[0].mimeType").value("video/mp4"))
        .andExpect(jsonPath("$[0].attachments[1].mimeType").value("text/csv"));
  }

  // ---- Alles oder nichts: Validierung ----------------------------------------------------------

  @Test
  @DisplayName(
      "i152_blank_text_with_files_400_nothing_stored: Text nur aus Leerzeichen mit Datei -> 400;"
          + " kein Kommentar, keine Anhangzeile, keine Datei")
  void i152_blank_text_with_files_400_nothing_stored() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT leerer Text");
    final Snapshot before = snapshot(taskId);

    postWithAttachments(mvc, creator, taskId, commentJson(" "), pdfFile("Bericht.pdf"))
        .andExpect(status().isBadRequest());

    assertUnchanged(taskId, before);
  }

  @Test
  @DisplayName(
      "i152_invalid_file_400_no_comment: gueltiger Text, PDF und EXE -> 400"
          + " NOVA_VALIDATION_FAILED; TASK_COMMENT und COMMENT_COUNT unveraendert, keine Datei")
  void i152_invalid_file_400_no_comment() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT ungueltige Datei");
    final Snapshot before = snapshot(taskId);

    postWithAttachments(
            mvc,
            creator,
            taskId,
            commentJson("Mit falscher Datei"),
            pdfFile("Bericht.pdf"),
            file("Programm.exe", MZ_BYTES))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value(containsString("„.exe“")));

    assertUnchanged(taskId, before);
  }

  @Test
  @DisplayName(
      "i152_twenty_one_files_400: 21 Dateien -> 400 (hoechstens 20 je Kommentar), nichts"
          + " gespeichert; 20 Dateien werden angenommen, ein Aufgaben-Anhang zaehlt nicht mit")
  void i152_twenty_one_files_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Hoechstzahl");
    // Ein aktiver Anhang direkt an der Aufgabe: die Grenze gilt je Kommentar (activeCount = 0).
    insertTaskAttachmentRow(taskDbId(taskId), "aufgabe.txt");
    final Snapshot before = snapshot(taskId);

    postWithAttachments(mvc, creator, taskId, commentJson("Zu viele"), txtFiles(21))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value(MAX_PER_COMMENT_MESSAGE));
    assertUnchanged(taskId, before);

    postWithAttachments(mvc, creator, taskId, commentJson("Genau zwanzig"), txtFiles(20))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.attachments.length()").value(20));
    assertThat(commentRowCount(taskId) - before.commentRows()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i152_comment_part_missing_400: ohne Teil comment -> 400 NOVA_VALIDATION_FAILED (Teil"
          + " genannt); ohne Teil files ebenfalls 400; jeweils nichts gespeichert")
  void i152_comment_part_missing_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Teil fehlt");
    final Snapshot before = snapshot(taskId);

    postWithAttachments(mvc, creator, taskId, null, pdfFile("Bericht.pdf"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value(containsString("comment")));
    assertUnchanged(taskId, before);

    postWithAttachments(mvc, creator, taskId, commentJson("Ohne Datei"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"));
    assertUnchanged(taskId, before);
  }

  // ---- Teil comment ohne JSON-Content-Type (P415) --------------------------------------------

  @Test
  @DisplayName(
      "i152_comment_part_without_content_type_201: Teil comment ohne Content-Type (so sendet ihn"
          + " FormData.append) wird als JSON gelesen -> 201 mit Anhang; Umlaute unveraendert, ein"
          + " Kommentar und eine Anhangzeile mehr")
  void i152_comment_part_without_content_type_201() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT ohne Content-Type");
    final Snapshot before = snapshot(taskId);

    postWithCommentPart(
            mvc, creator, taskId, null, commentJson("Größe prüfen"), pdfFile("Bericht.pdf"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.text").value("Größe prüfen"))
        .andExpect(jsonPath("$.attachments.length()").value(1))
        .andExpect(jsonPath("$.attachments[0].fileName").value("Bericht.pdf"));

    assertThat(commentRowCount(taskId) - before.commentRows()).isEqualTo(1);
    assertThat(attachmentRowCount(taskId) - before.attachmentRows()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i152_comment_part_text_plain_201: Teil comment als text/plain wird als JSON gelesen -> 201"
          + " mit Anhang, ein Kommentar und eine Anhangzeile mehr")
  void i152_comment_part_text_plain_201() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT text/plain");
    final Snapshot before = snapshot(taskId);

    postWithCommentPart(
            mvc,
            creator,
            taskId,
            MediaType.TEXT_PLAIN_VALUE,
            commentJson("Als Klartext gesendet"),
            pdfFile("Bericht.pdf"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.text").value("Als Klartext gesendet"))
        .andExpect(jsonPath("$.attachments.length()").value(1));

    assertThat(commentRowCount(taskId) - before.commentRows()).isEqualTo(1);
    assertThat(attachmentRowCount(taskId) - before.attachmentRows()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i152_comment_part_invalid_json_400_same_key: ungueltiges JSON im Teil comment (ohne Typ,"
          + " text/plain, application/json) -> 400 im KSF-Format mit demselben errorKey wie die"
          + " JSON-Variante; auch fuer Nicht-Mitglieder 400 statt 404 (der Teil wird vor der"
          + " Mitgliedschaft gelesen); nichts gespeichert")
  void i152_comment_part_invalid_json_400_same_key() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT ungueltiges JSON");
    final Snapshot before = snapshot(taskId);
    final String invalid = "{\"text\": kein json";

    // Referenz: der JSON-Weg ohne Anhaenge
    final JsonNode reference =
        json(
            mvc.perform(
                    post("/api/v1/tasks/{id}/comments", taskId)
                        .header("Authorization", bearer(creator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest()));
    final String expectedKey = reference.get("errorKey").asText();
    assertThat(expectedKey).isEqualTo("NOVA_MESSAGE_NOT_READABLE");

    for (final String contentType :
        new String[] {null, MediaType.TEXT_PLAIN_VALUE, "application/json"}) {
      for (final String token : List.of(creator, token(NON_MEMBER_REF, NON_MEMBER_LOGIN))) {
        final JsonNode body =
            json(
                postWithCommentPart(
                        mvc, token, taskId, contentType, invalid, pdfFile("Bericht.pdf"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400)));
        assertThat(body.get("errorKey").asText()).isEqualTo(expectedKey);
        assertThat(body.get("message").asText()).isEqualTo(reference.get("message").asText());
        assertThat(body.get("path").asText())
            .isEqualTo("/api/v1/tasks/" + taskId + "/comments/with-attachments");
      }
    }

    assertUnchanged(taskId, before);
  }

  // ---- Rechte ---------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "i152_non_member_404_before_file_validation: Nicht-Mitglied mit EXE -> 404 NOVA_NOT_FOUND"
          + " (nicht 400); unbekannte Aufgabe ebenso; nichts gespeichert")
  void i152_non_member_404_before_file_validation() throws Exception {
    final MockMvc mvc = buildMvc();
    final UUID taskId = createTask(mvc, creatorToken(), "Kommentar-Anhang-IT Nicht-Mitglied");
    final Snapshot before = snapshot(taskId);

    postWithAttachments(
            mvc,
            token(NON_MEMBER_REF, NON_MEMBER_LOGIN),
            taskId,
            commentJson("Darf ich nicht"),
            file("x.exe", MZ_BYTES))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
    final UUID unknownTask = UUID.randomUUID();
    postWithAttachments(
            mvc, creatorToken(), unknownTask, commentJson("Unbekannt"), file("x.exe", MZ_BYTES))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));

    assertUnchanged(taskId, before);
  }

  @Test
  @DisplayName(
      "i152_ex_member_cannot_comment_but_reads_attachment: Ausgetretene -> POST 404, nichts"
          + " gespeichert; den Inhalt eines Kommentar-Anhangs liest sie weiter (200)")
  void i152_ex_member_cannot_comment_but_reads_attachment() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final String ex = token(EX_REF, EX_LOGIN);
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Ausgetreten");
    addParticipants(mvc, creator, taskId, EX_REF);
    final JsonNode created =
        json(
            postWithAttachments(
                    mvc, creator, taskId, commentJson("Fuer alle"), file("Notiz.txt", TXT_BYTES))
                .andExpect(status().isCreated()));
    final UUID attachmentId = UUID.fromString(created.get("attachments").get(0).get("id").asText());
    exitParticipant(taskId, EX_REF);
    final Snapshot before = snapshot(taskId);

    postWithAttachments(mvc, ex, taskId, commentJson("Nach dem Austritt"), pdfFile("Bericht.pdf"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));
    assertUnchanged(taskId, before);

    final MvcResult content =
        fetch(
            mvc,
            get("/api/v1/tasks/{id}/attachments/{attachmentId}/content", taskId, attachmentId),
            ex);
    assertThat(content.getResponse().getStatus()).isEqualTo(200);
    assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(TXT_BYTES);
  }

  // ---- Benachrichtigungen ---------------------------------------------------------------------

  @Test
  @DisplayName(
      "i152_notifications_like_plain_comment: Top-Level mit Anhang und Erwaehnung -> Assignee"
          + " bekommt COMMENT (Differenz 1), Erwaehnte MENTION (Differenz 1) und kein COMMENT,"
          + " die Autorin nichts")
  void i152_notifications_like_plain_comment() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Benachrichtigung");
    addParticipants(mvc, creator, taskId, MEMBER_REF, OTHER_REF);
    putAssignees(mvc, creator, taskId, MEMBER_REF);

    final int memberCommentBefore = countNotifications(taskId, MEMBER_REF, "COMMENT");
    final int otherMentionBefore = countNotifications(taskId, OTHER_REF, "MENTION");
    final int otherCommentBefore = countNotifications(taskId, OTHER_REF, "COMMENT");
    final int creatorBefore = notificationCountOfRecipient(taskId, CREATOR_REF);

    postWithAttachments(
            mvc,
            creator,
            taskId,
            commentJson("Bitte ansehen", OTHER_REF),
            file("Notiz.txt", TXT_BYTES))
        .andExpect(status().isCreated());

    assertThat(countNotifications(taskId, MEMBER_REF, "COMMENT") - memberCommentBefore)
        .isEqualTo(1);
    assertThat(countNotifications(taskId, OTHER_REF, "MENTION") - otherMentionBefore).isEqualTo(1);
    assertThat(countNotifications(taskId, OTHER_REF, "COMMENT") - otherCommentBefore).isZero();
    assertThat(notificationCountOfRecipient(taskId, CREATOR_REF) - creatorBefore).isZero();
  }

  // ---- Verlauf (Intake #180) ------------------------------------------------------------------

  @Test
  @DisplayName(
      "i180_comment_with_attachment_mention_records_one_history_entry: Kommentar mit Anhang und"
          + " Erwaehnung eines Nicht-Mitglieds -> 201, genau ein neuer Verlaufseintrag 'hat einen"
          + " Beteiligten per Erwaehnung hinzugefuegt' (Akteur Autorin, Detail Name der Person),"
          + " keiner 'hat einen Beteiligten hinzugefuegt'; die erneute Erwaehnung der nun"
          + " aufgenommenen Person in einem zweiten Kommentar mit Anhang ergibt keinen Eintrag")
  void i180_comment_with_attachment_mention_records_one_history_entry() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Verlauf Erwaehnung");
    final String mention = commentJson("Bitte ansehen", MENTIONED_REF);

    final int beforeMention = historyTotal(taskId);
    postWithAttachments(mvc, creator, taskId, mention, file("Notiz.txt", TXT_BYTES))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.attachments.length()").value(1));
    assertThat(historyTotal(taskId) - beforeMention).as("genau ein Eintrag").isEqualTo(1);
    assertThat(historyEntries(taskId, CREATOR_REF, HISTORY_MENTIONED, MENTIONED_NAME))
        .as("Akteur ist die Autorin, Detail der Name der aufgenommenen Person")
        .isEqualTo(1);
    assertThat(historyEntries(taskId, CREATOR_REF, HISTORY_ADDED, MENTIONED_NAME))
        .as("nicht der Text des POST /participants")
        .isZero();

    final int beforeMemberMention = historyTotal(taskId);
    postWithAttachments(mvc, creator, taskId, mention, pdfFile("Bericht.pdf"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.attachments.length()").value(1));
    assertThat(historyTotal(taskId) - beforeMemberMention)
        .as("Erwaehnung eines Mitglieds: kein Eintrag")
        .isZero();
  }

  // ---- Abteilungserwaehnung (Intake #190) -----------------------------------------------------

  @Test
  @DisplayName(
      "i190_multipart_comment_accepts_division_mentions: Teil comment mit divisionMentions einer"
          + " aktiven Abteilung (ein Nicht-Mitglied) -> 201 mit Anhang, die Person ist Beteiligte"
          + " mit genau einer MENTION und einem Verlaufseintrag per Erwaehnung; eine unbekannte"
          + " Abteilung im selben Weg -> 400 im KSF-Format, nichts gespeichert (auch keine Datei)")
  void i190_multipart_comment_accepts_division_mentions() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Abteilung");
    final UUID divisionRef = UUID.randomUUID();
    insertDivision(divisionRef, "ACTIVE", "Anhang-Abteilung", MENTIONED_REF);
    final int beforeHistory = historyTotal(taskId);

    postWithAttachments(
            mvc,
            creator,
            taskId,
            commentJsonWithDivisions("Bitte ansehen, Abteilung", divisionRef),
            file("Notiz.txt", TXT_BYTES))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.attachments.length()").value(1));

    assertThat(activeParticipantRows(taskId, MENTIONED_REF))
        .as("per Abteilung aufgenommen")
        .isEqualTo(1);
    assertThat(countNotifications(taskId, MENTIONED_REF, "MENTION")).isEqualTo(1);
    assertThat(countNotifications(taskId, MENTIONED_REF, "PARTICIPANT")).isZero();
    assertThat(historyTotal(taskId) - beforeHistory).as("genau ein Eintrag").isEqualTo(1);
    assertThat(historyEntries(taskId, CREATOR_REF, HISTORY_MENTIONED, MENTIONED_NAME)).isEqualTo(1);

    final Snapshot before = snapshot(taskId);
    final int notificationsBefore = notificationCountOfTask(taskId);
    postWithAttachments(
            mvc,
            creator,
            taskId,
            commentJsonWithDivisions("Unbekannte Abteilung", UUID.randomUUID()),
            pdfFile("Bericht.pdf"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("Unbekannte oder nicht aktive Abteilung."));
    assertUnchanged(taskId, before);
    assertThat(notificationCountOfTask(taskId)).isEqualTo(notificationsBefore);
  }

  // ---- Rollback --------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "i152_rollback_after_attachments_written_leaves_nothing: Abbruch im Seam nach dem Schreiben"
          + " der Anhaenge -> 500; kein Kommentar, keine Anhangzeile, COMMENT_COUNT und Dateien"
          + " wie vorher, keine Benachrichtigung")
  void i152_rollback_after_attachments_written_leaves_nothing() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Rollback");
    addParticipants(mvc, creator, taskId, MEMBER_REF);
    putAssignees(mvc, creator, taskId, MEMBER_REF);
    final Snapshot before = snapshot(taskId);
    final int notificationsBefore = notificationCountOfTask(taskId);

    SeamFailingCommentService.FILES_AT_SEAM.set(null);
    SeamFailingCommentService.FAIL_NEXT.set(true);
    try {
      postWithAttachments(
              mvc,
              creator,
              taskId,
              commentJson("Wird zurueckgerollt"),
              pdfFile("Bericht.pdf"),
              file("Notiz.txt", TXT_BYTES))
          .andExpect(status().isInternalServerError());
    } finally {
      SeamFailingCommentService.FAIL_NEXT.set(false);
    }

    // Positivkontrolle: am Seam lagen die Dateien dieser Transaktion schon auf der Platte.
    final Set<Path> atSeam = SeamFailingCommentService.FILES_AT_SEAM.get();
    assertThat(atSeam).as("Seam wurde nicht erreicht").isNotNull();
    final Set<Path> writtenInTransaction = new HashSet<>(atSeam);
    writtenInTransaction.removeAll(before.files());
    assertThat(writtenInTransaction)
        .extracting(p -> p.getFileName().toString())
        .anySatisfy(name -> assertThat(name).matches("\\d+\\.pdf"))
        .anySatisfy(name -> assertThat(name).matches("\\d+\\.txt"));

    assertUnchanged(taskId, before);
    assertThat(notificationCountOfTask(taskId)).isEqualTo(notificationsBefore);
  }

  // ---- Intake #194: Anhang-Zaehler ANHANG_ANZAHL ----------------------------------------------

  @Test
  @DisplayName(
      "i194_comment_with_attachments_adds_n: Kommentar mit zwei Dateien -> COMMENT_COUNT +1,"
          + " ANHANG_ANZAHL +2, Zaehler gleich den aktiven Anhangzeilen")
  void i194_comment_with_attachments_adds_n() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Zaehler +2");
    assertCountMatchesRows(jdbcTemplate, taskId);
    final int commentCountBefore = commentCount(taskId);
    final int attachmentCountBefore = attachmentCount(jdbcTemplate, taskId);

    postWithAttachments(
            mvc,
            creator,
            taskId,
            commentJson("Zwei Dateien"),
            pdfFile("Bericht.pdf"),
            file("Notiz.txt", TXT_BYTES))
        .andExpect(status().isCreated());

    assertThat(commentCount(taskId) - commentCountBefore).as("COMMENT_COUNT").isEqualTo(1);
    assertThat(attachmentCount(jdbcTemplate, taskId) - attachmentCountBefore)
        .as("ANHANG_ANZAHL")
        .isEqualTo(2);
    assertCountMatchesRows(jdbcTemplate, taskId);
  }

  @Test
  @DisplayName(
      "i194_comment_without_attachments_keeps_count: nach einem Kommentar mit Datei aendert ein"
          + " Kommentar ohne Datei ANHANG_ANZAHL nicht, COMMENT_COUNT +1")
  void i194_comment_without_attachments_keeps_count() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Zaehler ohne Datei");
    postWithAttachments(
            mvc, creator, taskId, commentJson("Mit Datei"), file("Notiz.txt", TXT_BYTES))
        .andExpect(status().isCreated());
    assertCountMatchesRows(jdbcTemplate, taskId);
    final int commentCountBefore = commentCount(taskId);
    final int attachmentCountBefore = attachmentCount(jdbcTemplate, taskId);
    assertThat(attachmentCountBefore).as("Vorlauf: ein Kommentar-Anhang").isEqualTo(1);

    mvc.perform(
            post("/api/v1/tasks/{id}/comments", taskId)
                .header("Authorization", bearer(creator))
                .contentType(MediaType.APPLICATION_JSON)
                .content(commentJson("Ohne Datei")))
        .andExpect(status().isCreated());

    assertThat(commentCount(taskId) - commentCountBefore).as("COMMENT_COUNT").isEqualTo(1);
    assertThat(attachmentCount(jdbcTemplate, taskId))
        .as("ANHANG_ANZAHL unveraendert")
        .isEqualTo(attachmentCountBefore);
    assertCountMatchesRows(jdbcTemplate, taskId);
  }

  @Test
  @DisplayName(
      "i194_failed_store_rolls_back_attachment_count: Abbruch im Seam nach dem Schreiben der"
          + " Anhaenge -> 500; ANHANG_ANZAHL, COMMENT_COUNT, Zeilen und Dateien wie vorher")
  void i194_failed_store_rolls_back_attachment_count() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Zaehler Rollback");
    // Vorlauf mit einem gespeicherten Kommentar-Anhang, damit der Rollback einen Wert > 0 haelt.
    postWithAttachments(
            mvc, creator, taskId, commentJson("Vorlauf"), file("Vorlauf.txt", TXT_BYTES))
        .andExpect(status().isCreated());
    assertCountMatchesRows(jdbcTemplate, taskId);
    final Snapshot before = snapshot(taskId);
    assertThat(before.attachmentCount()).as("Vorlauf: ein Kommentar-Anhang").isEqualTo(1);

    SeamFailingCommentService.FILES_AT_SEAM.set(null);
    SeamFailingCommentService.FAIL_NEXT.set(true);
    try {
      postWithAttachments(
              mvc,
              creator,
              taskId,
              commentJson("Wird zurueckgerollt"),
              pdfFile("Bericht.pdf"),
              file("Notiz.txt", TXT_BYTES))
          .andExpect(status().isInternalServerError());
    } finally {
      SeamFailingCommentService.FAIL_NEXT.set(false);
    }

    assertThat(SeamFailingCommentService.FILES_AT_SEAM.get())
        .as("Seam wurde nicht erreicht")
        .isNotNull();
    assertUnchanged(taskId, before);
    assertCountMatchesRows(jdbcTemplate, taskId);
  }

  // ---- Entfernen ------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "i152_comment_attachment_not_removable_via_task_delete: DELETE auf einen Kommentar-Anhang"
          + " ueber den Aufgaben-Pfad -> 404 NOVA_NOT_FOUND, Zeile bleibt ACTIVE")
  void i152_comment_attachment_not_removable_via_task_delete() throws Exception {
    final MockMvc mvc = buildMvc();
    final String creator = creatorToken();
    final UUID taskId = createTask(mvc, creator, "Kommentar-Anhang-IT Entfernen");
    final JsonNode created =
        json(
            postWithAttachments(
                    mvc, creator, taskId, commentJson("Mit Datei"), file("Notiz.txt", TXT_BYTES))
                .andExpect(status().isCreated()));
    final UUID attachmentId = UUID.fromString(created.get("attachments").get(0).get("id").asText());

    mvc.perform(
            delete("/api/v1/tasks/{id}/attachments/{attachmentId}", taskId, attachmentId)
                .header("Authorization", bearer(creator)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorKey").value("NOVA_NOT_FOUND"));

    assertThat(rowByUuid(attachmentRows(taskId), attachmentId).entityStatus()).isEqualTo("ACTIVE");
  }

  // ---- Hilfen: Requests -----------------------------------------------------------------------

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        // nach springSecurity() registriert, also innerhalb der Security-Kette
        .addFilters(new AwaitStreamingBodyFilter())
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

  private static String bearer(final String token) {
    return "Bearer " + token;
  }

  /**
   * Sendet {@code POST /comments/with-attachments}: Teil {@code comment} als JSON mit Content-Type
   * {@code application/json} (weggelassen bei {@code null}), dazu die Teile {@code files}.
   *
   * <p>Der Teil {@code comment} geht als {@link MockPart}: Der Controller nimmt ihn als {@code
   * Part} an (Intake #152 Teil B), und MockMvc stellt ueber {@code file(...)} hinzugefuegte Teile
   * nur als {@code MultipartFile} bereit, nicht ueber {@code getPart}.
   */
  private static ResultActions postWithAttachments(
      final MockMvc mvc,
      final String token,
      final UUID taskId,
      final String commentJson,
      final MockMultipartFile... files)
      throws Exception {
    final MockMultipartHttpServletRequestBuilder request = multipart(WITH_ATTACHMENTS_URL, taskId);
    if (commentJson != null) {
      final MockPart comment =
          new MockPart("comment", commentJson.getBytes(StandardCharsets.UTF_8));
      comment.getHeaders().setContentType(MediaType.APPLICATION_JSON);
      request.part(comment);
    }
    for (final MockMultipartFile file : files) {
      request.file(file);
    }
    return mvc.perform(request.header("Authorization", bearer(token)));
  }

  /**
   * Wie {@link #postWithAttachments}, aber der Teil {@code comment} wird wie von einem Browser mit
   * {@code FormData.append} gesendet: ohne Dateinamen und mit dem angegebenen Content-Type (bei
   * {@code null} gar keinen).
   */
  private static ResultActions postWithCommentPart(
      final MockMvc mvc,
      final String token,
      final UUID taskId,
      final String partContentType,
      final String commentPart,
      final MockMultipartFile... files)
      throws Exception {
    final MockMultipartHttpServletRequestBuilder request = multipart(WITH_ATTACHMENTS_URL, taskId);
    final MockPart comment = new MockPart("comment", commentPart.getBytes(StandardCharsets.UTF_8));
    if (partContentType != null) {
      comment.getHeaders().setContentType(MediaType.parseMediaType(partContentType));
    }
    request.part(comment);
    for (final MockMultipartFile file : files) {
      request.file(file);
    }
    return mvc.perform(request.header("Authorization", bearer(token)));
  }

  /** JSON des Teils {@code comment} mit Text und optionalen Erwaehnungen. */
  private static String commentJson(final String text, final UUID... mentions) throws Exception {
    return new ObjectMapper()
        .writeValueAsString(Map.of("text", text, "mentions", List.of(mentions)));
  }

  /** JSON des Teils {@code comment} mit Text und erwaehnten Abteilungen (Intake #190). */
  private static String commentJsonWithDivisions(final String text, final UUID... divisions)
      throws Exception {
    return new ObjectMapper()
        .writeValueAsString(Map.of("text", text, "divisionMentions", List.of(divisions)));
  }

  private static MockMultipartFile file(final String name, final byte[] content) {
    return new MockMultipartFile("files", name, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
  }

  private static MockMultipartFile pdfFile(final String name) {
    return file(name, PDF_BYTES);
  }

  /** {@code count} kleine Textdateien {@code datei-<n>.txt}. */
  private static MockMultipartFile[] txtFiles(final int count) {
    final MockMultipartFile[] files = new MockMultipartFile[count];
    for (int i = 0; i < count; i++) {
      files[i] = file("datei-" + i + ".txt", TXT_BYTES);
    }
    return files;
  }

  private static UUID createTask(final MockMvc mvc, final String token, final String title)
      throws Exception {
    final String response =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", bearer(token))
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
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    new ObjectMapper().writeValueAsString(Map.of("participantIds", List.of(refs)))))
        .andExpect(status().is2xxSuccessful());
  }

  /** Setzt die Assignee-Menge einer Aufgabe per PUT (erwartet 204). */
  private static void putAssignees(
      final MockMvc mvc, final String token, final UUID taskId, final UUID... assignees)
      throws Exception {
    mvc.perform(
            put("/api/v1/tasks/{id}/assignees", taskId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    new ObjectMapper().writeValueAsString(Map.of("assignees", List.of(assignees)))))
        .andExpect(status().isNoContent());
  }

  /**
   * Ruft einen Binaer-Endpunkt ab. Die Antwort ist ein {@code StreamingResponseBody}: der erste
   * Durchlauf startet die asynchrone Verarbeitung und kehrt erst nach dem Kopieren zurueck ({@link
   * AwaitStreamingBodyFilter}), {@code asyncDispatch} schliesst den Request ab. Ohne Async (Fehler)
   * zaehlt der erste Durchlauf.
   */
  private static MvcResult fetch(
      final MockMvc mvc, final MockHttpServletRequestBuilder request, final String token)
      throws Exception {
    final MvcResult first = mvc.perform(request.header("Authorization", bearer(token))).andReturn();
    if (!first.getRequest().isAsyncStarted()) {
      return first;
    }
    return mvc.perform(asyncDispatch(first)).andReturn();
  }

  private static JsonNode json(final ResultActions res) throws Exception {
    return new ObjectMapper()
        .readTree(res.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  // ---- Hilfen: Datenbank ----------------------------------------------------------------------

  /** Eine Zeile aus {@code TASK_ANHANG}. */
  record AttachmentRow(
      long id,
      UUID uuid,
      String storageKey,
      String sha256,
      UUID uploadedByRef,
      String entityStatus,
      Long commentId) {}

  /** Zustand einer Aufgabe vor einem abgelehnten oder zurueckgerollten Aufruf. */
  record Snapshot(
      int commentRows,
      int commentCount,
      int attachmentRows,
      int attachmentCount,
      Set<Path> files) {}

  private Snapshot snapshot(final UUID taskId) {
    return new Snapshot(
        commentRowCount(taskId),
        commentCount(taskId),
        attachmentRowCount(taskId),
        attachmentCount(jdbcTemplate, taskId),
        filesUnder(baseDir.resolve("560")));
  }

  /**
   * Nichts gespeichert: Kommentare, COMMENT_COUNT, ANHANG_ANZAHL (Intake #194), Anhangzeilen und
   * Dateien wie vorher.
   */
  private void assertUnchanged(final UUID taskId, final Snapshot before) {
    assertThat(commentRowCount(taskId)).as("TASK_COMMENT-Zeilen").isEqualTo(before.commentRows());
    assertThat(commentCount(taskId)).as("COMMENT_COUNT").isEqualTo(before.commentCount());
    assertThat(attachmentCount(jdbcTemplate, taskId))
        .as("ANHANG_ANZAHL")
        .isEqualTo(before.attachmentCount());
    assertThat(attachmentRowCount(taskId))
        .as("TASK_ANHANG-Zeilen")
        .isEqualTo(before.attachmentRows());
    assertThat(filesUnder(baseDir.resolve("560"))).as("Dateien").isEqualTo(before.files());
  }

  /** Die Zeilen der Kommentar-Anhaenge (jeder Status) einer Aufgabe. */
  private List<AttachmentRow> attachmentRows(final UUID taskId) {
    return jdbcTemplate.query(
        "SELECT a.ID, a.EXTERNALID, a.SPEICHERSCHLUESSEL, a.SHA256, a.HOCHGELADEN_VON_REF,"
            + " a.ENTITY_STATUS, a.KOMMENTAR_ID"
            + " FROM [dbo].[TASK_ANHANG] a JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND a.KOMMENTAR_ID IS NOT NULL",
        (rs, i) ->
            new AttachmentRow(
                rs.getLong("ID"),
                UUID.fromString(rs.getString("EXTERNALID")),
                rs.getString("SPEICHERSCHLUESSEL"),
                rs.getString("SHA256"),
                UUID.fromString(rs.getString("HOCHGELADEN_VON_REF")),
                rs.getString("ENTITY_STATUS"),
                rs.getObject("KOMMENTAR_ID", Long.class)),
        taskId);
  }

  private static AttachmentRow rowByUuid(final List<AttachmentRow> rows, final UUID uuid) {
    return rows.stream()
        .filter(r -> r.uuid().equals(uuid))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Keine Zeile zu Anhang " + uuid));
  }

  /** Alle Anhangzeilen (jeder Status, auch Aufgaben-Anhaenge) einer Aufgabe. */
  private int attachmentRowCount(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_ANHANG] a JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ?",
        Integer.class,
        taskId);
  }

  /** Alle Kommentarzeilen (jeder Status) einer Aufgabe. */
  private int commentRowCount(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] c JOIN [dbo].[TASK] t ON c.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ?",
        Integer.class,
        taskId);
  }

  private int commentCount(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskId);
  }

  private long taskDbId(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskId);
  }

  private long commentDbId(final UUID commentId) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[TASK_COMMENT] WHERE EXTERNALID = ?", Long.class, commentId);
  }

  /** IDs der per JDBC gesaeten Abteilungs- und Mitgliederzeilen (Intake #190). */
  private static final AtomicLong JDBC_DIVISION_IDS = new AtomicLong(8_310_000_000L);

  /**
   * Saet eine Abteilung der lokalen Projektion im Mandanten A per JDBC (Kopf mit Status und Name,
   * eine Mitgliederzeile je Person), wie sie der Consumer aus Thema 2 schreibt.
   */
  private void insertDivision(
      final UUID divisionRef, final String status, final String name, final UUID... members) {
    final long divisionId = JDBC_DIVISION_IDS.incrementAndGet();
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_ABTEILUNG] (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM,"
            + " EXTERNALID, VERSION, TENANT_ID, ABTEILUNG_REF, NAME, REVISION, ABGERUFEN_AM)"
            + " VALUES (?, ?, 'comment-attachment-it', SYSDATETIME(), NEWID(), 1, ?, ?, ?, ?,"
            + " SYSDATETIME())",
        divisionId,
        status,
        TENANT_A_ID,
        divisionRef,
        name,
        "a".repeat(64));
    for (final UUID member : members) {
      jdbcTemplate.update(
          "INSERT INTO [dbo].[TASK_ABTEILUNG_MITGLIED] (ID, ENTITY_STATUS, ANGELEGT_VON,"
              + " ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID, ABTEILUNG_ID, MITARBEITER_REF,"
              + " KERNMITGLIED) VALUES (?, 'ACTIVE', 'comment-attachment-it', SYSDATETIME(),"
              + " NEWID(), 1, ?, ?, ?, 0)",
          JDBC_DIVISION_IDS.incrementAndGet(),
          TENANT_A_ID,
          divisionId,
          member);
    }
  }

  /** Aktive (nicht ausgetretene) Teilnehmerzeilen einer Person an einer Aufgabe. */
  private int activeParticipantRows(final UUID taskId, final UUID ref) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ? AND p.ENTITY_STATUS = 'ACTIVE'"
            + " AND p.EXIT_KIND IS NULL",
        Integer.class,
        taskId,
        ref);
  }

  /** Alle Verlaufseintraege einer Aufgabe. */
  private int historyTotal(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t"
            + " ON h.TASK_ID = t.ID WHERE t.EXTERNALID = ?",
        Integer.class,
        taskId);
  }

  /** Verlaufseintraege einer Aufgabe mit Akteur, Text und Detail (Name der Person). */
  private int historyEntries(
      final UUID taskId, final UUID actorRef, final String text, final String detail) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t"
            + " ON h.TASK_ID = t.ID WHERE t.EXTERNALID = ? AND h.ACTOR_REF = ?"
            + " AND h.TEXT = ? AND h.CHANGE_DETAIL = ?",
        Integer.class,
        taskId,
        actorRef,
        text,
        detail);
  }

  /** Saet einen aktiven Text-Anhang direkt an der Aufgabe per JDBC (ohne Datei). */
  private void insertTaskAttachmentRow(final long taskDbId, final String fileName) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_ANHANG]"
            + " (ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, KOMMENTAR_ID, DATEINAME, MIME_TYP, DATEIENDUNG, GROESSE_BYTES, SHA256,"
            + " SPEICHERSCHLUESSEL, HAT_VORSCHAU, HOCHGELADEN_VON_REF)"
            + " VALUES (?, 'ACTIVE', 'comment-attachment-it', SYSDATETIME(), ?, 1, ?, ?, ?, ?,"
            + " 'text/plain', 'txt', ?, ?, NULL, 0, ?)",
        JDBC_ATTACHMENT_IDS.incrementAndGet(),
        UUID.randomUUID(),
        TENANT_A_ID,
        taskDbId,
        new SqlParameterValue(Types.BIGINT, null),
        fileName,
        TXT_BYTES.length,
        sha256Hex(TXT_BYTES),
        CREATOR_REF);
  }

  /** Anzahl der Benachrichtigungen eines Typ-CODEs fuer einen Empfaenger an einer Aufgabe. */
  private int countNotifications(final UUID taskId, final UUID recipientRef, final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
            + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
            + " JOIN [dbo].[DIC_NOTIFICATION_TYPE] d ON n.TYPE_ID = d.ID"
            + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ? AND d.CODE = ?",
        Integer.class,
        taskId,
        recipientRef,
        code);
  }

  /** Anzahl aller Benachrichtigungen (jeden Typs) eines Empfaengers an einer Aufgabe. */
  private int notificationCountOfRecipient(final UUID taskId, final UUID recipientRef) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
            + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ?",
        Integer.class,
        taskId,
        recipientRef);
  }

  /** Anzahl aller Benachrichtigungen an einer Aufgabe. */
  private int notificationCountOfTask(final UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
            + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID WHERE t.EXTERNALID = ?",
        Integer.class,
        taskId);
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

  // ---- Hilfen: Dateien und Testdaten ----------------------------------------------------------

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

  private static String sha256(final Path file) {
    try {
      return sha256Hex(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
      walk.sorted(Comparator.reverseOrder()).forEach(CommentAttachmentIT::deleteQuietly);
    } catch (IOException ignored) {
      // Aufraeumen best effort
    }
  }

  // ---- Hilfen: Sicherheit und Seed ------------------------------------------------------------

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
    seedBenutzerIfAbsent(jt, id, externalId, loginName, "IT Test User", tenantId);
  }

  private static void seedBenutzerIfAbsent(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final String loginName,
      final String fullName,
      final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, LOGIN_NAME, VOLLER_NAME) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?)",
        id,
        id,
        externalId,
        tenantId,
        loginName,
        fullName);
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  // ---- Seam-Unterklasse -----------------------------------------------------------------------

  /**
   * Test-Unterklasse des {@link CommentService}: bricht im Seam nach dem Speichern der Anhaenge
   * einmalig ab, wenn {@link #FAIL_NEXT} gesetzt ist; vorher merkt sie sich die Dateien unter dem
   * Mandantenordner 560 (Positivkontrolle des Rollback-Tests). Sonst wirkt sie wie die Produktion.
   */
  static class SeamFailingCommentService extends CommentService {

    /** Einmaliger Abbruch beim naechsten Erreichen des Seams. */
    static final AtomicBoolean FAIL_NEXT = new AtomicBoolean(false);

    /** Dateien unter {@code 560} zum Zeitpunkt des Seams. */
    static final AtomicReference<Set<Path>> FILES_AT_SEAM = new AtomicReference<>();

    SeamFailingCommentService(
        final BaseTaskRepository taskRepository,
        final BaseTaskCommentRepository commentRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final NotificationService notificationService,
        final InboxSignalService inboxSignalService,
        final InboxGenerationService inboxGenerationService,
        final UserAccessor userAccessor,
        final AuditTimeConverter auditTimeConverter,
        final TaskMembershipGuard membershipGuard,
        final NotificationMuteFilter muteFilter,
        final AttachmentWriter attachmentWriter,
        final AttachmentModelMapper attachmentModelMapper,
        final AttachmentProperties attachmentProperties,
        final MentionRecipientService mentionRecipientService) {
      super(
          taskRepository,
          commentRepository,
          userStateQueryRepository,
          participantQueryRepository,
          notificationService,
          inboxSignalService,
          inboxGenerationService,
          userAccessor,
          auditTimeConverter,
          membershipGuard,
          muteFilter,
          attachmentWriter,
          attachmentModelMapper,
          attachmentProperties,
          mentionRecipientService);
    }

    @Override
    protected void onAfterAttachmentsStoredForTest(final Long commentId) {
      if (FAIL_NEXT.getAndSet(false)) {
        FILES_AT_SEAM.set(filesUnder(baseDir.resolve("560")));
        throw new IllegalStateException("Testabbruch nach dem Speichern der Anhaenge.");
      }
    }
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
    TaskMembershipGuard.class,
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
    // Paket web wird nicht gescannt: fehlende Multipart-Teile sonst 500 statt 400.
    MultipartErrorAdvice.class,
    // Intake #190: CommentService haengt am DivisionMentionResolver (Paket division).
    de.brasi.nova.taskmanagement.division.DivisionMentionItWiring.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "comment-attachment-it";
    }

    /** Ersetzt den gescannten CommentService (Primary) durch die Seam-Unterklasse. */
    @Bean
    @Primary
    CommentService seamFailingCommentService(
        final BaseTaskRepository taskRepository,
        final BaseTaskCommentRepository commentRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final NotificationService notificationService,
        final InboxSignalService inboxSignalService,
        final InboxGenerationService inboxGenerationService,
        final UserAccessor userAccessor,
        final AuditTimeConverter auditTimeConverter,
        final TaskMembershipGuard membershipGuard,
        final NotificationMuteFilter muteFilter,
        final AttachmentWriter attachmentWriter,
        final AttachmentModelMapper attachmentModelMapper,
        final AttachmentProperties attachmentProperties,
        final MentionRecipientService mentionRecipientService) {
      return new SeamFailingCommentService(
          taskRepository,
          commentRepository,
          userStateQueryRepository,
          participantQueryRepository,
          notificationService,
          inboxSignalService,
          inboxGenerationService,
          userAccessor,
          auditTimeConverter,
          membershipGuard,
          muteFilter,
          attachmentWriter,
          attachmentModelMapper,
          attachmentProperties,
          mentionRecipientService);
    }
  }
}
