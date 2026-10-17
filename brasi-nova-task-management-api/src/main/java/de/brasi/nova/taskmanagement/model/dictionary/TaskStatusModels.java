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
 * Auto-generierte DTOs für die Entität TaskStatus.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskStatusModels {

  @Relation(collectionRelation = "taskStatusCollection", itemRelation = "taskStatusModel")
  @Schema(
    name = "TaskStatusModel",
    description = """
# TaskStatusModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskStatusModel extends BaseDictionaryModel<TaskStatusModel> {

  }

  @Getter
  @Setter
  public static class TaskStatusCreateModel extends BaseCreateDictionaryModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskStatusUpdateModel extends BaseUpdateDictionaryModel<TaskStatusUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskStatusSingleUpdateModel extends BaseSingleUpdateDictionaryModel<TaskStatusSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskStatusBatchUpdateModel extends BaseBatchUpdateValidModel<TaskStatusBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskStatusPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskStatus-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskStatusPatchModel extends BasePatchDictionaryModel<TaskStatusPatchModel>{

}

@Relation(collectionRelation = "taskStatusInfoCollection", itemRelation = "taskStatusInfoModel")
@Schema(
  name = "TaskStatusInfoModel",
  description = "Leichtgewichtige Referenz auf TaskStatus."
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public static class TaskStatusInfoModel extends InfoModel<TaskStatusInfoModel> {






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
    name = "TaskStatusCollection",
    description = "Sammlung von TaskStatus-Entitäten mit HATEOAS-Links"
  )
  public static class TaskStatusCollection extends CollectionModel<TaskStatusModel> {
  }

  @Schema(
    name = "PagedTaskStatusCollection",
    description = "Paginierte Sammlung von TaskStatus-Entitäten"
  )
  public static class PagedTaskStatusCollection extends PagedModel<TaskStatusModel> {
  }

  @Schema(
    name = "TaskStatusSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskStatus-Ressourcen"
  )
  public static class TaskStatusSearchResponse extends SearchResponse<TaskStatusModel> {
  }

}
