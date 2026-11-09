package de.brasi.nova.taskmanagement.tag.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.keel.framework.api.service.model.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.hateoas.server.core.Relation;

/**
 * Antwort-DTO fuer einen Tag im globalen Katalog.
 *
 * <p>PLAN #141 Slice 2: migriert vom {@code record} auf eine {@link BaseModel}-Subklasse, damit
 * {@code GET /api/v1/tags} ueber die KSF-Enrichment-Pipeline ({@code NovaFindResponseAdvice} +
 * {@code @EnableNovaHateoas}-Scan am {@code TagController}) permission-gegatete HATEOAS-{@code
 * _links} (create/update/deactivate/delete) tragen kann. Die {@code _links} sind rein ADDITIV
 * (FE-Anzeige) und veraendern WEDER die bestehende Enforcement
 * ({@code @RequiresPermission(TAG_ADMIN)} an jedem Mutations-Handler) NOCH den Wire-Vertrag der
 * fachlichen Felder.
 *
 * <p>Wire-Vertrag: alle bisherigen Felder ({@code id}, {@code name}, {@code severityCode}, {@code
 * color}) behalten identischen JSON-Namen und Typ; {@code id} wird auf {@code BaseModel.id}
 * abgebildet (via {@code super(id)}). Einziges additives Wire-Feld aus {@code BaseModel}: {@code
 * modelState} (Lebenszyklus-Zustand). Das geerbte {@code version} wird - wie beim {@code
 * TaskDetailModel} (Slice 1) - bewusst via {@link JsonIgnoreProperties} aus dem Wire genommen: der
 * Tag-Vertrag kannte nie ein {@code version}-Feld (vormals ein {@code record} mit genau vier
 * Komponenten); Optimistic-Locking laeuft entity-seitig, nicht ueber dieses Lese-DTO.
 *
 * <p>Die fluent-Accessoren ({@code @Accessors(fluent = true)}) erhalten die record-stilige Lese-API
 * ({@code name()}, {@code severityCode()}, {@code color()}); {@code id()} ist ein Alias auf {@code
 * BaseModel.getId()}, sodass bestehende Java-Konsumenten ({@code TagCatalogServiceTest})
 * unveraendert weiterlaufen.
 *
 * <p>{@code @Relation} pinnt die HAL-Namen der Ressource: ein einzelnes Item als {@code tag}, die
 * Collection als {@code tags} (= Schluessel unter {@code _embedded}).
 */
@Getter
@Setter
@Accessors(fluent = true)
@JsonIgnoreProperties("version")
@Relation(itemRelation = "tag", collectionRelation = "tags")
public class TagModel extends BaseModel<TagModel> {

  @JsonProperty("name")
  @Schema(description = "Tag-Name.", example = "Dringend")
  private String name;

  @JsonProperty("severityCode")
  @Schema(
      description =
          "Schweregrad-CODE des Tags (z.B. PRIMARY, DANGER, WARNING); null bei"
              + " color-only/neutralem Tag.",
      example = "DANGER",
      nullable = true)
  private String severityCode;

  @JsonProperty("color")
  @Schema(
      description =
          "UI-Farbe des Tags als Hex-String (#RRGGBB oder #RGB, normalisiert Grossbuchstaben);"
              + " null = keine Farbe.",
      example = "#F87171",
      nullable = true)
  private String color;

  /** Parameterloser Konstruktor (Jackson/Framework). */
  public TagModel() {
    super();
  }

  /**
   * Voll-Konstruktor; bildet die externe UUID {@code id} auf {@code BaseModel.id} ab und setzt alle
   * fachlichen Felder. Signatur-identisch zur vormaligen {@code record}-Komponentenreihenfolge,
   * damit der Aufrufer ({@code TagCatalogService.toModel}) feldscharf migriert werden kann.
   *
   * @param id externe UUID des Tags (EXTERNALID)
   * @param name Tag-Name (BEZEICHNUNG / entryValue)
   * @param severityCode Anzeigeschwere (CODE aus DIC_TAG_SEVERITY) oder {@code null}
   * @param color UI-Farbe als Hex-String ({@code #RRGGBB}/{@code #RGB}) oder {@code null}
   */
  public TagModel(final UUID id, final String name, final String severityCode, final String color) {
    super(id);
    this.name = name;
    this.severityCode = severityCode;
    this.color = color;
  }

  /**
   * Externe UUID des Tags (fluent-Alias auf {@code BaseModel.getId()}). Haelt die record-stilige
   * Lese-API {@code model.id()} fuer bestehende Konsumenten aufrecht; der Wire-Name {@code id} wird
   * von {@code BaseModel.getId()} serialisiert.
   *
   * @return die externe UUID des Tags
   */
  public UUID id() {
    return getId();
  }
}
