package de.brasi.nova.taskmanagement.inbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.brasi.nova.taskmanagement.task.TaskFacadeController;
import de.brasi.nova.taskmanagement.task.TaskHateoas;
import io.keel.framework.api.hateoas.annotation.SelfLinkTarget;
import io.keel.framework.api.service.model.InfoModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.hateoas.server.core.Relation;

/**
 * Info-Projektion einer Aufgabe fuer den Snapshot (PLAN #141 Slice 5, Teil B).
 *
 * <p>Zweite Projektion neben {@link TaskSummaryModel} (R7: TaskSummaryModel fuer Listen mit
 * per-Item-Affordance-Rels; TaskSummaryInfoModel NUR fuer den Snapshot mit {@code self}-Link).
 *
 * <p>Der {@code SelfLinkEnricher} traversiert den {@link
 * de.brasi.nova.taskmanagement.paging.KeysetPage} -Record per reflektivem Feldzugriff ({@code
 * items}-Feld = Iterable) und fuegt je {@link InfoModel}-Instanz mit non-null {@code id} einen
 * {@code self}-Link ein. Das Ziel stammt aus {@code @SelfLinkTarget}: {@code
 * controller=TaskFacadeController.class, linkTo=TaskHateoas.Targets.GET_DETAIL}; der Enricher
 * bindet {@code {id}} aus {@code infoModel.getId()}.
 *
 * <p><b>Warum KEIN BaseModel:</b> InfoModel serialisiert nur {@code id:UUID} (+{@code _links}). Es
 * gibt keine {@code createdAt:LocalDateTime}-Kollision (InfoModel hat kein createdAt) -> {@code
 * createdAt:Instant} ist ein normales Feld ohne R8-Neutralisierungsaufwand. Es gibt auch kein
 * {@code modelState} im Wire (InfoModel hat keinen {@code modelState}-Getter).
 *
 * <p>Feldnamen und Typen entsprechen exakt den {@link TaskSummaryModel}-Feldern (unveraenderter
 * fachlicher Wire-Vertrag fuer Snapshot-Items). Einzige gewollte Unterschiede:
 *
 * <ul>
 *   <li>{@code self}-Link kommt hinzu (via SelfLinkEnricher).
 *   <li>{@code modelState} entfaellt (BaseModel-only, nicht in InfoModel).
 * </ul>
 *
 * @see TaskSummaryModel
 * @see io.keel.framework.api.hateoas.annotation.SelfLinkTarget
 */
@Getter
@Setter
@SelfLinkTarget(controller = TaskFacadeController.class, linkTo = TaskHateoas.Targets.GET_DETAIL)
@Relation(itemRelation = "task", collectionRelation = "tasks")
public class TaskSummaryInfoModel extends InfoModel<TaskSummaryInfoModel> {

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

  @JsonProperty("createdAt")
  @Schema(description = "Erstellzeitpunkt der Aufgabe (UTC).", example = "2026-10-01T06:30:00Z")
  private Instant createdAt;

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
  public TaskSummaryInfoModel() {
    super();
  }

  /**
   * Voll-Konstruktor; bildet die externe UUID {@code id} auf {@code InfoModel.id} ab und setzt alle
   * fachlichen Felder. Signatur parallel zu {@link TaskSummaryModel}.
   *
   * @param id externe UUID der Aufgabe
   * @param title Titel
   * @param statusCode Status-CODE
   * @param priorityCode Prioritaets-CODE
   * @param dueDate Faelligkeitsdatum (optional)
   * @param lastActivityAt letzter Aktivitaetszeitpunkt
   * @param commentCount Kommentaranzahl
   * @param createdById UUID des Erstellers
   * @param createdAt Erstellzeitpunkt
   * @param description optionale Beschreibung
   * @param assigneeIds UUIDs der Assignees
   * @param participantIds UUIDs der Teilnehmer
   * @param tagIds UUIDs der Tags
   * @param isPinned persoenlicher Pin-Marker
   * @param isFlagged persoenlicher Flag-Marker
   * @param isMuted persoenlicher Mute-Marker
   * @param isDone abgeschlossen
   * @param exit Austritts-Info (optional)
   */
  public TaskSummaryInfoModel(
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
    super();
    setId(id);
    this.title = title;
    this.statusCode = statusCode;
    this.priorityCode = priorityCode;
    this.dueDate = dueDate;
    this.lastActivityAt = lastActivityAt;
    this.commentCount = commentCount;
    this.createdById = createdById;
    this.createdAt = createdAt;
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
}
