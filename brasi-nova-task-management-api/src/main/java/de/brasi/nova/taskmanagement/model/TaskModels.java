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
 * Auto-generierte DTOs für die Entität Task.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskModels {

  @Relation(collectionRelation = "taskCollection", itemRelation = "taskModel")
  @Schema(
    name = "TaskModel",
    description = """
# TaskModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskModel extends BaseModel<TaskModel> {

  }

  @Getter
  @Setter
  public static class TaskCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskUpdateModel extends BaseUpdateModel<TaskUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskSingleUpdateModel extends BaseSingleUpdateModel<TaskSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskBatchUpdateModel extends BaseBatchUpdateModel<TaskBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer Task-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskPatchModel extends BasePatchModel<TaskPatchModel>{

}

@Relation(collectionRelation = "taskInfoCollection", itemRelation = "taskInfoModel")
@Schema(
  name = "TaskInfoModel",
  description = "Leichtgewichtige Referenz auf Task."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskInfoModel extends InfoModel<TaskInfoModel> {

}

  @Schema(
    name = "TaskCollection",
    description = "Sammlung von Task-Entitäten mit HATEOAS-Links"
  )
  public static class TaskCollection extends CollectionModel<TaskModel> {
  }

  @Schema(
    name = "PagedTaskCollection",
    description = "Paginierte Sammlung von Task-Entitäten"
  )
  public static class PagedTaskCollection extends PagedModel<TaskModel> {
  }

  @Schema(
    name = "TaskSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer Task-Ressourcen"
  )
  public static class TaskSearchResponse extends SearchResponse<TaskModel> {
  }

}
