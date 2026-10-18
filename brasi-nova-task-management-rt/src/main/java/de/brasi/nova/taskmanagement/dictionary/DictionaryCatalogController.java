package de.brasi.nova.taskmanagement.dictionary;

import de.brasi.nova.taskmanagement.dictionary.model.DictionaryCatalogModel;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer den Dictionary-Katalog (arch-v2 §7).
 *
 * <p>Liefert alle 6 System-Dictionaries als einen kompakten Gesamtkatalog.
 * Jeder Handler traegt eine {@link RequiresPermission}-Annotation (Pflicht gemaess arch-v2 §8).
 */
@RestController
@RequestMapping("/api/v1/dictionaries")
public class DictionaryCatalogController {

  private final DictionaryCatalogService catalogService;

  /** Konstruktor-Injektion des {@link DictionaryCatalogService}. */
  public DictionaryCatalogController(final DictionaryCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  /**
   * Liefert den vollstaendigen Dictionary-Katalog (alle 6 System-Dictionaries).
   *
   * <p>Sichtbar fuer alle authentifizierten Benutzer mit TASK_READ-Berechtigung.
   * Die Dictionaries sind global (TENANT_ID=0) und fuer alle Mandanten identisch.
   *
   * @return 200 OK mit {@link DictionaryCatalogModel}
   */
  @Operation(
      summary = "Dictionary-Katalog laden",
      description = "Liefert alle 6 System-Dictionaries als einen kompakten Gesamtkatalog:"
          + " taskStatus, taskPriority, taskDirection, notificationType, notificationSeverity, tagSeverity."
          + " Global (TENANT_ID=0), fuer alle Mandanten identisch.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Dictionary-Katalog erfolgreich geladen.",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = DictionaryCatalogModel.class))),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_READ-Berechtigung.")
  })
  @GetMapping
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<DictionaryCatalogModel> listDictionaries() {
    return ResponseEntity.ok(catalogService.list());
  }
}
