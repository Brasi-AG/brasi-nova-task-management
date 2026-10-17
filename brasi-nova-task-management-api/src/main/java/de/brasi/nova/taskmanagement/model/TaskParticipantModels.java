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
 * Auto-generierte DTOs für die Entität TaskParticipant.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskParticipantModels {

  @Relation(collectionRelation = "taskParticipantCollection", itemRelation = "taskParticipantModel")
  @Schema(
    name = "TaskParticipantModel",
    description = """
# TaskParticipantModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskParticipantModel extends BaseModel<TaskParticipantModel> {

  }

  @Getter
  @Setter
  public static class TaskParticipantCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskParticipantUpdateModel extends BaseUpdateModel<TaskParticipantUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskParticipantSingleUpdateModel extends BaseSingleUpdateModel<TaskParticipantSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskParticipantBatchUpdateModel extends BaseBatchUpdateModel<TaskParticipantBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskParticipantPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskParticipant-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskParticipantPatchModel extends BasePatchModel<TaskParticipantPatchModel>{

}

@Relation(collectionRelation = "taskParticipantInfoCollection", itemRelation = "taskParticipantInfoModel")
@Schema(
  name = "TaskParticipantInfoModel",
  description = "Leichtgewichtige Referenz auf TaskParticipant."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskParticipantInfoModel extends InfoModel<TaskParticipantInfoModel> {

}

  @Schema(
    name = "TaskParticipantCollection",
    description = "Sammlung von TaskParticipant-Entitäten mit HATEOAS-Links"
  )
  public static class TaskParticipantCollection extends CollectionModel<TaskParticipantModel> {
  }

  @Schema(
    name = "PagedTaskParticipantCollection",
    description = "Paginierte Sammlung von TaskParticipant-Entitäten"
  )
  public static class PagedTaskParticipantCollection extends PagedModel<TaskParticipantModel> {
  }

  @Schema(
    name = "TaskParticipantSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskParticipant-Ressourcen"
  )
  public static class TaskParticipantSearchResponse extends SearchResponse<TaskParticipantModel> {
  }

}
