package de.brasi.nova.taskmanagement.inbox.model;

import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.participant.model.ParticipantSummary;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 * @param asOf                 Snapshot-Wasserzeichen (UTC); identisch mit {@code tasks.asOf()}
 * @param currentUserId        UUID des authentifizierten Anfragers (Actor, serverseitig)
 * @param tasks                erste Keyset-Seite der aktiven (OPEN) Aufgaben des Benutzers
 * @param notifications        erste Seite der Benachrichtigungen des Benutzers
 * @param tags                 globaler Tag-Katalog (alle aktiven Tags)
 * @param participantSummaries distinkte Teilnehmer NUR der Aufgaben dieser Seite ("Seiten-Teilnehmer")
 * @param archiveTotals        Archiv-Gesamtzahlen je Filter (all/done/left) zum {@code asOf}
 */
public record InboxSnapshotModel(
    @Schema(description = "Snapshot-Wasserzeichen (UTC); fuer alle Folge-Reads desselben Scrolls identisch.",
        example = "2026-10-18T08:00:00Z")
    Instant asOf,
    @Schema(description = "UUID des authentifizierten Anfragers (Actor).",
        example = "550e8400-e29b-41d4-a716-446655440000")
    UUID currentUserId,
    @Schema(description = "Erste Keyset-Seite der aktiven (OPEN) Aufgaben des Benutzers.")
    KeysetPage<TaskSummaryModel> tasks,
    @Schema(description = "Erste Seite der Benachrichtigungen des Benutzers.")
    NotificationPage notifications,
    @Schema(description = "Globaler Tag-Katalog (alle aktiven Tags).")
    List<TagModel> tags,
    @Schema(description = "Distinkte Teilnehmer der Aufgaben dieser Seite (nur aktuell sichtbare Seite).")
    List<ParticipantSummary> participantSummaries,
    @Schema(description = "Archiv-Gesamtzahlen je Filter (all/done/left) zum asOf-Zeitpunkt.")
    ArchiveTotals archiveTotals) {}
