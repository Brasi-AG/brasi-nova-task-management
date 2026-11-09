package de.brasi.nova.taskmanagement.affordanceprobe;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * SPIKE-WEGWERF-IT (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>Empirischer Nachweis der KSF-2.3.24-Affordance-Mechanik P1/P2/P3 ueber den ECHTEN
 * Web-/HATEOAS-Enrichment-Pfad (globaler {@code NovaFindResponseAdvice} + {@code @EnableNovaHateoas}
 * -Scan + {@code SelfLinkEnricher}), serviert via MockMvc. P1 ist die Kanarienvogel-Probe: erscheint
 * kein {@code _links.self}, ist die Enrichment-Pipeline nicht aktiv und die uebrigen Proben sind
 * nicht aussagekraeftig.
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@code @RefSchemaItEnabled}); die reale Persistenz-Autoconfig
 * speist die Enrichment-Infrastruktur, die Proben lesen aber keine Tabellen.
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
      // keine KSF-Web-Security: die Proben testen Enrichment-Mechanik, nicht Authz.
      "ksf.web-security.enabled=false",
      "ksf.web-security.filter-chain.enabled=false",
      "ksf.module-registration.enabled=false",
      // Der KSF-Web-Stack (ApplicationWarmer/NovaTenantProperties) verlangt eine Tenant-Entity-
      // Klasse auch bei deaktivierter Web-Security -> wie in Produktion auf die refentity.Firm.
      "ksf.security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      // Dev-Modus an, damit der HATEOAS-Self-Check bei Regelproblemen laut ist statt still.
      "ksf.web.dev.enabled=true",
      // Enrichment-/Response-Diagnose sichtbar machen (NOVA-RESP/NOVA-LINK auf DEBUG).
      "logging.level.io.keel.framework.web.mvc=DEBUG",
      "logging.level.io.keel.framework.web.hateoas=DEBUG",
      "logging.level.io.keel.framework.web.enrichment=DEBUG",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class AffordanceMechanicsProbeIT {

  private static HikariDataSource master;
  private static String dbName;

  private final ObjectMapper om = new ObjectMapper();

  @Autowired private WebApplicationContext webApplicationContext;
  private MockMvc mvc;

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

  /**
   * Die KSF-Laufzeit-Regelablage liest beim Link-Aufbau die Tabelle {@code system_controller_regel}
   * (annotations-Regeln werden mit optionalen DB-Regeln gemischt). Die minimale Wegwerf-DB enthaelt
   * nur PROD_FIRMA/PROD_BENUTZER -> die (leere) Tabelle wird hier angelegt, damit die Link-Erzeugung
   * nicht fail-safe mit "Ungueltiger Objektname" abbricht. Leer genuegt: dann greifen nur die
   * Annotation-Regeln.
   */
  @BeforeAll
  static void createRuntimeRuleTable(@Autowired JdbcTemplate jt) {
    jt.execute(
        "IF OBJECT_ID('dbo.system_controller_regel', 'U') IS NULL "
            + "CREATE TABLE [dbo].[system_controller_regel] ("
            + " [id] bigint NOT NULL IDENTITY(1,1) PRIMARY KEY,"
            + " [rule_schluessel] nvarchar(255) NULL,"
            + " [beschreibung] nvarchar(1024) NULL,"
            + " [entity_status] nvarchar(255) NOT NULL DEFAULT 'ACTIVE',"
            + " [gueltig_von] date NULL,"
            + " [gueltig_bis] date NULL,"
            + " [externalid] uniqueidentifier NOT NULL DEFAULT NEWID(),"
            + " [version] int NOT NULL DEFAULT 1,"
            + " [tenant_id] bigint NULL)");
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

  private static final long TENANT_ID = 590L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000590");

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    // Die Enrichment-Pipeline verlangt einen gebundenen Tenant/Request-Kontext, um Links zu
    // erzeugen (in Produktion bindet die KSF-Filterchain ihn aus dem JWT). Bei abgeschalteter
    // Web-Security wird er hier manuell gesetzt (Muster aus InboxReadEnrichmentIT).
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void clearTenant() {
    TenantContextHolder.clear();
  }

  // ---- P1: Option-1 self-Link am PLAIN @RestController -----------------------------------------

  @Test
  @DisplayName("P1 (Kanarienvogel): self-Link erscheint am plain @RestController via @EnableNovaHateoas")
  void p1_self_link_present_on_plain_controller() throws Exception {
    final UUID id = UUID.randomUUID();
    final String body =
        mvc.perform(get("/probe/owner/" + id))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[P1] GET /probe/owner/{id} -> " + body);
    org.junit.jupiter.api.Assertions.assertTrue(
        root.path("_links").path("self").path("href").isTextual(),
        "P1 BLOCKER: _links.self.href fehlt -> Enrichment-Pipeline nicht aktiv. Body=" + body);
  }

  // ---- P2: Item-Rels + Collection-Rels vom NICHT-Owner-Controller ------------------------------

  @Test
  @DisplayName("P2: Item-Rels + create-Collection-Rel am Nicht-Owner-Controller (nicht-leer + leer)")
  void p2_collection_from_non_owner_controller() throws Exception {
    final String listBody =
        mvc.perform(get("/probe/nonowner/list"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    System.out.println("[P2-list] GET /probe/nonowner/list -> " + listBody);

    final String emptyBody =
        mvc.perform(get("/probe/nonowner/empty"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    System.out.println("[P2-empty] GET /probe/nonowner/empty -> " + emptyBody);

    final JsonNode list = om.readTree(listBody);
    final JsonNode empty = om.readTree(emptyBody);

    // Items koennen unter _embedded.<rel> oder flach "items" liegen; beide Formen durchsuchen.
    final JsonNode firstItem = firstItem(list);
    final boolean itemHasSelf =
        firstItem != null && firstItem.path("_links").path("self").path("href").isTextual();
    final boolean itemHasEdit =
        firstItem != null && firstItem.path("_links").path("edit").path("href").isTextual();
    final boolean collectionHasCreate =
        list.path("_links").path("create").path("href").isTextual();
    final boolean emptyCollectionHasCreate =
        empty.path("_links").path("create").path("href").isTextual();

    System.out.println(
        "[P2-RESULT] item.self="
            + itemHasSelf
            + " item.edit="
            + itemHasEdit
            + " collection.create(non-empty)="
            + collectionHasCreate
            + " collection.create(empty)="
            + emptyCollectionHasCreate
            + " firstItem="
            + firstItem);
    // Beobachtung, kein hartes Pass/Fail: die Ergebnisse beantworten offene Design-Fragen.
  }

  // ---- P3: verschachtelter self braucht Regel-Owner? (BLOCKER-1) -------------------------------

  @Test
  @DisplayName("P3: nested self NUR unter Regel-Owner-Handler? (ohne vs. mit @EnableNovaHateoas)")
  void p3_nested_self_requires_rule_owner() throws Exception {
    final String noOwnerBody =
        mvc.perform(get("/probe/snapshot-noowner/view"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    System.out.println("[P3-noowner] GET /probe/snapshot-noowner/view -> " + noOwnerBody);

    final String ownerBody =
        mvc.perform(get("/probe/snapshot-owner/view"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    System.out.println("[P3-owner] GET /probe/snapshot-owner/view -> " + ownerBody);

    final boolean nestedSelfWithoutOwner = nestedItemHasSelf(om.readTree(noOwnerBody));
    final boolean nestedSelfWithOwner = nestedItemHasSelf(om.readTree(ownerBody));
    System.out.println(
        "[P3-RESULT] nestedSelf(no-owner)="
            + nestedSelfWithoutOwner
            + " nestedSelf(owner)="
            + nestedSelfWithOwner);
  }

  // ---- P5 (HAL-Wire ueber MVC): CollectionModel / PagedModel / KeysetCollectionModel -----------

  @Test
  @DisplayName("P5 (MVC-HAL-Wire): _embedded-Shape fuer Collection/Paged/Keyset ueber echten HTTP-Pfad")
  void p5_hal_wire_shapes_over_http() throws Exception {
    final String collJson =
        mvc.perform(get("/probe/nonowner/list"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final String pagedJson =
        mvc.perform(get("/probe/nonowner/paged"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final String keysetJson =
        mvc.perform(get("/probe/nonowner/keyset"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    System.out.println("[P5-MVC-coll] " + collJson);
    System.out.println("[P5-MVC-paged] " + pagedJson);
    System.out.println("[P5-MVC-keyset] " + keysetJson);

    final JsonNode coll = om.readTree(collJson);
    final JsonNode paged = om.readTree(pagedJson);
    final JsonNode keyset = om.readTree(keysetJson);
    System.out.println(
        "[P5-MVC-RESULT] coll.hasEmbedded="
            + coll.has("_embedded")
            + " coll.embeddedRel="
            + fieldNames(coll.path("_embedded"))
            + " | paged.hasEmbedded="
            + paged.has("_embedded")
            + " paged.hasPage="
            + paged.has("page")
            + " paged.hasLinks="
            + paged.has("_links")
            + " | keyset.hasEmbedded="
            + keyset.has("_embedded")
            + " keyset.nextCursor="
            + (keyset.has("nextCursor") ? "top-level" : "elsewhere")
            + " keyset.hasMore="
            + (keyset.has("hasMore") ? "top-level" : "elsewhere")
            + " keyset.asOf="
            + (keyset.has("asOf") ? "top-level" : "elsewhere")
            + " keyset.hasLinks="
            + keyset.has("_links"));
  }

  // ---- Hilfen ----------------------------------------------------------------------------------

  private static String fieldNames(final JsonNode node) {
    if (!node.isObject()) {
      return "<none>";
    }
    final StringBuilder sb = new StringBuilder("[");
    final var it = node.fieldNames();
    while (it.hasNext()) {
      sb.append(it.next());
      if (it.hasNext()) {
        sb.append(",");
      }
    }
    return sb.append("]").toString();
  }

  /** Findet das erste Collection-Item, egal ob unter {@code _embedded.<rel>} oder flach. */
  private static JsonNode firstItem(final JsonNode collection) {
    final JsonNode embedded = collection.path("_embedded");
    if (embedded.isObject()) {
      final var fields = embedded.fieldNames();
      while (fields.hasNext()) {
        final JsonNode arr = embedded.path(fields.next());
        if (arr.isArray() && arr.size() > 0) {
          return arr.get(0);
        }
      }
    }
    final JsonNode flat = collection.path("items");
    if (flat.isArray() && flat.size() > 0) {
      return flat.get(0);
    }
    // Manche Renderer legen Items direkt als Top-Level-Array ab.
    if (collection.isArray() && collection.size() > 0) {
      return collection.get(0);
    }
    return null;
  }

  /** Prueft, ob das erste verschachtelte InfoModel-Item einen self-Link traegt (items-Feld). */
  private static boolean nestedItemHasSelf(final JsonNode snapshot) {
    final JsonNode items = snapshot.path("items");
    if (items.isArray() && items.size() > 0) {
      return items.get(0).path("_links").path("self").path("href").isTextual();
    }
    // Falls der self-Enricher nested Collections ins HAL-_embedded verschiebt:
    final JsonNode embedded = snapshot.path("_embedded");
    if (embedded.isObject()) {
      final var fields = embedded.fieldNames();
      while (fields.hasNext()) {
        final JsonNode arr = embedded.path(fields.next());
        if (arr.isArray() && arr.size() > 0) {
          return arr.get(0).path("_links").path("self").path("href").isTextual();
        }
      }
    }
    return false;
  }
}
