package de.brasi.nova.taskmanagement.inbox.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.participant.model.ParticipantSummary;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import io.keel.framework.api.service.model.InfoModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Bootstrap-Snapshot der benutzer-scoped Inbox (arch-v2 §2.1/§3).
 *
 * <p>Erzeugt EIN {@code asOf}-Wasserzeichen (UTC), das sowohl top-level getragen wird als auch im
 * {@link KeysetPage#asOf()} der aktiven Aufgabenseite. Es gibt bewusst KEINEN {@code sequence}-/
 * Delta-Mechanismus in 1a (arch-v2 §3: Reconnect = Re-Bootstrap mit neuem {@code asOf}).
 *
 * <p>BE-1: {@code currentUserId} traegt den authentifizierten Anfrager (aus {@code CurrentActor},
 * nie vom Client), damit das Frontend Actor-bezogene Anzeige (eigene vs. fremde Zuordnung) ohne
 * zusaetzlichen Call aufloesen kann.
 *
 * <p>PLAN #141 Slice 5 (Teil B): migriert vom {@code record} auf eine {@link InfoModel}-Subklasse,
 * damit der ausliefernde {@code TaskFacadeController} (Rule-Owner der {@code Task}-Entitaet) den
 * {@code SelfLinkEnricher} fuer die verschachtelten {@link TaskSummaryInfoModel}-Items triggert.
 * Der Enricher laeuft nur, wenn (a) der Response-Body ein {@link InfoModel} ist UND (b) der
 * ausliefernde Controller ein {@code @EnableNovaHateoas}-Rule-Owner ist.
 *
 * <p>Das top-level {@code id}-Feld von {@link InfoModel} wird per {@code @JsonIgnore} unterdrueckt:
 * der Snapshot hat keine eigene UUID (kein persistentes Objekt). {@code asOf}, {@code currentUserId}
 * und die anderen Nutzfelder werden per {@code @JsonProperty} gepinnt.
 *
 * <p>Die fluent-Accessoren ({@code @Accessors(fluent = true)}) erhalten die record-stilige Lese-API
 * fuer bestehende Java-Konsumenten ({@code model.asOf()}, {@code model.tasks()} usw.).
 */
@Getter
@Setter
@Accessors(fluent = true)
public class InboxSnapshotModel extends InfoModel<InboxSnapshotModel> {

  @JsonProperty("asOf")
  @Schema(
      description =
          "Snapshot-Wasserzeichen (UTC); fuer alle Folge-Reads desselben Scrolls identisch.",
      example = "2026-10-18T08:00:00Z")
  private Instant asOf;

  @JsonProperty("currentUserId")
  @Schema(
      description = "UUID des authentifizierten Anfragers (Actor).",
      example = "550e8400-e29b-41d4-a716-446655440000")
  private UUID currentUserId;

  @JsonProperty("tasks")
  @Schema(description = "Erste Keyset-Seite der aktiven (OPEN) Aufgaben des Benutzers.")
  private KeysetPage<TaskSummaryInfoModel> tasks;

  @JsonProperty("notifications")
  @Schema(description = "Erste Seite der Benachrichtigungen des Benutzers.")
  private NotificationPage notifications;

  @JsonProperty("tags")
  @Schema(description = "Globaler Tag-Katalog (alle aktiven Tags).")
  private List<TagModel> tags;

  @JsonProperty("participantSummaries")
  @Schema(
      description =
          "Distinkte Teilnehmer der Aufgaben dieser Seite (nur aktuell sichtbare Seite).")
  private List<ParticipantSummary> participantSummaries;

  @JsonProperty("archiveTotals")
  @Schema(description = "Archiv-Gesamtzahlen je Filter (all/done/left) zum asOf-Zeitpunkt.")
  private ArchiveTotals archiveTotals;

  /** Parameterloser Konstruktor (Jackson/Framework). */
  public InboxSnapshotModel() {
    super();
  }

  /**
   * Voll-Konstruktor; spiegelt die vormalige {@code record}-Komponentenreihenfolge, damit der
   * Aufrufer ({@code InboxSnapshotService.loadSnapshot}) unveraendert bleibt.
   *
   * @param asOf Snapshot-Wasserzeichen (UTC)
   * @param currentUserId UUID des Anfragers (Actor)
   * @param tasks aktive Aufgabenseite
   * @param notifications Benachrichtigungs-Seite
   * @param tags globaler Tag-Katalog
   * @param participantSummaries distinkte Seiten-Teilnehmer
   * @param archiveTotals Archiv-Gesamtzahlen
   */
  public InboxSnapshotModel(
      final Instant asOf,
      final UUID currentUserId,
      final KeysetPage<TaskSummaryInfoModel> tasks,
      final NotificationPage notifications,
      final List<TagModel> tags,
      final List<ParticipantSummary> participantSummaries,
      final ArchiveTotals archiveTotals) {
    super();
    this.asOf = asOf;
    this.currentUserId = currentUserId;
    this.tasks = tasks;
    this.notifications = notifications;
    this.tags = tags;
    this.participantSummaries = participantSummaries;
    this.archiveTotals = archiveTotals;
  }

  /**
   * Unterdrueckt das geerbte top-level {@code id}-Feld im Wire. Der Snapshot hat keine eigene UUID
   * (kein persistentes Objekt); ein {@code id:null} im JSON waere irreführend.
   *
   * @return immer {@code null} (nicht im Wire)
   */
  @Override
  @JsonIgnore
  public UUID getId() {
    return super.getId();
  }
}
