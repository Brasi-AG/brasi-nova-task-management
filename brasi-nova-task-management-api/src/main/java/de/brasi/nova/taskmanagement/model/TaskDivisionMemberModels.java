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
 * Auto-generierte DTOs für die Entität TaskDivisionMember.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskDivisionMemberModels {

  @Relation(collectionRelation = "taskDivisionMemberCollection", itemRelation = "taskDivisionMemberModel")
  @Schema(
    name = "TaskDivisionMemberModel",
    description = """
# TaskDivisionMemberModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskDivisionMemberModel extends BaseModel<TaskDivisionMemberModel> {

  }

  @Getter
  @Setter
  public static class TaskDivisionMemberCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskDivisionMemberUpdateModel extends BaseUpdateModel<TaskDivisionMemberUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskDivisionMemberSingleUpdateModel extends BaseSingleUpdateModel<TaskDivisionMemberSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskDivisionMemberBatchUpdateModel extends BaseBatchUpdateModel<TaskDivisionMemberBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskDivisionMemberPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskDivisionMember-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskDivisionMemberPatchModel extends BasePatchModel<TaskDivisionMemberPatchModel>{

}

@Relation(collectionRelation = "taskDivisionMemberInfoCollection", itemRelation = "taskDivisionMemberInfoModel")
@Schema(
  name = "TaskDivisionMemberInfoModel",
  description = "Leichtgewichtige Referenz auf TaskDivisionMember."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskDivisionMemberInfoModel extends InfoModel<TaskDivisionMemberInfoModel> {

}

  @Schema(
    name = "TaskDivisionMemberCollection",
    description = "Sammlung von TaskDivisionMember-Entitäten mit HATEOAS-Links"
  )
  public static class TaskDivisionMemberCollection extends CollectionModel<TaskDivisionMemberModel> {
  }

  @Schema(
    name = "PagedTaskDivisionMemberCollection",
    description = "Paginierte Sammlung von TaskDivisionMember-Entitäten"
  )
  public static class PagedTaskDivisionMemberCollection extends PagedModel<TaskDivisionMemberModel> {
  }

  @Schema(
    name = "TaskDivisionMemberSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskDivisionMember-Ressourcen"
  )
  public static class TaskDivisionMemberSearchResponse extends SearchResponse<TaskDivisionMemberModel> {
  }

}
