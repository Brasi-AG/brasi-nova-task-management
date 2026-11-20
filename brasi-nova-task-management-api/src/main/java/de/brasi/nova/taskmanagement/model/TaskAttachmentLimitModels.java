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
 * Auto-generierte DTOs für die Entität TaskAttachmentLimit.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskAttachmentLimitModels {

  @Relation(collectionRelation = "taskAttachmentLimitCollection", itemRelation = "taskAttachmentLimitModel")
  @Schema(
    name = "TaskAttachmentLimitModel",
    description = """
# TaskAttachmentLimitModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskAttachmentLimitModel extends BaseModel<TaskAttachmentLimitModel> {

  }

  @Getter
  @Setter
  public static class TaskAttachmentLimitCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskAttachmentLimitUpdateModel extends BaseUpdateModel<TaskAttachmentLimitUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskAttachmentLimitSingleUpdateModel extends BaseSingleUpdateModel<TaskAttachmentLimitSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskAttachmentLimitBatchUpdateModel extends BaseBatchUpdateModel<TaskAttachmentLimitBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskAttachmentLimitPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskAttachmentLimit-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskAttachmentLimitPatchModel extends BasePatchModel<TaskAttachmentLimitPatchModel>{

}

@Relation(collectionRelation = "taskAttachmentLimitInfoCollection", itemRelation = "taskAttachmentLimitInfoModel")
@Schema(
  name = "TaskAttachmentLimitInfoModel",
  description = "Leichtgewichtige Referenz auf TaskAttachmentLimit."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskAttachmentLimitInfoModel extends InfoModel<TaskAttachmentLimitInfoModel> {

}

  @Schema(
    name = "TaskAttachmentLimitCollection",
    description = "Sammlung von TaskAttachmentLimit-Entitäten mit HATEOAS-Links"
  )
  public static class TaskAttachmentLimitCollection extends CollectionModel<TaskAttachmentLimitModel> {
  }

  @Schema(
    name = "PagedTaskAttachmentLimitCollection",
    description = "Paginierte Sammlung von TaskAttachmentLimit-Entitäten"
  )
  public static class PagedTaskAttachmentLimitCollection extends PagedModel<TaskAttachmentLimitModel> {
  }

  @Schema(
    name = "TaskAttachmentLimitSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskAttachmentLimit-Ressourcen"
  )
  public static class TaskAttachmentLimitSearchResponse extends SearchResponse<TaskAttachmentLimitModel> {
  }

}
