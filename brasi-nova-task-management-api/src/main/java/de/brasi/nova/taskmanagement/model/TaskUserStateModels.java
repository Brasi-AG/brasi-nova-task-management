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
 * Auto-generierte DTOs für die Entität TaskUserState.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskUserStateModels {

  @Relation(collectionRelation = "taskUserStateCollection", itemRelation = "taskUserStateModel")
  @Schema(
    name = "TaskUserStateModel",
    description = """
# TaskUserStateModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskUserStateModel extends BaseModel<TaskUserStateModel> {

  }

  @Getter
  @Setter
  public static class TaskUserStateCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskUserStateUpdateModel extends BaseUpdateModel<TaskUserStateUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskUserStateSingleUpdateModel extends BaseSingleUpdateModel<TaskUserStateSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskUserStateBatchUpdateModel extends BaseBatchUpdateModel<TaskUserStateBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskUserStatePatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskUserState-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskUserStatePatchModel extends BasePatchModel<TaskUserStatePatchModel>{

}

@Relation(collectionRelation = "taskUserStateInfoCollection", itemRelation = "taskUserStateInfoModel")
@Schema(
  name = "TaskUserStateInfoModel",
  description = "Leichtgewichtige Referenz auf TaskUserState."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskUserStateInfoModel extends InfoModel<TaskUserStateInfoModel> {

}

  @Schema(
    name = "TaskUserStateCollection",
    description = "Sammlung von TaskUserState-Entitäten mit HATEOAS-Links"
  )
  public static class TaskUserStateCollection extends CollectionModel<TaskUserStateModel> {
  }

  @Schema(
    name = "PagedTaskUserStateCollection",
    description = "Paginierte Sammlung von TaskUserState-Entitäten"
  )
  public static class PagedTaskUserStateCollection extends PagedModel<TaskUserStateModel> {
  }

  @Schema(
    name = "TaskUserStateSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskUserState-Ressourcen"
  )
  public static class TaskUserStateSearchResponse extends SearchResponse<TaskUserStateModel> {
  }

}
