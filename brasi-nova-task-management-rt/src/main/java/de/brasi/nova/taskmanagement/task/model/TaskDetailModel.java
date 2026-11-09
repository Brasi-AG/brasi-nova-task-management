package de.brasi.nova.taskmanagement.task.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.brasi.nova.taskmanagement.inbox.model.ExitModel;
import io.keel.framework.api.service.model.BaseModel;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Antwort-DTO fuer das Detail einer Aufgabe.
 *
 * <p>PLAN #141 Slice 1: migriert vom {@code record} auf eine {@link BaseModel}-Subklasse, damit der
 * GET-Detail-Endpunkt ueber die KSF-Enrichment-Pipeline (globaler {@code NovaFindResponseAdvice} +
 * {@code @EnableNovaHateoas}-Scan + {@code SelfLinkEnricher}) permission-gegatete HATEOAS-{@code
 * _links} (zunaechst nur {@code self}) tragen kann. Die {@code _links} sind rein ADDITIV (Anzeige)
 * und veraendern WEDER die bestehende objekt-level Enforcement ({@code TaskMembershipGuard}) NOCH
 * den Wire-Vertrag der fachlichen Felder.
 *
 * <p>Wire-Vertrag (unveraendert): alle bisherigen Felder behalten identischen JSON-Namen und Typ.
 * Die externe UUID {@code id} wird auf {@code BaseModel.id} abgebildet (via {@code super(id)}). Die
 * booleschen Marker tragen bewusst den {@code is}-Praefix im Wire ({@code isPinned}/{@code
 * isFlagged}/{@code isMuted}, NICHT bean-gestripptes {@code pinned}/...); {@code @JsonProperty}
 * pinnt die Namen, da fluent Lombok-Accessoren von Jackson nicht als Bean-Properties erkannt werden.
 * Einziges additives Wire-Feld aus {@code BaseModel}: {@code modelState} (Lebenszyklus-Zustand). Das
 * ebenfalls geerbte {@code createdAt} traegt {@code @JsonInclude(NON_NULL)} und bleibt hier
 * ungesetzt -> faellt aus dem Wire (kein fachliches {@code createdAt}-Feld, keine Kollision).
 *
 * <p>FINDING 3 (Codex-IMPL-Gate): das von {@code BaseModel} geerbte {@code version} wird bewusst via
 * {@link JsonIgnoreProperties} aus dem Wire genommen. Der Detail-Vertrag kannte nie ein {@code
 * version}-Feld (vormals ein {@code record} mit genau 21 Komponenten); der Detail-Service setzt es
 * nicht, sodass es als unbeabsichtigtes {@code version:null} serialisiert wuerde. Optimistic-Locking
 * laeuft entity-seitig (nicht ueber dieses Lese-DTO), daher gibt es keinen fachlichen Grund, {@code
 * version} am Detail zu exponieren -> minimaler, zum Alt-Vertrag deckungsgleicher Wire.
 *
 * <p>Die fluent-Accessoren ({@code @Accessors(fluent = true)}) erhalten die record-stilige
 * Lese-API ({@code title()}, {@code isPinned()}, {@code source()}, ...), sodass bestehende
 * Java-Konsumenten (z.B. {@code TaskDetailServiceTest}) unveraendert weiterlaufen.
 */
@Getter
@Setter
@Accessors(fluent = true)
@JsonIgnoreProperties("version")
public class TaskDetailModel extends BaseModel<TaskDetailModel> {

  @JsonProperty("title")
  @Schema(description = "Titel der Aufgabe.", example = "Jahresabschluss vorbereiten")
  private String title;

  @JsonProperty("description")
  @Schema(
      description = "Optionale Beschreibung der Aufgabe.",
      example = "Bitte Unterlagen bis Montag bereitstellen.")
  private String description;

  @JsonProperty("statusCode")
  @Schema(description = "Aktueller Status-CODE der Aufgabe.", example = "OPEN")
  private String statusCode;

  @JsonProperty("priorityCode")
  @Schema(description = "Prioritaets-CODE der Aufgabe.", example = "NORMAL")
  private String priorityCode;

  @JsonProperty("directionCode")
  @Schema(description = "Richtungs-CODE der Aufgabe.", example = "CUSTOMER_INTERNAL")
  private String directionCode;

  @JsonProperty("startDate")
  @Schema(description = "Optionales Startdatum (ISO 8601).", example = "2026-11-01")
  private LocalDate startDate;

  @JsonProperty("dueDate")
  @Schema(description = "Optionales Faelligkeitsdatum (ISO 8601).", example = "2026-12-31")
  private LocalDate dueDate;

  @JsonProperty("lastActivityAt")
  @Schema(description = "Zeitpunkt der letzten Aktivitaet (UTC).", example = "2026-10-18T08:00:00Z")
  private Instant lastActivityAt;

  @JsonProperty("commentCount")
  @Schema(description = "Anzahl der Kommentare (denormalisiert).", example = "3")
  private int commentCount;

  @JsonProperty("history")
  @Schema(description = "Liste der Historieneintraege (neueste zuerst).")
  private List<HistoryModel> history;

  @JsonProperty("assignees")
  @ArraySchema(
      schema =
          @Schema(
              type = "string",
              format = "uuid",
              description = "GUID eines aktuellen Assignees (PROD_BENUTZER.EXTERNALID)."))
  @Schema(
      description =
          "Aktuelle Assignee-GUIDs (stabil sortiert); Read-before-edit fuer PUT assignees.")
  private List<UUID> assignees;

  @JsonProperty("tagIds")
  @ArraySchema(
      schema =
          @Schema(
              type = "string",
              format = "uuid",
              description = "GUID eines aktuell zugeordneten Tags (TASK_TAG.EXTERNALID)."))
  @Schema(
      description =
          "Aktuelle Tag-GUIDs (stabil sortiert); Read-before-edit fuer PATCH tagIds."
              + " Enthaelt auch katalog-inaktive, aber noch zugeordnete Tags (faithful).")
  private List<UUID> tagIds;

  @JsonProperty("tags")
  @ArraySchema(
      schema =
          @Schema(
              implementation = TagRefModel.class,
              description = "Anzeige-Projektion eines zugeordneten Tags (id, name, color)."))
  @Schema(
      description =
          "Anzeige-Projektion ALLER zugeordneten Tags (id/name/color, stabil"
              + " sortiert nach id). Enthaelt - wie tagIds - auch katalog-inaktive (retire-te), aber noch"
              + " zugeordnete Tags (faithful, status-agnostischer globaler Katalog-Read), damit das"
              + " Frontend auch deaktivierte zugeordnete Tags mit Name/Farbe rendern kann. Additiv zu"
              + " tagIds (Intake #138).")
  private List<TagRefModel> tags;

  @JsonProperty("createdById")
  @Schema(
      description = "UUID des Erstellers (CREATOR_FLAG, PERSON) oder null.",
      example = "550e8400-e29b-41d4-a716-446655440000")
  private UUID createdById;

  @JsonProperty("participantIds")
  @ArraySchema(
      schema =
          @Schema(
              type = "string",
              format = "uuid",
              description = "GUID eines aktiven PERSON-Teilnehmers (ohne Assignees/Ersteller)."))
  @Schema(description = "Aktive PERSON-Teilnehmer ohne Assignees/Ersteller (stabil sortiert).")
  private List<UUID> participantIds;

  @JsonProperty("isPinned")
  @Schema(description = "Persoenlicher Marker des Anfragers: angepinnt.", example = "false")
  private boolean isPinned;

  @JsonProperty("isFlagged")
  @Schema(description = "Persoenlicher Marker des Anfragers: markiert.", example = "false")
  private boolean isFlagged;

  @JsonProperty("isMuted")
  @Schema(description = "Persoenlicher Marker des Anfragers: stummgeschaltet.", example = "false")
  private boolean isMuted;

  @JsonProperty("exit")
  @Schema(description = "Aktueller Austritt des Anfragers (handover/unsubscribed) oder null.")
  private ExitModel exit;

  @JsonProperty("source")
  @Schema(description = "Quellkontext, sofern vorhanden; href nur bei registriertem Typ.")
  private TaskSourceModel source;

  /** Parameterloser Konstruktor (Jackson/Framework). */
  public TaskDetailModel() {
    super();
  }

  /**
   * Voll-Konstruktor; bildet die externe UUID {@code id} auf {@code BaseModel.id} ab und setzt alle
   * fachlichen Felder. Signatur-identisch zur vormaligen {@code record}-Komponentenreihenfolge,
   * damit der Aufrufer ({@code TaskDetailService}) feldscharf migriert werden kann.
   */
  public TaskDetailModel(
      final UUID id,
      final String title,
      final String description,
      final String statusCode,
      final String priorityCode,
      final String directionCode,
      final LocalDate startDate,
      final LocalDate dueDate,
      final Instant lastActivityAt,
      final int commentCount,
      final List<HistoryModel> history,
      final List<UUID> assignees,
      final List<UUID> tagIds,
      final List<TagRefModel> tags,
      final UUID createdById,
      final List<UUID> participantIds,
      final boolean isPinned,
      final boolean isFlagged,
      final boolean isMuted,
      final ExitModel exit,
      final TaskSourceModel source) {
    super(id);
    this.title = title;
    this.description = description;
    this.statusCode = statusCode;
    this.priorityCode = priorityCode;
    this.directionCode = directionCode;
    this.startDate = startDate;
    this.dueDate = dueDate;
    this.lastActivityAt = lastActivityAt;
    this.commentCount = commentCount;
    this.history = history;
    this.assignees = assignees;
    this.tagIds = tagIds;
    this.tags = tags;
    this.createdById = createdById;
    this.participantIds = participantIds;
    this.isPinned = isPinned;
    this.isFlagged = isFlagged;
    this.isMuted = isMuted;
    this.exit = exit;
    this.source = source;
  }

  /**
   * Externe UUID der Aufgabe (fluent-Alias auf {@code BaseModel.getId()}). Haelt die record-stilige
   * Lese-API {@code model.id()} fuer bestehende Konsumenten aufrecht; der Wire-Name {@code id} wird
   * von {@code BaseModel.getId()} serialisiert.
   */
  public UUID id() {
    return getId();
  }
}
