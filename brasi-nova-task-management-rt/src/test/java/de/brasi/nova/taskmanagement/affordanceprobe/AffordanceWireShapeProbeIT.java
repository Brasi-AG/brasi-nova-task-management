package de.brasi.nova.taskmanagement.affordanceprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SPIKE-WEGWERF-IT (PLAN #141 Slice 0, P4-mech + P5-mech). KEIN Produktionscode.
 *
 * <p>Serialisiert Probe-Modelle mit dem ECHTEN, vom KSF-Web-Stack konfigurierten {@link
 * ObjectMapper}-Bean und protokolliert die exakte Wire-Form:
 *
 * <ul>
 *   <li>P4-mech: {@code createdAt} erscheint genau EINMAL als ISO-8601-Instant (Z/Offset), nie als
 *       LocalDateTime.
 *   <li>P5-mech: Wire-Form von {@code CollectionModel} / {@code PagedModel} /
 *       {@code KeysetCollectionModel} (Items unter {@code _embedded.<rel>} vs. flach; wo die
 *       Cursor-Properties landen; wo {@code _links} liegt).
 * </ul>
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@code @RefSchemaItEnabled}); der Context-Boot liefert den
 * konfigurierten ObjectMapper-Bean, die Serialisierung selbst beruehrt keine DB.
 */
@SpringBootTest(
    classes = ProbeWebContext.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "task-management.test.affordance-probe.enabled=true",
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "spring.flyway.enabled=false",
      "ksf.persistence.dialect=MSSQL",
      "ksf.persistence.ddl-auto=none",
      "ksf.web-security.enabled=false",
      "ksf.web-security.filter-chain.enabled=false",
      "ksf.module-registration.enabled=false",
      "ksf.security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class AffordanceWireShapeProbeIT {

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private ObjectMapper objectMapper;

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
    }
  }

  // ---- P4-mech: createdAt genau einmal als Instant --------------------------------------------

  @Test
  @DisplayName("P4-mech: createdAt genau EINMAL als ISO-8601-Instant (Z/Offset), kein LocalDateTime")
  void p4_created_at_is_single_instant() throws Exception {
    final Instant now = Instant.parse("2026-11-05T09:30:00Z");
    final ProbeTimestampModel model = new ProbeTimestampModel(UUID.randomUUID(), now);
    final String json = objectMapper.writeValueAsString(model);
    System.out.println("[P4] ProbeTimestampModel -> " + json);

    // Genau ein createdAt-Vorkommen (regex zaehlt die JSON-Property).
    final long occurrences =
        java.util.regex.Pattern.compile("\"createdAt\"")
            .matcher(json)
            .results()
            .count();
    Assertions.assertEquals(1L, occurrences, "createdAt muss genau EINMAL im Draht stehen: " + json);

    final JsonNode root = objectMapper.readTree(json);
    final String createdAt = root.path("createdAt").asText();
    System.out.println("[P4-RESULT] createdAt=" + createdAt);
    Assertions.assertTrue(
        createdAt.endsWith("Z") || createdAt.matches(".*[+-]\\d{2}:?\\d{2}$"),
        "createdAt muss ein ISO-8601-Instant mit Z/Offset sein: " + createdAt);
    // Instant-Serialisierung darf nicht in eine LocalDateTime-Form ohne Zone kollabieren.
    Assertions.assertFalse(
        root.path("createdAt").isArray(), "createdAt darf kein Array (Timestamp-Komponenten) sein");
  }

  // ---- P5-mech: Listen-Wire-Form ---------------------------------------------------------------

  @Test
  @DisplayName("P5-mech: Wire-Form CollectionModel / PagedModel / KeysetCollectionModel")
  void p5_list_wire_shapes() throws Exception {
    final ProbeModel a = new ProbeModel(UUID.randomUUID(), "wire-a");
    final ProbeModel b = new ProbeModel(UUID.randomUUID(), "wire-b");

    // (i) CollectionModel
    final CollectionModel<ProbeModel> coll = CollectionModel.of(List.of(a, b));
    final String collJson = objectMapper.writeValueAsString(coll);
    System.out.println("[P5-i] CollectionModel<ProbeModel> -> " + collJson);

    // (ii) PagedModel
    final PagedModel<ProbeModel> paged =
        PagedModel.of(
            List.of(a, b), new PagedModel.PageMetadata(2, 0, 2, 1));
    final String pagedJson = objectMapper.writeValueAsString(paged);
    System.out.println("[P5-ii] PagedModel<ProbeModel> -> " + pagedJson);

    // (iii) KeysetCollectionModel (CollectionModel + nextCursor/hasMore/asOf)
    final KeysetCollectionModel keyset =
        new KeysetCollectionModel(
            List.of(a, b), "cursor-abc", true, Instant.parse("2026-11-05T09:30:00Z"));
    final String keysetJson = objectMapper.writeValueAsString(keyset);
    System.out.println("[P5-iii] KeysetCollectionModel -> " + keysetJson);

    // Reine Beobachtung: Struktur-Marker protokollieren, damit die Lane die Wire-Form kennt.
    final JsonNode collNode = objectMapper.readTree(collJson);
    final JsonNode pagedNode = objectMapper.readTree(pagedJson);
    final JsonNode keysetNode = objectMapper.readTree(keysetJson);
    System.out.println(
        "[P5-RESULT] coll.hasEmbedded="
            + collNode.has("_embedded")
            + " coll.hasLinks="
            + collNode.has("_links")
            + " paged.hasEmbedded="
            + pagedNode.has("_embedded")
            + " paged.hasPage="
            + pagedNode.has("page")
            + " keyset.hasEmbedded="
            + keysetNode.has("_embedded")
            + " keyset.nextCursorAt="
            + (keysetNode.has("nextCursor") ? "top-level" : "elsewhere")
            + " keyset.hasMoreAt="
            + (keysetNode.has("hasMore") ? "top-level" : "elsewhere")
            + " keyset.asOfAt="
            + (keysetNode.has("asOf") ? "top-level" : "elsewhere"));
  }
}
