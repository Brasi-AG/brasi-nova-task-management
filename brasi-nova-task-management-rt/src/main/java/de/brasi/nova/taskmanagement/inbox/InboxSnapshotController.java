package de.brasi.nova.taskmanagement.inbox;

import de.brasi.nova.taskmanagement.inbox.model.InboxSnapshotModel;
import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
  @Operation(
      summary = "Inbox-Snapshot laden",
      description = "Liefert den vollstaendigen Bootstrap-Snapshot der Inbox:"
          + " aktive Aufgaben (erste Seite), Benachrichtigungen, Tag-Katalog,"
          + " Teilnehmer-Zusammenfassungen und Archiv-Gesamtzahlen."
          + " Erzeugt ein asOf-Wasserzeichen fuer konsistente Keyset-Paginierung.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Inbox-Snapshot erfolgreich geladen.",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = InboxSnapshotModel.class))),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_READ-Berechtigung."),
      @ApiResponse(
          responseCode = "410",
          description = "Snapshot konnte unter anhaltender Membership-Aenderung nicht konsistent"
              + " erzeugt werden (seltene Degradation); Client wiederholt die Anfrage.")
  })
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
  @Operation(
      summary = "Aktive Aufgaben laden",
      description = "Liefert eine Keyset-paginierte Seite aktiver (OPEN) Aufgaben des angemeldeten Benutzers."
          + " Setzt das asOf-Wasserzeichen bei der ersten Seite (cursor=leer).")
  @ApiResponses({
      // Kein explizites 200-content: springdoc leitet das Schema aus dem parametrisierten
      // Rueckgabetyp KeysetPage<TaskSummaryModel> ab (items dann korrekt TaskSummaryModel statt
      // untypisiert - ein roher @Schema(implementation=KeysetPage.class) wuerde den Typparameter
      // verlieren).
      @ApiResponse(responseCode = "200", description = "Seite aktiver Aufgaben."),
      @ApiResponse(responseCode = "400", description = "Ungueltiger cursor."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_READ-Berechtigung."),
      @ApiResponse(
          responseCode = "410",
          description = "Snapshot abgelaufen; Client muss neu bootstrappen. Folgeseite (cursor):"
              + " Membership-Generation geaendert. Erste Seite (ohne cursor): Snapshot konnte unter"
              + " anhaltender Membership-Aenderung nicht konsistent erzeugt werden (seltene"
              + " Degradation).")
  })
  @GetMapping
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<KeysetPage<TaskSummaryModel>> activeTasks(
      @Parameter(description = "Opaker Keyset-Cursor fuer die naechste Seite (leer = erste Seite).")
      @RequestParam(required = false) final String cursor,
      @Parameter(description = "Seitengroesse, geclampt auf [1, 100]. Default: 50.")
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
  @Operation(
      summary = "Archivierte Aufgaben laden",
      description = "Liefert eine Keyset-paginierte Seite archivierter Aufgaben des angemeldeten Benutzers."
          + " filter=all (Standard): alle archivierten; filter=done: erledigte; filter=left: verlassene.")
  @ApiResponses({
      // Kein explizites 200-content: springdoc leitet das Schema aus dem parametrisierten
      // Rueckgabetyp KeysetPage<TaskSummaryModel> ab (siehe activeTasks).
      @ApiResponse(responseCode = "200", description = "Seite archivierter Aufgaben."),
      @ApiResponse(responseCode = "400", description = "Ungueltiger filter-Wert oder cursor."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_READ-Berechtigung."),
      @ApiResponse(
          responseCode = "410",
          description = "Snapshot abgelaufen; Client muss neu bootstrappen. Folgeseite (cursor):"
              + " Membership-Generation geaendert. Erste Seite (ohne cursor): Snapshot konnte unter"
              + " anhaltender Membership-Aenderung nicht konsistent erzeugt werden (seltene"
              + " Degradation).")
  })
  @GetMapping("/archive")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<KeysetPage<TaskSummaryModel>> archiveTasks(
      @Parameter(description = "Filter: 'all' (Standard), 'done' (erledigt) oder 'left' (verlassen).")
      @RequestParam(required = false) final String filter,
      @Parameter(description = "Opaker Keyset-Cursor fuer die naechste Seite (leer = erste Seite).")
      @RequestParam(required = false) final String cursor,
      @Parameter(description = "Seitengroesse, geclampt auf [1, 100]. Default: 50.")
      @RequestParam(required = false, defaultValue = "50") final int limit) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return ResponseEntity.ok(
        snapshotService.loadArchivePage(actor.user().id(), filter, cursor, limit));
  }
}
