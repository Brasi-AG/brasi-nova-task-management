/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.model;

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
 * Auto-generierte DTOs für die Entität TaskTag.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskTagModels {

  @Relation(collectionRelation = "taskTagCollection", itemRelation = "taskTagModel")
  @Schema(
    name = "TaskTagModel",
    description = """
# TaskTagModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskTagModel extends BaseDictionaryModel<TaskTagModel> {

  }

  @Getter
  @Setter
  public static class TaskTagCreateModel extends BaseCreateDictionaryModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskTagUpdateModel extends BaseUpdateDictionaryModel<TaskTagUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskTagSingleUpdateModel extends BaseSingleUpdateDictionaryModel<TaskTagSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskTagBatchUpdateModel extends BaseBatchUpdateValidModel<TaskTagBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskTagPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskTag-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskTagPatchModel extends BasePatchDictionaryModel<TaskTagPatchModel>{

}

@Relation(collectionRelation = "taskTagInfoCollection", itemRelation = "taskTagInfoModel")
@Schema(
  name = "TaskTagInfoModel",
  description = "Leichtgewichtige Referenz auf TaskTag."
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public static class TaskTagInfoModel extends InfoModel<TaskTagInfoModel> {






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
    name = "TaskTagCollection",
    description = "Sammlung von TaskTag-Entitäten mit HATEOAS-Links"
  )
  public static class TaskTagCollection extends CollectionModel<TaskTagModel> {
  }

  @Schema(
    name = "PagedTaskTagCollection",
    description = "Paginierte Sammlung von TaskTag-Entitäten"
  )
  public static class PagedTaskTagCollection extends PagedModel<TaskTagModel> {
  }

  @Schema(
    name = "TaskTagSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskTag-Ressourcen"
  )
  public static class TaskTagSearchResponse extends SearchResponse<TaskTagModel> {
  }

}
