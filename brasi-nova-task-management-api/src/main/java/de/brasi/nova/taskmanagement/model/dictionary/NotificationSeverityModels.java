/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.model.dictionary;

import io.keel.framework.api.service.model.PatchValue;
import io.keel.framework.api.service.model.BaseSingleUpdateModel;
import io.keel.framework.api.service.model.query.SearchResponse;

import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.PagedModel;

import io.keel.framework.api.service.model.BaseBatchUpdateValidModel;
import io.keel.framework.api.service.model.BaseCreateDictionaryModel;
import io.keel.framework.api.service.model.BaseDictionaryModel;
import io.keel.framework.api.service.model.BasePatchDictionaryModel;
import io.keel.framework.api.service.model.BaseSingleUpdateDictionaryModel;
import io.keel.framework.api.service.model.BaseUpdateDictionaryModel;
import io.keel.framework.api.service.model.CreateModel;
import io.keel.framework.api.service.model.InfoModel;
import io.keel.framework.api.service.model.InfoSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.hateoas.server.core.Relation;

/*
 * Auto-generierte DTOs für die Entität NotificationSeverity.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class NotificationSeverityModels {

  @Relation(collectionRelation = "notificationSeverityCollection", itemRelation = "notificationSeverityModel")
  @Schema(
    name = "NotificationSeverityModel",
    description = """
# NotificationSeverityModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class NotificationSeverityModel extends BaseDictionaryModel<NotificationSeverityModel> {

  }

  @Getter
  @Setter
  public static class NotificationSeverityCreateModel extends BaseCreateDictionaryModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class NotificationSeverityUpdateModel extends BaseUpdateDictionaryModel<NotificationSeverityUpdateModel> {

  }

  @Getter
  @Setter
  public static class NotificationSeveritySingleUpdateModel extends BaseSingleUpdateDictionaryModel<NotificationSeveritySingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class NotificationSeverityBatchUpdateModel extends BaseBatchUpdateValidModel<NotificationSeverityBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "NotificationSeverityPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer NotificationSeverity-Entität. Nur gesetzte Felder werden geändert.")
  public static class NotificationSeverityPatchModel extends BasePatchDictionaryModel<NotificationSeverityPatchModel>{

}

@Relation(collectionRelation = "notificationSeverityInfoCollection", itemRelation = "notificationSeverityInfoModel")
@Schema(
  name = "NotificationSeverityInfoModel",
  description = "Leichtgewichtige Referenz auf NotificationSeverity."
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public static class NotificationSeverityInfoModel extends InfoModel<NotificationSeverityInfoModel> {






    @Schema(
      requiredMode = Schema.RequiredMode.NOT_REQUIRED,
      description = "Der Typ des Wörterbuch-Eintrags.",
      example = "SYSTEM_PUBLIC"
    )
    @InfoSource("dictionaryEntryType")
    private String dictionaryEntryType;






    @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      description = "Der Eintrag des Wörterbuchs. Darf nicht null oder leer sein.",
      example = "Beispiel-Eintrag"
    )
    @InfoSource("entryValue")
    private String entry;

}

  @Schema(
    name = "NotificationSeverityCollection",
    description = "Sammlung von NotificationSeverity-Entitäten mit HATEOAS-Links"
  )
  public static class NotificationSeverityCollection extends CollectionModel<NotificationSeverityModel> {
  }

  @Schema(
    name = "PagedNotificationSeverityCollection",
    description = "Paginierte Sammlung von NotificationSeverity-Entitäten"
  )
  public static class PagedNotificationSeverityCollection extends PagedModel<NotificationSeverityModel> {
  }

  @Schema(
    name = "NotificationSeveritySearchResponse",
    description = "Keyset-paginierte Suchantwort fuer NotificationSeverity-Ressourcen"
  )
  public static class NotificationSeveritySearchResponse extends SearchResponse<NotificationSeverityModel> {
  }

}
