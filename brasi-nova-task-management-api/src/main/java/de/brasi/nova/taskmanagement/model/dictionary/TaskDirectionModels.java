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
 * Auto-generierte DTOs für die Entität TaskDirection.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskDirectionModels {

  @Relation(collectionRelation = "taskDirectionCollection", itemRelation = "taskDirectionModel")
  @Schema(
    name = "TaskDirectionModel",
    description = """
# TaskDirectionModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskDirectionModel extends BaseDictionaryModel<TaskDirectionModel> {

  }

  @Getter
  @Setter
  public static class TaskDirectionCreateModel extends BaseCreateDictionaryModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskDirectionUpdateModel extends BaseUpdateDictionaryModel<TaskDirectionUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskDirectionSingleUpdateModel extends BaseSingleUpdateDictionaryModel<TaskDirectionSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskDirectionBatchUpdateModel extends BaseBatchUpdateValidModel<TaskDirectionBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskDirectionPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskDirection-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskDirectionPatchModel extends BasePatchDictionaryModel<TaskDirectionPatchModel>{

}

@Relation(collectionRelation = "taskDirectionInfoCollection", itemRelation = "taskDirectionInfoModel")
@Schema(
  name = "TaskDirectionInfoModel",
  description = "Leichtgewichtige Referenz auf TaskDirection."
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public static class TaskDirectionInfoModel extends InfoModel<TaskDirectionInfoModel> {






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
    name = "TaskDirectionCollection",
    description = "Sammlung von TaskDirection-Entitäten mit HATEOAS-Links"
  )
  public static class TaskDirectionCollection extends CollectionModel<TaskDirectionModel> {
  }

  @Schema(
    name = "PagedTaskDirectionCollection",
    description = "Paginierte Sammlung von TaskDirection-Entitäten"
  )
  public static class PagedTaskDirectionCollection extends PagedModel<TaskDirectionModel> {
  }

  @Schema(
    name = "TaskDirectionSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskDirection-Ressourcen"
  )
  public static class TaskDirectionSearchResponse extends SearchResponse<TaskDirectionModel> {
  }

}
