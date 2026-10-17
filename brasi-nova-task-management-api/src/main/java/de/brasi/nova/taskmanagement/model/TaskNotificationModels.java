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
 * Auto-generierte DTOs für die Entität TaskNotification.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskNotificationModels {

  @Relation(collectionRelation = "taskNotificationCollection", itemRelation = "taskNotificationModel")
  @Schema(
    name = "TaskNotificationModel",
    description = """
# TaskNotificationModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskNotificationModel extends BaseModel<TaskNotificationModel> {

  }

  @Getter
  @Setter
  public static class TaskNotificationCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskNotificationUpdateModel extends BaseUpdateModel<TaskNotificationUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskNotificationSingleUpdateModel extends BaseSingleUpdateModel<TaskNotificationSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskNotificationBatchUpdateModel extends BaseBatchUpdateModel<TaskNotificationBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskNotificationPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskNotification-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskNotificationPatchModel extends BasePatchModel<TaskNotificationPatchModel>{

}

@Relation(collectionRelation = "taskNotificationInfoCollection", itemRelation = "taskNotificationInfoModel")
@Schema(
  name = "TaskNotificationInfoModel",
  description = "Leichtgewichtige Referenz auf TaskNotification."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskNotificationInfoModel extends InfoModel<TaskNotificationInfoModel> {

}

  @Schema(
    name = "TaskNotificationCollection",
    description = "Sammlung von TaskNotification-Entitäten mit HATEOAS-Links"
  )
  public static class TaskNotificationCollection extends CollectionModel<TaskNotificationModel> {
  }

  @Schema(
    name = "PagedTaskNotificationCollection",
    description = "Paginierte Sammlung von TaskNotification-Entitäten"
  )
  public static class PagedTaskNotificationCollection extends PagedModel<TaskNotificationModel> {
  }

  @Schema(
    name = "TaskNotificationSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskNotification-Ressourcen"
  )
  public static class TaskNotificationSearchResponse extends SearchResponse<TaskNotificationModel> {
  }

}
