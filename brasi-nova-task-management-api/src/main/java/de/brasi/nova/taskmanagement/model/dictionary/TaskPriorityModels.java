/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.model.dictionary;

import io.keel.framework.api.service.model.PatchValue;
import io.keel.framework.api.service.model.BaseSingleUpdateModel;
import io.keel.framework.api.service.model.query.SearchResponse;

import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.PagedModel;

import io.keel.framework.api.service.model.BaseBatchUpdateValidModel;
import io.keel.framework.api.service.model.BaseCreateDictionaryModel;
import io.keel.framework.api.service.model.BaseDictionaryModel;
import io.keel.framework.api.service.model.BasePatchDictionaryModel;
import io.keel.framework.api.service.model.BaseSingleUpdateDictionaryModel;
import io.keel.framework.api.service.model.BaseUpdateDictionaryModel;
import io.keel.framework.api.service.model.CreateModel;
import io.keel.framework.api.service.model.InfoModel;
import io.keel.framework.api.service.model.InfoSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.hateoas.server.core.Relation;

/*
 * Auto-generierte DTOs für die Entität TaskPriority.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskPriorityModels {

  @Relation(collectionRelation = "taskPriorityCollection", itemRelation = "taskPriorityModel")
  @Schema(
    name = "TaskPriorityModel",
    description = """
# TaskPriorityModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskPriorityModel extends BaseDictionaryModel<TaskPriorityModel> {

  }

  @Getter
  @Setter
  public static class TaskPriorityCreateModel extends BaseCreateDictionaryModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskPriorityUpdateModel extends BaseUpdateDictionaryModel<TaskPriorityUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskPrioritySingleUpdateModel extends BaseSingleUpdateDictionaryModel<TaskPrioritySingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskPriorityBatchUpdateModel extends BaseBatchUpdateValidModel<TaskPriorityBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskPriorityPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskPriority-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskPriorityPatchModel extends BasePatchDictionaryModel<TaskPriorityPatchModel>{

}

@Relation(collectionRelation = "taskPriorityInfoCollection", itemRelation = "taskPriorityInfoModel")
@Schema(
  name = "TaskPriorityInfoModel",
  description = "Leichtgewichtige Referenz auf TaskPriority."
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public static class TaskPriorityInfoModel extends InfoModel<TaskPriorityInfoModel> {






    @Schema(
      requiredMode = Schema.RequiredMode.NOT_REQUIRED,
      description = "Der Typ des Wörterbuch-Eintrags.",
      example = "SYSTEM_PUBLIC"
    )
    @InfoSource("dictionaryEntryType")
    private String dictionaryEntryType;






    @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      description = "Der Eintrag des Wörterbuchs. Darf nicht null oder leer sein.",
      example = "Beispiel-Eintrag"
    )
    @InfoSource("entryValue")
    private String entry;

}

  @Schema(
    name = "TaskPriorityCollection",
    description = "Sammlung von TaskPriority-Entitäten mit HATEOAS-Links"
  )
  public static class TaskPriorityCollection extends CollectionModel<TaskPriorityModel> {
  }

  @Schema(
    name = "PagedTaskPriorityCollection",
    description = "Paginierte Sammlung von TaskPriority-Entitäten"
  )
  public static class PagedTaskPriorityCollection extends PagedModel<TaskPriorityModel> {
  }

  @Schema(
    name = "TaskPrioritySearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskPriority-Ressourcen"
  )
  public static class TaskPrioritySearchResponse extends SearchResponse<TaskPriorityModel> {
  }

}
