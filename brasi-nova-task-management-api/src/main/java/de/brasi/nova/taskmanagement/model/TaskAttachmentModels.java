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
 * Auto-generierte DTOs für die Entität TaskAttachment.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskAttachmentModels {

  @Relation(collectionRelation = "taskAttachmentCollection", itemRelation = "taskAttachmentModel")
  @Schema(
    name = "TaskAttachmentModel",
    description = """
# TaskAttachmentModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskAttachmentModel extends BaseModel<TaskAttachmentModel> {

  }

  @Getter
  @Setter
  public static class TaskAttachmentCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskAttachmentUpdateModel extends BaseUpdateModel<TaskAttachmentUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskAttachmentSingleUpdateModel extends BaseSingleUpdateModel<TaskAttachmentSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskAttachmentBatchUpdateModel extends BaseBatchUpdateModel<TaskAttachmentBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskAttachmentPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskAttachment-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskAttachmentPatchModel extends BasePatchModel<TaskAttachmentPatchModel>{

}

@Relation(collectionRelation = "taskAttachmentInfoCollection", itemRelation = "taskAttachmentInfoModel")
@Schema(
  name = "TaskAttachmentInfoModel",
  description = "Leichtgewichtige Referenz auf TaskAttachment."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskAttachmentInfoModel extends InfoModel<TaskAttachmentInfoModel> {

}

  @Schema(
    name = "TaskAttachmentCollection",
    description = "Sammlung von TaskAttachment-Entitäten mit HATEOAS-Links"
  )
  public static class TaskAttachmentCollection extends CollectionModel<TaskAttachmentModel> {
  }

  @Schema(
    name = "PagedTaskAttachmentCollection",
    description = "Paginierte Sammlung von TaskAttachment-Entitäten"
  )
  public static class PagedTaskAttachmentCollection extends PagedModel<TaskAttachmentModel> {
  }

  @Schema(
    name = "TaskAttachmentSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskAttachment-Ressourcen"
  )
  public static class TaskAttachmentSearchResponse extends SearchResponse<TaskAttachmentModel> {
  }

}
