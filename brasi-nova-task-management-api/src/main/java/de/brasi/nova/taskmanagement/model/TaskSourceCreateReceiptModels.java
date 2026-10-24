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
 * Auto-generierte DTOs für die Entität TaskSourceCreateReceipt.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskSourceCreateReceiptModels {

  @Relation(collectionRelation = "taskSourceCreateReceiptCollection", itemRelation = "taskSourceCreateReceiptModel")
  @Schema(
    name = "TaskSourceCreateReceiptModel",
    description = """
# TaskSourceCreateReceiptModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskSourceCreateReceiptModel extends BaseModel<TaskSourceCreateReceiptModel> {

  }

  @Getter
  @Setter
  public static class TaskSourceCreateReceiptCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskSourceCreateReceiptUpdateModel extends BaseUpdateModel<TaskSourceCreateReceiptUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskSourceCreateReceiptSingleUpdateModel extends BaseSingleUpdateModel<TaskSourceCreateReceiptSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskSourceCreateReceiptBatchUpdateModel extends BaseBatchUpdateModel<TaskSourceCreateReceiptBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskSourceCreateReceiptPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskSourceCreateReceipt-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskSourceCreateReceiptPatchModel extends BasePatchModel<TaskSourceCreateReceiptPatchModel>{

}

@Relation(collectionRelation = "taskSourceCreateReceiptInfoCollection", itemRelation = "taskSourceCreateReceiptInfoModel")
@Schema(
  name = "TaskSourceCreateReceiptInfoModel",
  description = "Leichtgewichtige Referenz auf TaskSourceCreateReceipt."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskSourceCreateReceiptInfoModel extends InfoModel<TaskSourceCreateReceiptInfoModel> {

}

  @Schema(
    name = "TaskSourceCreateReceiptCollection",
    description = "Sammlung von TaskSourceCreateReceipt-Entitäten mit HATEOAS-Links"
  )
  public static class TaskSourceCreateReceiptCollection extends CollectionModel<TaskSourceCreateReceiptModel> {
  }

  @Schema(
    name = "PagedTaskSourceCreateReceiptCollection",
    description = "Paginierte Sammlung von TaskSourceCreateReceipt-Entitäten"
  )
  public static class PagedTaskSourceCreateReceiptCollection extends PagedModel<TaskSourceCreateReceiptModel> {
  }

  @Schema(
    name = "TaskSourceCreateReceiptSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskSourceCreateReceipt-Ressourcen"
  )
  public static class TaskSourceCreateReceiptSearchResponse extends SearchResponse<TaskSourceCreateReceiptModel> {
  }

}
