package de.brasi.nova.taskmanagement.tag;

import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.tag.model.TagCreateInput;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import de.brasi.nova.taskmanagement.tag.model.TagUpdateInput;
import de.brasi.nova.taskmanagement.web.model.CreatedResourceResponse;
import io.keel.framework.api.hateoas.annotation.CollectionLinkRule;
import io.keel.framework.api.hateoas.annotation.EnableNovaHateoas;
import io.keel.framework.api.hateoas.annotation.HateoasLinkTarget;
import io.keel.framework.api.hateoas.annotation.LinkParam;
import io.keel.framework.api.hateoas.annotation.LinkRule;
import io.keel.framework.api.hateoas.annotation.LinkRules;
import io.keel.framework.api.openapi.annotation.ApiFindCollectionResponse;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.hateoas.CollectionModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer den globalen Tag-Katalog (arch-v2 §7).
 *
 * <p>Jeder Handler traegt eine {@link RequiresPermission}-Annotation (Pflicht gemaess arch-v2 §8).
 *
 * <p>Berechtigungen (R-TAGLIST-PERM):
 *
 * <ul>
 *   <li>GET / = authenticated-only (Standard-Selfservice, Slice 5) — jeder angemeldete Nutzer darf
 *       den globalen Tag-Katalog lesen; kein {@code @RequiresPermission}-Gate mehr.
 *   <li>POST / + PUT /{id} = {@link TaskManagementPermissions#TAG_ADMIN} — nur Admins verwalten
 *       Tags.
 * </ul>
 *
 * <p>PLAN #141 Slice 2: dieser Controller ist Regel-Owner der Affordance-Links fuer {@link TaskTag}
 * ({@code @EnableNovaHateoas}). Die {@code @LinkRules} deklarieren permission-gegatete HATEOAS-{@code
 * _links} (ITEM: update/deactivate/delete am {@code _embedded}-Item; COLLECTION: create am
 * Collection-Level). Alle Rels sind TAG_ADMIN-gegatet ({@link TagHateoas.Specs#TAG_ADMIN}); {@code
 * deactivate} zusaetzlich nur bei aktivem Tag ({@link TagHateoas.Specs#IS_ACTIVE}). Die {@code
 * _links} sind rein ADDITIV (FE-Anzeige) und veraendern die bestehende Enforcement NICHT - jeder
 * Mutations-Handler traegt unveraendert sein {@code @RequiresPermission(TAG_ADMIN)}.
 */
@RestController
@RequestMapping("/api/v1/tags")
@EnableNovaHateoas(entityClass = TaskTag.class)
@LinkRules(
    value = {
      @LinkRule(
          rel = TagHateoas.Rels.UPDATE,
          linkTo = TagHateoas.Targets.UPDATE,
          title = TagHateoas.Titles.UPDATE,
          conditions = {TagHateoas.Specs.TAG_ADMIN},
          params = {@LinkParam(name = "id", source = "#dto.id")}),
      @LinkRule(
          rel = TagHateoas.Rels.DEACTIVATE,
          linkTo = TagHateoas.Targets.DEACTIVATE,
          title = TagHateoas.Titles.DEACTIVATE,
          conditions = {TagHateoas.Specs.TAG_ADMIN, TagHateoas.Specs.IS_ACTIVE},
          params = {@LinkParam(name = "id", source = "#dto.id")}),
      @LinkRule(
          rel = TagHateoas.Rels.DELETE,
          linkTo = TagHateoas.Targets.DELETE,
          title = TagHateoas.Titles.DELETE,
          conditions = {TagHateoas.Specs.TAG_ADMIN},
          params = {@LinkParam(name = "id", source = "#dto.id")})
    },
    collectionRules = {
      @CollectionLinkRule(
          rel = TagHateoas.Rels.CREATE,
          linkTo = TagHateoas.Targets.CREATE,
          title = TagHateoas.Titles.CREATE,
          conditions = {TagHateoas.Specs.TAG_ADMIN})
    })
public class TagController {

  private final TagCatalogService catalogService;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public TagController(final TagCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  /**
   * Liefert alle aktiven Tags des globalen Katalogs.
   *
   * @return 200 OK mit Liste der Tags
   */
  @Operation(
      summary = "Tags auflisten",
      description =
          "Liefert alle aktiven Tags des globalen Katalogs (TENANT_ID=0) als HAL-CollectionModel."
              + " Die Tag-Items liegen unter _embedded.tags; jedes Item traegt eine optionale"
              + " UI-Farbe (color als Hex) und einen optionalen severityCode (null bei"
              + " color-only/neutralem Tag) sowie - permission-gegatet (TAG_ADMIN) - additive"
              + " Affordance-_links (update/deactivate/delete am Item, create am Collection-Level).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        // Bewusst OHNE explizites content: springdoc leitet das HAL-Collection-Schema
        // (_embedded.tags + _links) nativ aus dem Rueckgabetyp ab. Ein explizites @Content ohne
        // schema unterdrueckt dieses Schema im Default-Dokument (Regen-Quelle, #141 Slice 6).
        description = "Tag-Liste erfolgreich geladen (HAL-CollectionModel, Items unter _embedded.tags)."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT.")
  })
  @GetMapping
  @ApiFindCollectionResponse(TagModel.class)
  public ResponseEntity<CollectionModel<TagModel>> listTags() {
    return ResponseEntity.ok(CollectionModel.of(catalogService.list()));
  }

  /**
   * Legt einen neuen Tag im globalen Katalog an.
   *
   * <p>{@code severityCode} und {@code color} sind beide optional: ein gueltiger Hex-{@code color}
   * gewinnt; sonst wird die Farbe aus einem kanonischen {@code severityCode} abgeleitet; ohne
   * beides entsteht ein neutraler Tag (color und severity null).
   *
   * @param input Eingabe-DTO (name; optional severityCode; optional color)
   * @return 201 Created mit {"id": "<uuid>"}
   */
  @Operation(
      summary = "Tag anlegen",
      description =
          "Legt einen neuen Tag im globalen Katalog an (name; optional color als Hex #RGB/#RRGGBB;"
              + " optional severityCode). Erfordert TAG_ADMIN-Berechtigung.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Tag erfolgreich angelegt.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CreatedResourceResponse.class),
                examples =
                    @ExampleObject(value = "{\"id\": \"aa0e8400-e29b-41d4-a716-446655440010\"}"))),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Eingabe: leerer/zu langer Name, ungueltiger Hex-color, oder unbekannter"
                + " bzw. nicht-kanonischer severityCode."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(responseCode = "403", description = "Fehlende TAG_ADMIN-Berechtigung.")
  })
  @PostMapping
  @HateoasLinkTarget(TagHateoas.Targets.CREATE)
  @RequiresPermission(TaskManagementPermissions.TAG_ADMIN)
  public ResponseEntity<Map<String, UUID>> createTag(@RequestBody final TagCreateInput input) {
    final UUID id = catalogService.create(input);
    return ResponseEntity.created(URI.create("/api/v1/tags/" + id)).body(Map.of("id", id));
  }

  /**
   * Aktualisiert einen bestehenden Tag im globalen Katalog (PatchValue-Semantik: absent = Feld
   * unveraendert).
   *
   * <p>{@code color} present+null/leer loescht die Farbe und neutralisiert dabei die severity
   * (COLOR und SEVERITY_ID werden null). Eine widerspruechliche Kombination (color leeren UND
   * severityCode setzen in derselben Anfrage) ergibt 400.
   *
   * @param id externe UUID des Tags
   * @param input Patch-Eingabe (name, severityCode, color - je optional/PatchValue)
   * @return 204 No Content
   */
  @Operation(
      summary = "Tag aktualisieren",
      description =
          "Aktualisiert einen bestehenden Tag (name, severityCode und/oder color; PatchValue:"
              + " absent = unveraendert, color null/leer = Farbe und severity loeschen). Erfordert"
              + " TAG_ADMIN-Berechtigung.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Tag erfolgreich aktualisiert."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Eingabe: leerer/zu langer Name, ungueltiger Hex-color, unbekannter bzw."
                + " nicht-kanonischer severityCode, oder widerspruechliche color-Loeschung +"
                + " severityCode-Setzen."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(responseCode = "403", description = "Fehlende TAG_ADMIN-Berechtigung."),
    @ApiResponse(responseCode = "404", description = "Tag nicht gefunden.")
  })
  @PutMapping("/{id}")
  @HateoasLinkTarget(TagHateoas.Targets.UPDATE)
  @RequiresPermission(TaskManagementPermissions.TAG_ADMIN)
  public ResponseEntity<Void> updateTag(
      @Parameter(description = "Externe UUID des Tags.", required = true) @PathVariable
          final UUID id,
      @RequestBody final TagUpdateInput input) {
    catalogService.update(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Deaktiviert (retire-t) einen Tag im globalen Katalog (Intake #138, Slice 138-2).
   *
   * <p>Setzt {@code ENTITY_STATUS=DEACTIVE}; bestehende Zuordnungen bleiben erhalten (bewusst
   * gelockerte Invariante: ein deaktivierter-aber-zugeordneter Tag ist GEWOLLT). Nach dem Retire
   * ist der Tag aus GET /tags und snapshot.tags verschwunden, bestehende Aufgaben behalten ihn (das
   * Detail liefert ihn faithful; ein PATCH-Resubmit der zugeordneten UUID wird toleriert).
   *
   * <p><b>Lebenszyklus:</b> {@code DEACTIVE} ist terminal. Ein bereits deaktivierter Tag liefert
   * {@code 404} (nicht mehr im aktiven Katalog) - idempotenz-neutral und gewollt; es gibt bewusst
   * KEINE Reaktivierung.
   *
   * @param id externe UUID des Tags
   * @return 204 No Content
   */
  @Operation(
      summary = "Tag deaktivieren (retiren)",
      description =
          "Deaktiviert einen aktiven Tag (ENTITY_STATUS=DEACTIVE). Bestehende Zuordnungen zu Aufgaben"
              + " bleiben unveraendert; der Tag verschwindet nur aus dem aktiven Katalog (GET /tags,"
              + " snapshot.tags) und ist nicht mehr neu auswaehlbar. DEACTIVE ist terminal (keine"
              + " Reaktivierung). Erfordert TAG_ADMIN-Berechtigung.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Tag erfolgreich deaktiviert."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(responseCode = "403", description = "Fehlende TAG_ADMIN-Berechtigung."),
    @ApiResponse(
        responseCode = "404",
        description = "Tag nicht gefunden oder bereits deaktiviert (terminal, neutral maskiert).")
  })
  @PostMapping("/{id}/deactivate")
  @HateoasLinkTarget(TagHateoas.Targets.DEACTIVATE)
  @RequiresPermission(TaskManagementPermissions.TAG_ADMIN)
  public ResponseEntity<Void> deactivateTag(
      @Parameter(description = "Externe UUID des Tags.", required = true) @PathVariable
          final UUID id) {
    catalogService.deactivate(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Loescht (soft-delete) einen Tag im globalen Katalog (Intake #138, Slice 138-3; Owner-Entscheid
   * D1=B, fail-closed).
   *
   * <p>Loeschen bedeutet {@code ENTITY_STATUS=DEACTIVE} (kein Hard-Delete). Ist der Tag noch an
   * mindestens EINE Aufgabe referenziert (ueber ALLE Mandanten gezaehlt), wird mit {@code 409}
   * abgelehnt und der Tag bleibt {@code ACTIVE}; die Meldung nennt NUR die Anzahl und erklaert die
   * Alternativen (Zuordnungen per PATCH entfernen ODER retiren). Unreferenziert -> {@code 204}.
   *
   * <p><b>Abgrenzung zu deactivate:</b> {@code deactivate} ist das bewusste Retire (immer erlaubt,
   * Zuordnungen bleiben); {@code delete} ist der fail-closed Loeschweg (nur unreferenziert). Beide
   * enden im terminalen {@code DEACTIVE}; auf einen bereits deaktivierten Tag liefern beide {@code
   * 404}.
   *
   * @param id externe UUID des Tags
   * @return 204 No Content
   */
  @Operation(
      summary = "Tag loeschen (fail-closed)",
      description =
          "Loescht einen Tag (soft-delete -> ENTITY_STATUS=DEACTIVE), aber NUR wenn er an keine"
              + " Aufgabe mehr referenziert ist (mandantenuebergreifend gezaehlt). Ist er noch"
              + " referenziert, wird mit 409 abgelehnt (Tag bleibt aktiv); die Meldung nennt die"
              + " Anzahl referenzierender Aufgaben und verweist auf das Entfernen der Zuordnungen"
              + " (PATCH tagIds) oder das Deaktivieren (POST .../deactivate). DEACTIVE ist terminal;"
              + " ein bereits deaktivierter Tag liefert 404. Erfordert TAG_ADMIN-Berechtigung.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Tag erfolgreich geloescht (deaktiviert)."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(responseCode = "403", description = "Fehlende TAG_ADMIN-Berechtigung."),
    @ApiResponse(
        responseCode = "404",
        description = "Tag nicht gefunden oder bereits deaktiviert (terminal, neutral maskiert)."),
    @ApiResponse(
        responseCode = "409",
        description =
            "Tag ist noch an mindestens eine Aufgabe referenziert (Anzahl in der Meldung);"
                + " der Tag bleibt aktiv. errorKey=NOVA_BUSINESS_RULE_VIOLATION.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = io.keel.framework.web.exception.ApiError.class)))
  })
  @DeleteMapping("/{id}")
  @HateoasLinkTarget(TagHateoas.Targets.DELETE)
  @RequiresPermission(TaskManagementPermissions.TAG_ADMIN)
  public ResponseEntity<Void> deleteTag(
      @Parameter(description = "Externe UUID des Tags.", required = true) @PathVariable
          final UUID id) {
    catalogService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
