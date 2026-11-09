package de.brasi.nova.taskmanagement.inbox;

import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.paging.CursorCollectionModel;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import io.keel.framework.api.openapi.annotation.ApiFindCollectionResponse;
import io.keel.framework.api.security.user.UserAccessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer aktive/Archiv-Reads (arch-v2 §7, plan-v3 Task 14).
 *
 * <p>PLAN #141 Slice 5 (Teil B): der {@code GET /tasks/snapshot}-Handler wurde in den {@code
 * TaskFacadeController} verschoben, damit er im Kontext eines {@code @EnableNovaHateoas}-
 * Rule-Owners (Task) ausgeliefert wird. Der SelfLinkEnricher benoetigt genau das fuer die
 * verschachtelten {@link de.brasi.nova.taskmanagement.inbox.model.TaskSummaryInfoModel}-Items.
 *
 * <p>Diese Reads sind AUTHENTICATED-ONLY (kein {@code @RequiresPermission}): das Lesen der EIGENEN
 * Inbox ist eine Basis-Faehigkeit jedes angemeldeten Nutzers (wie das eigene Profil), keine granted
 * Permission. Actor-UUID + Mandant kommen ausschliesslich aus dem Security-Context ({@link
 * CurrentActor#require(UserAccessor)}), NIEMALS aus Client-Eingabedaten - die Sicht ist damit
 * strikt aktor-scoped (ein Nutzer sieht nur die eigene Inbox). Anonyme Requests weist die
 * KSF-Default-Filter-Chain ({@code anyRequest().authenticated()}) bereits mit 401 ab.
 *
 * <p>Route-Praezedenz: die literalen Pfade {@code /archive} werden vor dem variablen {@code /{id}}
 * des {@code TaskFacadeController} gematcht. Die Collection-Route {@code GET /api/v1/tasks}
 * existiert im Facade-Controller nicht (nur POST), daher kollisionsfrei.
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
   * Liefert eine HAL-Collection aktiver (OPEN) Aufgaben (Fortsetzung des Snapshots).
   *
   * <p>Slice 4: Return-Typ ist {@link CursorCollectionModel} (HAL-Collection mit top-level
   * Cursor-Feldern + Item-rels via KSF-Enrichment-Pipeline).
   *
   * @param cursor opaker Cursor (null = erste Seite mit frischem asOf)
   * @param limit Seitengroesse (default 50, geclampt auf [1, 100])
   * @return 200 OK mit {@link CursorCollectionModel} von {@link TaskSummaryModel}
   */
  @Operation(
      summary = "Aktive Aufgaben laden",
      description =
          "Liefert eine Keyset-paginierte HAL-Collection aktiver (OPEN) Aufgaben des angemeldeten Benutzers."
              + " Setzt das asOf-Wasserzeichen bei der ersten Seite (cursor=leer)."
              + " Items tragen per-Item-Affordance-Rels (via entityClass=Task).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "HAL-Collection aktiver Aufgaben."),
    @ApiResponse(responseCode = "400", description = "Ungueltiger cursor."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(
        responseCode = "410",
        description =
            "Snapshot abgelaufen; Client muss neu bootstrappen. Folgeseite (cursor):"
                + " Membership-Generation geaendert. Erste Seite (ohne cursor): Snapshot konnte unter"
                + " anhaltender Membership-Aenderung nicht konsistent erzeugt werden (seltene"
                + " Degradation).")
  })
  @GetMapping
  @ApiFindCollectionResponse(TaskSummaryModel.class)
  public ResponseEntity<CursorCollectionModel<TaskSummaryModel>> activeTasks(
      @Parameter(description = "Opaker Keyset-Cursor fuer die naechste Seite (leer = erste Seite).")
          @RequestParam(required = false)
          final String cursor,
      @Parameter(description = "Seitengroesse, geclampt auf [1, 100]. Default: 50.")
          @RequestParam(required = false, defaultValue = "50")
          final int limit) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return ResponseEntity.ok(snapshotService.loadActivePage(actor.user().id(), cursor, limit));
  }

  /**
   * Liefert eine HAL-Collection archivierter Aufgaben je Filter.
   *
   * <p>Slice 4: Return-Typ ist {@link CursorCollectionModel} analog zu {@link #activeTasks}.
   *
   * @param filter {@code all} (default) | {@code done} | {@code left}
   * @param cursor opaker Cursor (null = erste Seite mit frischem asOf)
   * @param limit Seitengroesse (default 50, geclampt auf [1, 100])
   * @return 200 OK mit {@link CursorCollectionModel} von {@link TaskSummaryModel}
   */
  @Operation(
      summary = "Archivierte Aufgaben laden",
      description =
          "Liefert eine Keyset-paginierte HAL-Collection archivierter Aufgaben des angemeldeten Benutzers."
              + " filter=all (Standard): alle archivierten; filter=done: erledigte; filter=left: verlassene."
              + " Items tragen per-Item-Affordance-Rels (via entityClass=Task).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "HAL-Collection archivierter Aufgaben."),
    @ApiResponse(responseCode = "400", description = "Ungueltiger filter-Wert oder cursor."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(
        responseCode = "410",
        description =
            "Snapshot abgelaufen; Client muss neu bootstrappen. Folgeseite (cursor):"
                + " Membership-Generation geaendert. Erste Seite (ohne cursor): Snapshot konnte unter"
                + " anhaltender Membership-Aenderung nicht konsistent erzeugt werden (seltene"
                + " Degradation).")
  })
  @GetMapping("/archive")
  @ApiFindCollectionResponse(TaskSummaryModel.class)
  public ResponseEntity<CursorCollectionModel<TaskSummaryModel>> archiveTasks(
      @Parameter(
              description = "Filter: 'all' (Standard), 'done' (erledigt) oder 'left' (verlassen).")
          @RequestParam(required = false)
          final String filter,
      @Parameter(description = "Opaker Keyset-Cursor fuer die naechste Seite (leer = erste Seite).")
          @RequestParam(required = false)
          final String cursor,
      @Parameter(description = "Seitengroesse, geclampt auf [1, 100]. Default: 50.")
          @RequestParam(required = false, defaultValue = "50")
          final int limit) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return ResponseEntity.ok(
        snapshotService.loadArchivePage(actor.user().id(), filter, cursor, limit));
  }
}
