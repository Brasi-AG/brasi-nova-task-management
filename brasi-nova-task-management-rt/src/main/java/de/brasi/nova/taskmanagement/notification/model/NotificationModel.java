package de.brasi.nova.taskmanagement.notification.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.keel.framework.api.service.model.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.hateoas.server.core.Relation;

/**
 * Read-Modell fuer eine Benachrichtigung (plan-v4 DTO-Register).
 *
 * <p>PLAN #141 Slice 4: migriert vom {@code record} auf eine {@link BaseModel}-Subklasse, damit
 * {@code GET /api/v1/notifications} ueber die KSF-Enrichment-Pipeline als HAL- {@link
 * de.brasi.nova.taskmanagement.paging.CursorCollectionModel} ausgeliefert werden kann. Item-rels
 * und Collection-rels sind Slice 5 vorbehalten (mark-read/mark-all-read). In Slice 4 wird nur der
 * Return-Typ umgebaut + die Enrichment-Faehigkeit (setEntity) hergestellt.
 *
 * <p><b>R8-createdAt-Kollision:</b> {@code createdAt:Instant} kollidiert mit dem geerbten {@code
 * getCreatedAt():LocalDateTime} von {@link BaseModel}. Neutralisierung identisch zu {@code
 * TaskSummaryModel}:
 *
 * <ul>
 *   <li>Feld heisst intern {@code createdInstant} (Typ {@code Instant}), traegt
 *       {@code @JsonProperty("createdAt")}.
 *   <li>Das geerbte {@code getCreatedAt():LocalDateTime} wird per {@code @JsonIgnore} unterdrueckt.
 *   <li>Fluent-Alias {@code createdAt()} fuer Alt-Konsumenten (record-Accessor-Kompat).
 * </ul>
 *
 * <p>Wire-Vertrag: alle bisherigen Felder behalten identischen JSON-Namen und Typ. {@code version}
 * wird via {@link JsonIgnoreProperties} aus dem Wire genommen.
 *
 * <p>{@code @Relation} pinnt die HAL-Namen: ein einzelnes Item als {@code notification}, die
 * Collection als {@code notifications} (= Schluessel unter {@code _embedded}).
 */
@Getter
@Setter
@Accessors(fluent = true)
@JsonIgnoreProperties("version")
@Relation(itemRelation = "notification", collectionRelation = "notifications")
public class NotificationModel extends BaseModel<NotificationModel> {

  @JsonProperty("typeCode")
  @Schema(
      description =
          "Technischer CODE des Benachrichtigungs-Typs (z.B. ASSIGN, REPLY, MENTION, HANDOVER).",
      example = "ASSIGN")
  private String typeCode;

  @JsonProperty("taskId")
  @Schema(
      description = "Externe UUID der betroffenen Aufgabe (null bei System-Benachrichtigungen).",
      example = "550e8400-e29b-41d4-a716-446655440000")
  private UUID taskId;

  @JsonProperty("actorRef")
  @Schema(
      description = "UUID des Akteurs, der die Benachrichtigung ausgeloest hat.",
      example = "770e8400-e29b-41d4-a716-446655440002")
  private UUID actorRef;

  @JsonProperty("text")
  @Schema(
      description = "Anzeigetext der Benachrichtigung.",
      example = "Du wurdest der Aufgabe zugewiesen.")
  private String text;

  @JsonProperty("preview")
  @Schema(
      description = "Optionale Vorschau (z.B. Aufgabentitel).",
      example = "Jahresabschluss vorbereiten")
  private String preview;

  @JsonProperty("severityCode")
  @Schema(
      description =
          "Technischer CODE des Schweregrads (z.B. INFO, WARNING; null wenn nicht gesetzt).",
      example = "INFO")
  private String severityCode;

  @JsonProperty("read")
  @Schema(description = "true wenn die Benachrichtigung gelesen wurde.", example = "false")
  private boolean read;

  /**
   * R8-Neutralisierung: Feld traegt den Erstellzeitpunkt als Instant, heisst intern {@code
   * createdInstant} und serialisiert via {@code @JsonProperty("createdAt")} als "createdAt". Das
   * geerbte {@code getCreatedAt():LocalDateTime} von {@link BaseModel} wird per {@link JsonIgnore}
   * unterdrueckt.
   */
  @JsonProperty("createdAt")
  @Schema(description = "Erstellungszeitpunkt (UTC).", example = "2026-10-18T08:00:00Z")
  private Instant createdInstant;

  /** Parameterloser Konstruktor (Jackson/Framework). */
  public NotificationModel() {
    super();
  }

  /**
   * Voll-Konstruktor; bildet die externe UUID {@code id} auf {@code BaseModel.id} ab und setzt alle
   * fachlichen Felder. Signatur-identisch zur vormaligen {@code record}-Komponentenreihenfolge,
   * damit der Aufrufer ({@code NotificationQueryService.toModel}) feldscharf migriert werden kann.
   */
  public NotificationModel(
      final UUID id,
      final String typeCode,
      final UUID taskId,
      final UUID actorRef,
      final String text,
      final String preview,
      final String severityCode,
      final boolean read,
      final Instant createdAt) {
    super(id);
    this.typeCode = typeCode;
    this.taskId = taskId;
    this.actorRef = actorRef;
    this.text = text;
    this.preview = preview;
    this.severityCode = severityCode;
    this.read = read;
    this.createdInstant = createdAt;
  }

  /**
   * R8-Neutralisierung: Das geerbte {@code getCreatedAt():LocalDateTime} von {@link BaseModel} wird
   * aus dem Wire genommen. Liefert {@code null}.
   *
   * @return immer {@code null} (nicht im Wire)
   */
  @Override
  @JsonIgnore
  public LocalDateTime getCreatedAt() {
    return null;
  }

  /**
   * Fluent-Alias auf {@code createdInstant} fuer Alt-Konsumenten (record-Accessor-Kompat).
   *
   * @return Erstellzeitpunkt als Instant
   */
  public Instant createdAt() {
    return createdInstant;
  }

  /**
   * Externe UUID der Benachrichtigung (fluent-Alias auf {@code BaseModel.getId()}).
   *
   * @return die externe UUID der Benachrichtigung
   */
  public UUID id() {
    return getId();
  }
}
