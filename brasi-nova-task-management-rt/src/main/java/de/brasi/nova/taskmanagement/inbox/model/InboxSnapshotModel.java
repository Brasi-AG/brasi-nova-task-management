package de.brasi.nova.taskmanagement.inbox.model;

import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.participant.model.ParticipantSummary;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import java.time.Instant;
import java.util.List;

/**
 * Bootstrap-Snapshot der benutzer-scoped Inbox (arch-v2 §2.1/§3).
 *
 * <p>Erzeugt EIN {@code asOf}-Wasserzeichen (UTC), das sowohl top-level getragen wird als auch im
 * {@link KeysetPage#asOf()} der aktiven Aufgabenseite. Es gibt bewusst KEINEN {@code sequence}-/
 * Delta-Mechanismus in 1a (arch-v2 §3: Reconnect = Re-Bootstrap mit neuem {@code asOf}).
 *
 * <p>1a-Scope eingefroren: KEIN {@code currentUserId} (FE-Proof §B.5, Folgetrack). Die aufgedeckten
 * Listenzeilen-Luecken (Marker/Assignees/Tags pro Zeile) sind bewusst nicht in 1a (FE-Proof §B.4).
 *
 * @param asOf                 Snapshot-Wasserzeichen (UTC); identisch mit {@code tasks.asOf()}
 * @param tasks                erste Keyset-Seite der aktiven (OPEN) Aufgaben des Benutzers
 * @param notifications        erste Seite der Benachrichtigungen des Benutzers
 * @param tags                 globaler Tag-Katalog (alle aktiven Tags)
 * @param participantSummaries distinkte Teilnehmer NUR der Aufgaben dieser Seite ("Seiten-Teilnehmer")
 * @param archiveTotals        Archiv-Gesamtzahlen je Filter (all/done/left) zum {@code asOf}
 */
public record InboxSnapshotModel(
    Instant asOf,
    KeysetPage<TaskSummaryModel> tasks,
    NotificationPage notifications,
    List<TagModel> tags,
    List<ParticipantSummary> participantSummaries,
    ArchiveTotals archiveTotals) {}
