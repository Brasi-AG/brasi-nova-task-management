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
 * Auto-generierte DTOs für die Entität TaskInboxGeneration.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskInboxGenerationModels {

  @Relation(collectionRelation = "taskInboxGenerationCollection", itemRelation = "taskInboxGenerationModel")
  @Schema(
    name = "TaskInboxGenerationModel",
    description = """
# TaskInboxGenerationModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskInboxGenerationModel extends BaseModel<TaskInboxGenerationModel> {

  }

  @Getter
  @Setter
  public static class TaskInboxGenerationCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskInboxGenerationUpdateModel extends BaseUpdateModel<TaskInboxGenerationUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskInboxGenerationSingleUpdateModel extends BaseSingleUpdateModel<TaskInboxGenerationSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskInboxGenerationBatchUpdateModel extends BaseBatchUpdateModel<TaskInboxGenerationBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskInboxGenerationPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskInboxGeneration-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskInboxGenerationPatchModel extends BasePatchModel<TaskInboxGenerationPatchModel>{

}

@Relation(collectionRelation = "taskInboxGenerationInfoCollection", itemRelation = "taskInboxGenerationInfoModel")
@Schema(
  name = "TaskInboxGenerationInfoModel",
  description = "Leichtgewichtige Referenz auf TaskInboxGeneration."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskInboxGenerationInfoModel extends InfoModel<TaskInboxGenerationInfoModel> {

}

  @Schema(
    name = "TaskInboxGenerationCollection",
    description = "Sammlung von TaskInboxGeneration-Entitäten mit HATEOAS-Links"
  )
  public static class TaskInboxGenerationCollection extends CollectionModel<TaskInboxGenerationModel> {
  }

  @Schema(
    name = "PagedTaskInboxGenerationCollection",
    description = "Paginierte Sammlung von TaskInboxGeneration-Entitäten"
  )
  public static class PagedTaskInboxGenerationCollection extends PagedModel<TaskInboxGenerationModel> {
  }

  @Schema(
    name = "TaskInboxGenerationSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskInboxGeneration-Ressourcen"
  )
  public static class TaskInboxGenerationSearchResponse extends SearchResponse<TaskInboxGenerationModel> {
  }

}
