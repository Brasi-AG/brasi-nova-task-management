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
 * Auto-generierte DTOs für die Entität TaskHandover.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskHandoverModels {

  @Relation(collectionRelation = "taskHandoverCollection", itemRelation = "taskHandoverModel")
  @Schema(
    name = "TaskHandoverModel",
    description = """
# TaskHandoverModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskHandoverModel extends BaseModel<TaskHandoverModel> {

  }

  @Getter
  @Setter
  public static class TaskHandoverCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskHandoverUpdateModel extends BaseUpdateModel<TaskHandoverUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskHandoverSingleUpdateModel extends BaseSingleUpdateModel<TaskHandoverSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskHandoverBatchUpdateModel extends BaseBatchUpdateModel<TaskHandoverBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskHandoverPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskHandover-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskHandoverPatchModel extends BasePatchModel<TaskHandoverPatchModel>{

}

@Relation(collectionRelation = "taskHandoverInfoCollection", itemRelation = "taskHandoverInfoModel")
@Schema(
  name = "TaskHandoverInfoModel",
  description = "Leichtgewichtige Referenz auf TaskHandover."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskHandoverInfoModel extends InfoModel<TaskHandoverInfoModel> {

}

  @Schema(
    name = "TaskHandoverCollection",
    description = "Sammlung von TaskHandover-Entitäten mit HATEOAS-Links"
  )
  public static class TaskHandoverCollection extends CollectionModel<TaskHandoverModel> {
  }

  @Schema(
    name = "PagedTaskHandoverCollection",
    description = "Paginierte Sammlung von TaskHandover-Entitäten"
  )
  public static class PagedTaskHandoverCollection extends PagedModel<TaskHandoverModel> {
  }

  @Schema(
    name = "TaskHandoverSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskHandover-Ressourcen"
  )
  public static class TaskHandoverSearchResponse extends SearchResponse<TaskHandoverModel> {
  }

}
