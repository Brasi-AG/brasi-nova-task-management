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
 * Auto-generierte DTOs für die Entität TaskEventReceipt.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskEventReceiptModels {

  @Relation(collectionRelation = "taskEventReceiptCollection", itemRelation = "taskEventReceiptModel")
  @Schema(
    name = "TaskEventReceiptModel",
    description = """
# TaskEventReceiptModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskEventReceiptModel extends BaseModel<TaskEventReceiptModel> {

  }

  @Getter
  @Setter
  public static class TaskEventReceiptCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskEventReceiptUpdateModel extends BaseUpdateModel<TaskEventReceiptUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskEventReceiptSingleUpdateModel extends BaseSingleUpdateModel<TaskEventReceiptSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskEventReceiptBatchUpdateModel extends BaseBatchUpdateModel<TaskEventReceiptBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskEventReceiptPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskEventReceipt-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskEventReceiptPatchModel extends BasePatchModel<TaskEventReceiptPatchModel>{

}

@Relation(collectionRelation = "taskEventReceiptInfoCollection", itemRelation = "taskEventReceiptInfoModel")
@Schema(
  name = "TaskEventReceiptInfoModel",
  description = "Leichtgewichtige Referenz auf TaskEventReceipt."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskEventReceiptInfoModel extends InfoModel<TaskEventReceiptInfoModel> {

}

  @Schema(
    name = "TaskEventReceiptCollection",
    description = "Sammlung von TaskEventReceipt-Entitäten mit HATEOAS-Links"
  )
  public static class TaskEventReceiptCollection extends CollectionModel<TaskEventReceiptModel> {
  }

  @Schema(
    name = "PagedTaskEventReceiptCollection",
    description = "Paginierte Sammlung von TaskEventReceipt-Entitäten"
  )
  public static class PagedTaskEventReceiptCollection extends PagedModel<TaskEventReceiptModel> {
  }

  @Schema(
    name = "TaskEventReceiptSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskEventReceipt-Ressourcen"
  )
  public static class TaskEventReceiptSearchResponse extends SearchResponse<TaskEventReceiptModel> {
  }

}
