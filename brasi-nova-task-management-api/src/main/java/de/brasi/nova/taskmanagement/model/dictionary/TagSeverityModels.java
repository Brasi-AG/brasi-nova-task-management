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
 * Auto-generierte DTOs für die Entität TagSeverity.
 * NICHT MANUELL BEARBEITEN!
 * Diese Datei wird bei jeder Ausführung der CLI überschrieben.
 */


public class TagSeverityModels {

  @Relation(collectionRelation = "tagSeverityCollection", itemRelation = "tagSeverityModel")
  @Schema(
    name = "TagSeverityModel",
    description = """
# TagSeverityModel

> **Hinweis:** Automatisch erzeugter Platzhalter. Bitte fachlich befüllen
> (Zweck, Felder, Relationen/HATEOAS, Beispiele).
"""
  )
  @Getter
  @Setter
  public static class TagSeverityModel extends BaseDictionaryModel<TagSeverityModel> {

  }

  @Getter
  @Setter
  public static class TagSeverityCreateModel extends BaseCreateDictionaryModel implements CreateModel {

  }

  @Deprecated(since = "2.3.0", forRemoval = true)
  @Getter
  @Setter
  public static class TagSeverityUpdateModel extends BaseUpdateDictionaryModel<TagSeverityUpdateModel> {

  }

  @Getter
  @Setter
  public static class TagSeveritySingleUpdateModel extends BaseSingleUpdateDictionaryModel<TagSeveritySingleUpdateModel> {

  }

  @Getter
  @Setter
  public static class TagSeverityBatchUpdateModel extends BaseBatchUpdateValidModel<TagSeverityBatchUpdateModel> {

  }

  @Getter
  @Setter
  @Schema(name = "TagSeverityPatchModel", description = "Datenmodell für Teil-Aktualisierungen (Patch) einer TagSeverity-Entität. Nur gesetzte Felder werden geändert.")
  public static class TagSeverityPatchModel extends BasePatchDictionaryModel<TagSeverityPatchModel>{

}

@Relation(collectionRelation = "tagSeverityInfoCollection", itemRelation = "tagSeverityInfoModel")
@Schema(
  name = "TagSeverityInfoModel",
  description = "Leichtgewichtige Referenz auf TagSeverity."
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public static class TagSeverityInfoModel extends InfoModel<TagSeverityInfoModel> {






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
    name = "TagSeverityCollection",
    description = "Sammlung von TagSeverity-Entitäten mit HATEOAS-Links"
  )
  public static class TagSeverityCollection extends CollectionModel<TagSeverityModel> {
  }

  @Schema(
    name = "PagedTagSeverityCollection",
    description = "Paginierte Sammlung von TagSeverity-Entitäten"
  )
  public static class PagedTagSeverityCollection extends PagedModel<TagSeverityModel> {
  }

  @Schema(
    name = "TagSeveritySearchResponse",
    description = "Keyset-paginierte Suchantwort fuer TagSeverity-Ressourcen"
  )
  public static class TagSeveritySearchResponse extends SearchResponse<TagSeverityModel> {
  }

}
