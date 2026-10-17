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
 * Auto-generierte DTOs für die Entität TaskReminderReceipt.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskReminderReceiptModels {

  @Relation(collectionRelation = "taskReminderReceiptCollection", itemRelation = "taskReminderReceiptModel")
  @Schema(
    name = "TaskReminderReceiptModel",
    description = """
# TaskReminderReceiptModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskReminderReceiptModel extends BaseModel<TaskReminderReceiptModel> {

  }

  @Getter
  @Setter
  public static class TaskReminderReceiptCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskReminderReceiptUpdateModel extends BaseUpdateModel<TaskReminderReceiptUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskReminderReceiptSingleUpdateModel extends BaseSingleUpdateModel<TaskReminderReceiptSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskReminderReceiptBatchUpdateModel extends BaseBatchUpdateModel<TaskReminderReceiptBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskReminderReceiptPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskReminderReceipt-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskReminderReceiptPatchModel extends BasePatchModel<TaskReminderReceiptPatchModel>{

}

@Relation(collectionRelation = "taskReminderReceiptInfoCollection", itemRelation = "taskReminderReceiptInfoModel")
@Schema(
  name = "TaskReminderReceiptInfoModel",
  description = "Leichtgewichtige Referenz auf TaskReminderReceipt."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskReminderReceiptInfoModel extends InfoModel<TaskReminderReceiptInfoModel> {

}

  @Schema(
    name = "TaskReminderReceiptCollection",
    description = "Sammlung von TaskReminderReceipt-Entitäten mit HATEOAS-Links"
  )
  public static class TaskReminderReceiptCollection extends CollectionModel<TaskReminderReceiptModel> {
  }

  @Schema(
    name = "PagedTaskReminderReceiptCollection",
    description = "Paginierte Sammlung von TaskReminderReceipt-Entitäten"
  )
  public static class PagedTaskReminderReceiptCollection extends PagedModel<TaskReminderReceiptModel> {
  }

  @Schema(
    name = "TaskReminderReceiptSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskReminderReceipt-Ressourcen"
  )
  public static class TaskReminderReceiptSearchResponse extends SearchResponse<TaskReminderReceiptModel> {
  }

}
