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
 * Auto-generierte DTOs für die Entität TaskHistoryEntry.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TaskHistoryEntryModels {

  @Relation(collectionRelation = "taskHistoryEntryCollection", itemRelation = "taskHistoryEntryModel")
  @Schema(
    name = "TaskHistoryEntryModel",
    description = """
# TaskHistoryEntryModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TaskHistoryEntryModel extends BaseModel<TaskHistoryEntryModel> {

  }

  @Getter
  @Setter
  public static class TaskHistoryEntryCreateModel extends BaseCreateModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TaskHistoryEntryUpdateModel extends BaseUpdateModel<TaskHistoryEntryUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskHistoryEntrySingleUpdateModel extends BaseSingleUpdateModel<TaskHistoryEntrySingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TaskHistoryEntryBatchUpdateModel extends BaseBatchUpdateModel<TaskHistoryEntryBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TaskHistoryEntryPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TaskHistoryEntry-Entität. Nur gesetzte Felder werden geändert.")
  public static class TaskHistoryEntryPatchModel extends BasePatchModel<TaskHistoryEntryPatchModel>{

}

@Relation(collectionRelation = "taskHistoryEntryInfoCollection", itemRelation = "taskHistoryEntryInfoModel")
@Schema(
  name = "TaskHistoryEntryInfoModel",
  description = "Leichtgewichtige Referenz auf TaskHistoryEntry."
)
@Getter
@Setter
@NoArgsConstructor
public static class TaskHistoryEntryInfoModel extends InfoModel<TaskHistoryEntryInfoModel> {

}

  @Schema(
    name = "TaskHistoryEntryCollection",
    description = "Sammlung von TaskHistoryEntry-Entitäten mit HATEOAS-Links"
  )
  public static class TaskHistoryEntryCollection extends CollectionModel<TaskHistoryEntryModel> {
  }

  @Schema(
    name = "PagedTaskHistoryEntryCollection",
    description = "Paginierte Sammlung von TaskHistoryEntry-Entitäten"
  )
  public static class PagedTaskHistoryEntryCollection extends PagedModel<TaskHistoryEntryModel> {
  }

  @Schema(
    name = "TaskHistoryEntrySearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TaskHistoryEntry-Ressourcen"
  )
  public static class TaskHistoryEntrySearchResponse extends SearchResponse<TaskHistoryEntryModel> {
  }

}
