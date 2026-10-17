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
 * Auto-generierte DTOs für die Entität TaskComment.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskCommentModels {

  @Relation(collectionRelation = "taskCommentCollection", itemRelation = "taskCommentModel")
  @Schema(
    name = "TaskCommentModel",
    description = """
# TaskCommentModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskCommentModel extends BaseModel<TaskCommentModel> {

  }

  @Getter
  @Setter
  public static class TaskCommentCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskCommentUpdateModel extends BaseUpdateModel<TaskCommentUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskCommentSingleUpdateModel extends BaseSingleUpdateModel<TaskCommentSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskCommentBatchUpdateModel extends BaseBatchUpdateModel<TaskCommentBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskCommentPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskComment-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskCommentPatchModel extends BasePatchModel<TaskCommentPatchModel>{

}

@Relation(collectionRelation = "taskCommentInfoCollection", itemRelation = "taskCommentInfoModel")
@Schema(
  name = "TaskCommentInfoModel",
  description = "Leichtgewichtige Referenz auf TaskComment."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskCommentInfoModel extends InfoModel<TaskCommentInfoModel> {

}

  @Schema(
    name = "TaskCommentCollection",
    description = "Sammlung von TaskComment-Entitäten mit HATEOAS-Links"
  )
  public static class TaskCommentCollection extends CollectionModel<TaskCommentModel> {
  }

  @Schema(
    name = "PagedTaskCommentCollection",
    description = "Paginierte Sammlung von TaskComment-Entitäten"
  )
  public static class PagedTaskCommentCollection extends PagedModel<TaskCommentModel> {
  }

  @Schema(
    name = "TaskCommentSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskComment-Ressourcen"
  )
  public static class TaskCommentSearchResponse extends SearchResponse<TaskCommentModel> {
  }

}
