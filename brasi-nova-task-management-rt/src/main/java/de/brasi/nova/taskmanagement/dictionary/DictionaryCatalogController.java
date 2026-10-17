package de.brasi.nova.taskmanagement.dictionary;

import de.brasi.nova.taskmanagement.dictionary.model.DictionaryCatalogModel;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
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
  @GetMapping
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<DictionaryCatalogModel> listDictionaries() {
    return ResponseEntity.ok(catalogService.list());
  }
}
