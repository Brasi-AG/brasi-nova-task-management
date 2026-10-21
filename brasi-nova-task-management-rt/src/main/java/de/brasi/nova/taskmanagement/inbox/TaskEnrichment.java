package de.brasi.nova.taskmanagement.inbox;

import java.util.List;
import java.util.UUID;

/**
 * Interner Anreicherungs-Satz einer Aufgabe fuer die Inbox-Read-Projektion (BE-1), erzeugt vom
 * {@link InboxEnrichmentAssembler} und vom {@code InboxSnapshotService} in das
 * {@code TaskSummaryModel} gemappt. Bewusst KEIN Wire-Modell.
 *
 * @param createdById    UUID des Erstellers ({@code CREATOR_FLAG}, PERSON) oder {@code null}
 * @param assigneeIds    PERSON-UUIDs mit {@code ASSIGNEE_FLAG} und ohne Austritt (stabil sortiert)
 * @param participantIds PERSON-UUIDs mit {@code PARTICIPANT_FLAG} ohne Austritt, MINUS Assignees
 *                       MINUS Ersteller (stabil sortiert)
 * @param tagIds         zugeordnete Tag-UUIDs (inkl. katalog-inaktiver; stabil sortiert)
 * @param isPinned       Actor-Marker aus {@code TASK_USER_STATE}
 * @param isFlagged      Actor-Marker aus {@code TASK_USER_STATE}
 * @param isMuted        Actor-Marker aus {@code TASK_USER_STATE}
 * @param exitKind       asOf-gebundener Austritt des Anfragers: {@code "handover"},
 *                       {@code "unsubscribed"} oder {@code null} (aktives Mitglied)
 * @param handedOverToIds Uebergabeziele (BE-7) des sichtbaren Austritts-Handover-Ereignisses des
 *                       Anfragers; nur bei {@code exitKind="handover"} gefuellt (ggf. leer bei
 *                       Selbst-Handover ohne Ziele), sonst leer. Nie {@code null}.
 */
public record TaskEnrichment(
    UUID createdById,
    List<UUID> assigneeIds,
    List<UUID> participantIds,
    List<UUID> tagIds,
    boolean isPinned,
    boolean isFlagged,
    boolean isMuted,
    String exitKind,
    List<UUID> handedOverToIds) {

  /** Anreicherung einer Aufgabe ohne jegliche Rows (leere Listen, keine Marker, kein Austritt). */
  public static final TaskEnrichment EMPTY =
      new TaskEnrichment(
          null, List.of(), List.of(), List.of(), false, false, false, null, List.of());
}
