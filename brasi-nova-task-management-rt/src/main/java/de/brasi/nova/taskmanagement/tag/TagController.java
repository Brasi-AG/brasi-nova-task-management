package de.brasi.nova.taskmanagement.tag;

import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.tag.model.TagCreateInput;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import de.brasi.nova.taskmanagement.tag.model.TagUpdateInput;
import de.brasi.nova.taskmanagement.web.model.CreatedResourceResponse;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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
 * <ul>
 *   <li>GET / = {@link TaskManagementPermissions#TASK_READ} — jeder Task-Nutzer darf Tags lesen.
 *   <li>POST / + PUT /{id} = {@link TaskManagementPermissions#TAG_ADMIN} — nur Admins verwalten Tags.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tags")
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
      description = "Liefert alle aktiven Tags des globalen Katalogs (TENANT_ID=0).")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Tag-Liste erfolgreich geladen.",
          content = @Content(
              mediaType = "application/json",
              examples = @ExampleObject(value = "["
                  + "{\"id\": \"aa0e8400-e29b-41d4-a716-446655440010\","
                  + "\"name\": \"Dringend\","
                  + "\"severityCode\": \"DANGER\"}]"))),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_READ-Berechtigung.")
  })
  @GetMapping
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<List<TagModel>> listTags() {
    return ResponseEntity.ok(catalogService.list());
  }

  /**
   * Legt einen neuen Tag im globalen Katalog an.
   *
   * @param input Eingabe-DTO (name, severityCode)
   * @return 201 Created mit {"id": "<uuid>"}
   */
  @Operation(
      summary = "Tag anlegen",
      description = "Legt einen neuen Tag im globalen Katalog an. Erfordert TAG_ADMIN-Berechtigung.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "201",
          description = "Tag erfolgreich angelegt.",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = CreatedResourceResponse.class),
              examples = @ExampleObject(value = "{\"id\": \"aa0e8400-e29b-41d4-a716-446655440010\"}"))),
      @ApiResponse(responseCode = "400", description = "Ungueltige Eingabe (leerer Name, unbekannter severityCode)."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TAG_ADMIN-Berechtigung.")
  })
  @PostMapping
  @RequiresPermission(TaskManagementPermissions.TAG_ADMIN)
  public ResponseEntity<Map<String, UUID>> createTag(@RequestBody final TagCreateInput input) {
    final UUID id = catalogService.create(input);
    return ResponseEntity.created(URI.create("/api/v1/tags/" + id)).body(Map.of("id", id));
  }

  /**
   * Aktualisiert einen bestehenden Tag im globalen Katalog.
   *
   * @param id    externe UUID des Tags
   * @param input Patch-Eingabe (name, severityCode)
   * @return 204 No Content
   */
  @Operation(
      summary = "Tag aktualisieren",
      description = "Aktualisiert einen bestehenden Tag (name und/oder severityCode). Erfordert TAG_ADMIN-Berechtigung.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Tag erfolgreich aktualisiert."),
      @ApiResponse(responseCode = "400", description = "Ungueltige Eingabe (leerer Name, unbekannter severityCode)."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TAG_ADMIN-Berechtigung."),
      @ApiResponse(responseCode = "404", description = "Tag nicht gefunden.")
  })
  @PutMapping("/{id}")
  @RequiresPermission(TaskManagementPermissions.TAG_ADMIN)
  public ResponseEntity<Void> updateTag(
      @Parameter(description = "Externe UUID des Tags.", required = true) @PathVariable final UUID id,
      @RequestBody final TagUpdateInput input) {
    catalogService.update(id, input);
    return ResponseEntity.noContent().build();
  }
}
