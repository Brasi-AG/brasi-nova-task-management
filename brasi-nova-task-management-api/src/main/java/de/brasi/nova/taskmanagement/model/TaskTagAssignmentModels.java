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
 * Auto-generierte DTOs für die Entität TaskTagAssignment.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskTagAssignmentModels {

  @Relation(collectionRelation = "taskTagAssignmentCollection", itemRelation = "taskTagAssignmentModel")
  @Schema(
    name = "TaskTagAssignmentModel",
    description = """
# TaskTagAssignmentModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskTagAssignmentModel extends BaseModel<TaskTagAssignmentModel> {

  }

  @Getter
  @Setter
  public static class TaskTagAssignmentCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskTagAssignmentUpdateModel extends BaseUpdateModel<TaskTagAssignmentUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskTagAssignmentSingleUpdateModel extends BaseSingleUpdateModel<TaskTagAssignmentSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskTagAssignmentBatchUpdateModel extends BaseBatchUpdateModel<TaskTagAssignmentBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskTagAssignmentPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskTagAssignment-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskTagAssignmentPatchModel extends BasePatchModel<TaskTagAssignmentPatchModel>{

}

@Relation(collectionRelation = "taskTagAssignmentInfoCollection", itemRelation = "taskTagAssignmentInfoModel")
@Schema(
  name = "TaskTagAssignmentInfoModel",
  description = "Leichtgewichtige Referenz auf TaskTagAssignment."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskTagAssignmentInfoModel extends InfoModel<TaskTagAssignmentInfoModel> {

}

  @Schema(
    name = "TaskTagAssignmentCollection",
    description = "Sammlung von TaskTagAssignment-Entitäten mit HATEOAS-Links"
  )
  public static class TaskTagAssignmentCollection extends CollectionModel<TaskTagAssignmentModel> {
  }

  @Schema(
    name = "PagedTaskTagAssignmentCollection",
    description = "Paginierte Sammlung von TaskTagAssignment-Entitäten"
  )
  public static class PagedTaskTagAssignmentCollection extends PagedModel<TaskTagAssignmentModel> {
  }

  @Schema(
    name = "TaskTagAssignmentSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskTagAssignment-Ressourcen"
  )
  public static class TaskTagAssignmentSearchResponse extends SearchResponse<TaskTagAssignmentModel> {
  }

}
