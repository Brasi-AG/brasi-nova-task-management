package de.brasi.nova.taskmanagement.tag;

import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.tag.model.TagCreateInput;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import de.brasi.nova.taskmanagement.tag.model.TagUpdateInput;
import io.keel.framework.api.security.permission.RequiresPermission;
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
  @PutMapping("/{id}")
  @RequiresPermission(TaskManagementPermissions.TAG_ADMIN)
  public ResponseEntity<Void> updateTag(
      @PathVariable final UUID id,
      @RequestBody final TagUpdateInput input) {
    catalogService.update(id, input);
    return ResponseEntity.noContent().build();
  }
}
