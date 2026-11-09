package de.brasi.nova.taskmanagement.inbox.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.keel.framework.api.service.model.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.hateoas.server.core.Relation;

/**
 * Kompakte Listenzeilen-Repraesentation einer Aufgabe fuer Snapshot/Inbox/Archiv (arch-v2 §2.1,
 * FE-Proof §B.4), BE-1 additiv angereichert.
 *
 * <p>PLAN #141 Slice 4: migriert vom {@code record} auf eine {@link BaseModel}-Subklasse,
 * damit {@code GET /api/v1/tasks} (activeTasks) und {@code GET /api/v1/tasks/archive} ueber
 * die KSF-Enrichment-Pipeline als HAL-{@link de.brasi.nova.taskmanagement.paging.CursorCollectionModel}
 * ausgeliefert werden koennen. Items tragen per-Item-Affordance-Rels (via entityClass=Task),
 * die Collection traegt create/create-from-source (via TASK_CREATE-Authority).
 *
 * <p><b>R8-createdAt-Kollision:</b> {@code createdAt:Instant} kollidiert mit dem geerbten
 * {@code getCreatedAt():LocalDateTime} von {@link BaseModel}. Neutralisierung:
 * <ul>
 *   <li>Feld heisst intern {@code createdInstant} (Typ {@code Instant}), traegt
 *       {@code @JsonProperty("createdAt")} und serialisiert als "createdAt".
 *   <li>Das geerbte {@code getCreatedAt():LocalDateTime} wird per {@code @JsonIgnore} aus dem
 *       Wire genommen (sonst doppeltes/falsches createdAt).
 *   <li>Fluent-Alias {@code createdAt()} liefert {@code createdInstant} fuer Alt-Konsumenten
 *       (record-Accessor-Kompat).
 * </ul>
 * Ergebnis: genau EIN {@code createdAt} im Wire, ISO-8601-Instant mit Z (unveraenderter Vertrag).
 *
 * <p>Wire-Vertrag: alle bisherigen Felder behalten identischen JSON-Namen und Typ. Einziges
 * additives Wire-Feld aus {@link BaseModel}: {@code modelState} (Lebenszyklus-Zustand).
 * {@code version} wird via {@link JsonIgnoreProperties} aus dem Wire genommen (kein fachlicher
 * Grund, das Lese-DTO mit dem Version-Feld des OCC zu belasten).
 *
 * <p>Die fluent-Accessoren ({@code @Accessors(fluent = true)}) erhalten die record-stilige
 * Lese-API fuer bestehende Java-Konsumenten.
 *
 * <p>{@code @Relation} pinnt die HAL-Namen: ein einzelnes Item als {@code task}, die Collection
 * als {@code tasks} (= Schluessel unter {@code _embedded}).
 */
@Getter
@Setter
@Accessors(fluent = true)
@JsonIgnoreProperties("version")
@Relation(itemRelation = "task", collectionRelation = "tasks")
public class TaskSummaryModel extends BaseModel<TaskSummaryModel> {

  @JsonProperty("title")
  @Schema(description = "Titel der Aufgabe.", example = "Jahresabschluss vorbereiten")
  private String title;

  @JsonProperty("statusCode")
  @Schema(description = "Aktueller Status-CODE.", example = "OPEN")
  private String statusCode;

  @JsonProperty("priorityCode")
  @Schema(description = "Prioritaets-CODE.", example = "NORMAL")
  private String priorityCode;

  @JsonProperty("dueDate")
  @Schema(description = "Optionales Faelligkeitsdatum.", example = "2026-12-31")
  private LocalDate dueDate;

  @JsonProperty("lastActivityAt")
  @Schema(description = "Zeitpunkt der letzten Aktivitaet (UTC).", example = "2026-10-18T08:00:00Z")
  private Instant lastActivityAt;

  @JsonProperty("commentCount")
  @Schema(description = "Anzahl der Kommentare (denormalisiert).", example = "0")
  private int commentCount;

  @JsonProperty("createdById")
  @Schema(
      description = "UUID des Erstellers (CREATOR_FLAG, PERSON) oder null.",
      example = "550e8400-e29b-41d4-a716-446655440000")
  private UUID createdById;

  /**
   * R8-Neutralisierung: Feld traegt den Erstellzeitpunkt als Instant, heisst intern
   * {@code createdInstant} und serialisiert via {@code @JsonProperty("createdAt")} als "createdAt".
   * Das geerbte {@code getCreatedAt():LocalDateTime} von {@link BaseModel} wird per
   * {@link JsonIgnore} unterdrueckt.
   */
  @JsonProperty("createdAt")
  @Schema(description = "Erstellzeitpunkt der Aufgabe (UTC).", example = "2026-10-01T06:30:00Z")
  private Instant createdInstant;

  @JsonProperty("description")
  @Schema(description = "Optionale Beschreibung.", example = "Details zur Aufgabe")
  private String description;

  @JsonProperty("assigneeIds")
  @Schema(description = "UUIDs der aktiven PERSON-Assignees (ohne Austritt).")
  private List<UUID> assigneeIds;

  @JsonProperty("participantIds")
  @Schema(description = "UUIDs der aktiven PERSON-Teilnehmer (ohne Assignees/Ersteller).")
  private List<UUID> participantIds;

  @JsonProperty("tagIds")
  @Schema(description = "UUIDs der zugeordneten Tags (inkl. katalog-inaktiver).")
  private List<UUID> tagIds;

  /**
   * R8: is-Marker als @JsonProperty gepinnt (fluent Lombok wird von Jackson nicht als Bean-Property
   * erkannt, daher explizite JSON-Pinnung wie in {@code TaskDetailModel}).
   */
  @JsonProperty("isPinned")
  @Schema(description = "Persoenlicher Marker: angepinnt.", example = "false")
  private boolean isPinned;

  @JsonProperty("isFlagged")
  @Schema(description = "Persoenlicher Marker: markiert.", example = "false")
  private boolean isFlagged;

  @JsonProperty("isMuted")
  @Schema(description = "Persoenlicher Marker: stummgeschaltet.", example = "false")
  private boolean isMuted;

  @JsonProperty("isDone")
  @Schema(description = "Abgeschlossen (Status DONE oder CANCELLED).", example = "false")
  private boolean isDone;

  @JsonProperty("exit")
  @Schema(description = "Austritts-Info des Anfragers (nur Archiv; sonst null).")
  private ExitModel exit;

  /** Parameterloser Konstruktor (Jackson/Framework). */
  public TaskSummaryModel() {
    super();
  }

  /**
   * Voll-Konstruktor; bildet die externe UUID {@code id} auf {@code BaseModel.id} ab und setzt alle
   * fachlichen Felder. Signatur-identisch zur vormaligen {@code record}-Komponentenreihenfolge,
   * damit der Aufrufer ({@code InboxSnapshotService.toEnrichedSummary}) feldscharf migriert werden
   * kann.
   */
  public TaskSummaryModel(
      final UUID id,
      final String title,
      final String statusCode,
      final String priorityCode,
      final LocalDate dueDate,
      final Instant lastActivityAt,
      final int commentCount,
      final UUID createdById,
      final Instant createdAt,
      final String description,
      final List<UUID> assigneeIds,
      final List<UUID> participantIds,
      final List<UUID> tagIds,
      final boolean isPinned,
      final boolean isFlagged,
      final boolean isMuted,
      final boolean isDone,
      final ExitModel exit) {
    super(id);
    this.title = title;
    this.statusCode = statusCode;
    this.priorityCode = priorityCode;
    this.dueDate = dueDate;
    this.lastActivityAt = lastActivityAt;
    this.commentCount = commentCount;
    this.createdById = createdById;
    this.createdInstant = createdAt;
    this.description = description;
    this.assigneeIds = assigneeIds;
    this.participantIds = participantIds;
    this.tagIds = tagIds;
    this.isPinned = isPinned;
    this.isFlagged = isFlagged;
    this.isMuted = isMuted;
    this.isDone = isDone;
    this.exit = exit;
  }

  /**
   * R8-Neutralisierung: Das geerbte {@code getCreatedAt():LocalDateTime} von {@link BaseModel}
   * wird aus dem Wire genommen (kein fachliches createdAt:LocalDateTime im TaskSummaryModel-Vertrag;
   * stattdessen {@code createdInstant} als {@code @JsonProperty("createdAt")}). Liefert {@code null},
   * damit keine {@code null}-Serialisierung erfolgt (BaseModel-{@code @JsonInclude(NON_NULL)}).
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
   * Bestehender Aufruf {@code model.createdAt()} liefert das Erstellzeit-Instant.
   *
   * @return Erstellzeitpunkt als Instant
   */
  public Instant createdAt() {
    return createdInstant;
  }

  /**
   * Externe UUID der Aufgabe (fluent-Alias auf {@code BaseModel.getId()}). Haelt die record-stilige
   * Lese-API {@code model.id()} fuer bestehende Konsumenten aufrecht.
   *
   * @return die externe UUID der Aufgabe
   */
  public UUID id() {
    return getId();
  }
}
