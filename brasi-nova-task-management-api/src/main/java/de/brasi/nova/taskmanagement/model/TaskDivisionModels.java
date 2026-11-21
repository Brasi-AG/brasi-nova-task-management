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
 * Auto-generierte DTOs für die Entität TaskDivision.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskDivisionModels {

  @Relation(collectionRelation = "taskDivisionCollection", itemRelation = "taskDivisionModel")
  @Schema(
    name = "TaskDivisionModel",
    description = """
# TaskDivisionModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskDivisionModel extends BaseModel<TaskDivisionModel> {

  }

  @Getter
  @Setter
  public static class TaskDivisionCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskDivisionUpdateModel extends BaseUpdateModel<TaskDivisionUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskDivisionSingleUpdateModel extends BaseSingleUpdateModel<TaskDivisionSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskDivisionBatchUpdateModel extends BaseBatchUpdateModel<TaskDivisionBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskDivisionPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskDivision-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskDivisionPatchModel extends BasePatchModel<TaskDivisionPatchModel>{

}

@Relation(collectionRelation = "taskDivisionInfoCollection", itemRelation = "taskDivisionInfoModel")
@Schema(
  name = "TaskDivisionInfoModel",
  description = "Leichtgewichtige Referenz auf TaskDivision."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskDivisionInfoModel extends InfoModel<TaskDivisionInfoModel> {

}

  @Schema(
    name = "TaskDivisionCollection",
    description = "Sammlung von TaskDivision-Entitäten mit HATEOAS-Links"
  )
  public static class TaskDivisionCollection extends CollectionModel<TaskDivisionModel> {
  }

  @Schema(
    name = "PagedTaskDivisionCollection",
    description = "Paginierte Sammlung von TaskDivision-Entitäten"
  )
  public static class PagedTaskDivisionCollection extends PagedModel<TaskDivisionModel> {
  }

  @Schema(
    name = "TaskDivisionSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskDivision-Ressourcen"
  )
  public static class TaskDivisionSearchResponse extends SearchResponse<TaskDivisionModel> {
  }

}
