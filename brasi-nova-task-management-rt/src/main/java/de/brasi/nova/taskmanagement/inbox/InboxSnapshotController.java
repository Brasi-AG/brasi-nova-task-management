package de.brasi.nova.taskmanagement.inbox;

import de.brasi.nova.taskmanagement.inbox.model.InboxSnapshotModel;
import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer Inbox-Snapshot und aktive/Archiv-Reads (arch-v2 §7,
 * plan-v3 Task 14).
 *
 * <p>Jeder Handler traegt {@link RequiresPermission}{@code (TASK_READ)} (Pflicht gemaess arch-v2
 * §8). Actor-UUID + Mandant kommen ausschliesslich aus dem Security-Context
 * ({@link CurrentActor#require(UserAccessor)}), NIEMALS aus Client-Eingabedaten.
 *
 * <p>Route-Praezedenz: die literalen Pfade {@code /snapshot} und {@code /archive} werden vor dem
 * variablen {@code /{id}} des {@code TaskFacadeController} gematcht (gleiches Muster wie
 * {@code NotificationController} {@code /read-all} vor {@code /{id}/read}). Die Collection-Route
 * {@code GET /api/v1/tasks} existiert im Facade-Controller nicht (nur POST), daher kollisionsfrei.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class InboxSnapshotController {

  private final InboxSnapshotService snapshotService;
  private final UserAccessor userAccessor;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public InboxSnapshotController(
      final InboxSnapshotService snapshotService, final UserAccessor userAccessor) {
    this.snapshotService = snapshotService;
    this.userAccessor = userAccessor;
  }

  /**
   * Liefert den Bootstrap-Snapshot der Inbox fuer den angemeldeten Benutzer.
   *
   * @return 200 OK mit {@link InboxSnapshotModel}
   */
  @GetMapping("/snapshot")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<InboxSnapshotModel> snapshot() {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return ResponseEntity.ok(snapshotService.loadSnapshot(actor.user().id(), actor.tenant().id()));
  }

  /**
   * Liefert eine Keyset-Seite aktiver (OPEN) Aufgaben (Fortsetzung des Snapshots).
   *
   * @param cursor opaker Cursor (null = erste Seite mit frischem asOf)
   * @param limit  Seitengroesse (default 50, geclampt auf [1, 100])
   * @return 200 OK mit {@link KeysetPage} von {@link TaskSummaryModel}
   */
  @GetMapping
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<KeysetPage<TaskSummaryModel>> activeTasks(
      @RequestParam(required = false) final String cursor,
      @RequestParam(required = false, defaultValue = "50") final int limit) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return ResponseEntity.ok(snapshotService.loadActivePage(actor.user().id(), cursor, limit));
  }

  /**
   * Liefert eine Keyset-Seite archivierter Aufgaben je Filter.
   *
   * @param filter {@code all} (default) | {@code done} | {@code left}
   * @param cursor opaker Cursor (null = erste Seite mit frischem asOf)
   * @param limit  Seitengroesse (default 50, geclampt auf [1, 100])
   * @return 200 OK mit {@link KeysetPage} von {@link TaskSummaryModel}
   */
  @GetMapping("/archive")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<KeysetPage<TaskSummaryModel>> archiveTasks(
      @RequestParam(required = false) final String filter,
      @RequestParam(required = false) final String cursor,
      @RequestParam(required = false, defaultValue = "50") final int limit) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return ResponseEntity.ok(
        snapshotService.loadArchivePage(actor.user().id(), filter, cursor, limit));
  }
}
