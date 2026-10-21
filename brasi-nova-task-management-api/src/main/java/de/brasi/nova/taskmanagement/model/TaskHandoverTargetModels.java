/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.model;

import io.keel.framework.api.service.model.PatchValue;
import io.keel.framework.api.service.model.BaseSingleUpdateModel;
import io.keel.framework.api.service.model.query.SearchResponse;

import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.PagedModel;

import io.keel.framework.api.service.model.BaseBatchUpdateModel;
import io.keel.framework.api.service.model.BaseCreateModel;
import io.keel.framework.api.service.model.BaseModel;
import io.keel.framework.api.service.model.BasePatchModel;
import io.keel.framework.api.service.model.BaseUpdateModel;
import io.keel.framework.api.service.model.CreateModel;
import io.keel.framework.api.service.model.InfoModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.hateoas.server.core.Relation;

/*
 * Auto-generierte DTOs für die Entität TaskHandoverTarget.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskHandoverTargetModels {

  @Relation(collectionRelation = "taskHandoverTargetCollection", itemRelation = "taskHandoverTargetModel")
  @Schema(
    name = "TaskHandoverTargetModel",
    description = """
# TaskHandoverTargetModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskHandoverTargetModel extends BaseModel<TaskHandoverTargetModel> {

  }

  @Getter
  @Setter
  public static class TaskHandoverTargetCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskHandoverTargetUpdateModel extends BaseUpdateModel<TaskHandoverTargetUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskHandoverTargetSingleUpdateModel extends BaseSingleUpdateModel<TaskHandoverTargetSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskHandoverTargetBatchUpdateModel extends BaseBatchUpdateModel<TaskHandoverTargetBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskHandoverTargetPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskHandoverTarget-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskHandoverTargetPatchModel extends BasePatchModel<TaskHandoverTargetPatchModel>{

}

@Relation(collectionRelation = "taskHandoverTargetInfoCollection", itemRelation = "taskHandoverTargetInfoModel")
@Schema(
  name = "TaskHandoverTargetInfoModel",
  description = "Leichtgewichtige Referenz auf TaskHandoverTarget."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskHandoverTargetInfoModel extends InfoModel<TaskHandoverTargetInfoModel> {

}

  @Schema(
    name = "TaskHandoverTargetCollection",
    description = "Sammlung von TaskHandoverTarget-Entitäten mit HATEOAS-Links"
  )
  public static class TaskHandoverTargetCollection extends CollectionModel<TaskHandoverTargetModel> {
  }

  @Schema(
    name = "PagedTaskHandoverTargetCollection",
    description = "Paginierte Sammlung von TaskHandoverTarget-Entitäten"
  )
  public static class PagedTaskHandoverTargetCollection extends PagedModel<TaskHandoverTargetModel> {
  }

  @Schema(
    name = "TaskHandoverTargetSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskHandoverTarget-Ressourcen"
  )
  public static class TaskHandoverTargetSearchResponse extends SearchResponse<TaskHandoverTargetModel> {
  }

}
