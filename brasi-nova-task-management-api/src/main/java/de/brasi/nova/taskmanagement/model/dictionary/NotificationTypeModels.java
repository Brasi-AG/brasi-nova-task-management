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
 * Auto-generierte DTOs für die Entität NotificationType.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class NotificationTypeModels {

  @Relation(collectionRelation = "notificationTypeCollection", itemRelation = "notificationTypeModel")
  @Schema(
    name = "NotificationTypeModel",
    description = """
# NotificationTypeModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class NotificationTypeModel extends BaseDictionaryModel<NotificationTypeModel> {

  }

  @Getter
  @Setter
  public static class NotificationTypeCreateModel extends BaseCreateDictionaryModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class NotificationTypeUpdateModel extends BaseUpdateDictionaryModel<NotificationTypeUpdateModel> {

  }

  @Getter
  @Setter
  public static class NotificationTypeSingleUpdateModel extends BaseSingleUpdateDictionaryModel<NotificationTypeSingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class NotificationTypeBatchUpdateModel extends BaseBatchUpdateValidModel<NotificationTypeBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "NotificationTypePatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer NotificationType-Entität. Nur gesetzte Felder werden geändert.")
  public static class NotificationTypePatchModel extends BasePatchDictionaryModel<NotificationTypePatchModel>{

}

@Relation(collectionRelation = "notificationTypeInfoCollection", itemRelation = "notificationTypeInfoModel")
@Schema(
  name = "NotificationTypeInfoModel",
  description = "Leichtgewichtige Referenz auf NotificationType."
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public static class NotificationTypeInfoModel extends InfoModel<NotificationTypeInfoModel> {






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
    name = "NotificationTypeCollection",
    description = "Sammlung von NotificationType-Entitäten mit HATEOAS-Links"
  )
  public static class NotificationTypeCollection extends CollectionModel<NotificationTypeModel> {
  }

  @Schema(
    name = "PagedNotificationTypeCollection",
    description = "Paginierte Sammlung von NotificationType-Entitäten"
  )
  public static class PagedNotificationTypeCollection extends PagedModel<NotificationTypeModel> {
  }

  @Schema(
    name = "NotificationTypeSearchResponse",
    description = "Keyset-paginierte Suchantwort fuer NotificationType-Ressourcen"
  )
  public static class NotificationTypeSearchResponse extends SearchResponse<NotificationTypeModel> {
  }

}
