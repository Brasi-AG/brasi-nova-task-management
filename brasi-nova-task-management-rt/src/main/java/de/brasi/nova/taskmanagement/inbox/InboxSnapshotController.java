package de.brasi.nova.taskmanagement.inbox;

import de.brasi.nova.taskmanagement.inbox.model.ArchiveSearchPageSchema;
import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.paging.CursorCollectionModel;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import io.keel.framework.api.openapi.annotation.ApiFindCollectionResponse;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.web.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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

  /**
   * Sucht im Archiv des angemeldeten Benutzers nach einem Stichwort (Intake #149).
   *
   * <p>Gleiche Semantik und gleiches Antwortformat wie {@link #archiveTasks} (Filter, Cursor,
   * Limit, asOf/Generation, 410), zusaetzlich das Pflicht-Stichwort {@code query}. Bewusst ohne
   * {@code ApiFindCollectionResponse}: der KSF-Customizer haengte sonst 403/404 ohne Inhalt an, die
   * dieser Endpunkt nie liefert. Das Antwortschema beschreibt {@link ArchiveSearchPageSchema.Page}.
   *
   * @param query Suchbegriff (Pflicht; getrimmt, 1 bis 200 Zeichen)
   * @param filter {@code all} (default) | {@code done} | {@code left}
   * @param cursor opaker Cursor (null = erste Seite mit frischem asOf)
   * @param limit Seitengroesse (default 50, geclampt auf [1, 100])
   * @return 200 OK mit {@link CursorCollectionModel} von {@link TaskSummaryModel}
   */
  @Operation(
      summary = "Archivierte Aufgaben nach Stichwort durchsuchen",
      description =
          "Liefert eine Keyset-paginierte HAL-Collection der archivierten Aufgaben des angemeldeten"
              + " Benutzers, deren Titel, Beschreibung, Tag-Bezeichnung oder Anzeigename eines"
              + " aktiven Teilnehmers (Ersteller, Zugewiesene, Beteiligte) den Suchbegriff als"
              + " Teilstring enthaelt; Gross- und Kleinschreibung spielen keine Rolle, die Zeichen"
              + " %, _ und [ gelten woertlich. Jede Aufgabe erscheint hoechstens einmal. filter,"
              + " cursor und limit wirken wie beim Archiv; der Cursor gilt nur zusammen mit"
              + " demselben Suchbegriff und Filter.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Trefferseite der Archivsuche (leer, wenn nichts passt).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Page.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"_embedded\":{\"tasks\":[{"
                                + "\"id\":\"3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b\","
                                + "\"modelState\":\"ACTIVE\","
                                + "\"_links\":{\"self\":{\"href\":"
                                + "\"/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b\"}},"
                                + "\"title\":\"Quartalsbericht erstellen\","
                                + "\"statusCode\":\"DONE\",\"priorityCode\":\"NORMAL\","
                                + "\"dueDate\":\"2026-12-31\","
                                + "\"lastActivityAt\":\"2026-11-16T09:30:00Z\","
                                + "\"commentCount\":2,"
                                + "\"createdById\":\"550e8400-e29b-41d4-a716-446655440000\","
                                + "\"createdAt\":\"2026-11-01T06:30:00Z\","
                                + "\"description\":\"Zahlen aus dem Lager abgleichen\","
                                + "\"assigneeIds\":[\"550e8400-e29b-41d4-a716-446655440000\"],"
                                + "\"participantIds\":[],"
                                + "\"tagIds\":[\"7d9f0c2a-1b3e-4f5a-8c6d-9e0f1a2b3c4d\"],"
                                + "\"isPinned\":false,\"isFlagged\":false,\"isMuted\":false,"
                                + "\"isDone\":true,"
                                + "\"exit\":{\"kind\":\"handover\",\"handedOverToIds\":"
                                + "[\"6a1e2b3c-4d5e-4f60-8172-93a4b5c6d7e8\"]}}]},"
                                + "\"_links\":{\"create\":{\"href\":\"/api/v1/tasks\"}},"
                                + "\"nextCursor\":"
                                + "\"MjAyNi0xMS0xN1QxMDowMDowMHwyMDI2LTExLTE2VDA5OjMwOjAwfGFiY3wy\","
                                + "\"hasMore\":true,"
                                + "\"asOf\":\"2026-11-17T10:00:00Z\"}"))),
    @ApiResponse(
        responseCode = "400",
        description =
            "Suchbegriff fehlt, ist leer oder laenger als 200 Zeichen; ungueltiger filter-Wert"
                + " oder cursor.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"timestamp\":\"2026-11-17T10:00:00.123456\",\"status\":400,"
                                + "\"error\":\"Bad Request\","
                                + "\"message\":\"Der Parameter 'query' darf nicht leer sein.\","
                                + "\"path\":\"/api/v1/tasks/archive/search\"}"))),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                                + "\"detail\":\"Authentifizierung erforderlich\"}"))),
    @ApiResponse(
        responseCode = "410",
        description =
            "Snapshot abgelaufen; Client muss neu laden. Folgeseite (cursor): Mitgliedschaft des"
                + " Benutzers hat sich geaendert. Erste Seite (ohne cursor): unter anhaltender"
                + " Mitgliedschaftsaenderung nicht konsistent erzeugbar (seltene Degradation).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"timestamp\":\"2026-11-17T10:00:00.123456\",\"status\":410,"
                                + "\"error\":\"Gone\","
                                + "\"message\":\"Snapshot abgelaufen\","
                                + "\"path\":\"/api/v1/tasks/archive/search\"}")))
  })
  @GetMapping("/archive/search")
  public ResponseEntity<CursorCollectionModel<TaskSummaryModel>> searchArchive(
      @Parameter(
              description =
                  "Suchbegriff (Pflicht). Wird getrimmt; leer oder laenger als 200 Zeichen ist 400.",
              required = true,
              example = "Quartalsbericht")
          @RequestParam(required = false)
          final String query,
      @Parameter(
              description = "Filter: 'all' (Standard), 'done' (erledigt) oder 'left' (verlassen).",
              example = "all")
          @RequestParam(required = false)
          final String filter,
      @Parameter(description = "Opaker Keyset-Cursor fuer die naechste Seite (leer = erste Seite).")
          @RequestParam(required = false)
          final String cursor,
      @Parameter(description = "Seitengroesse, geclampt auf [1, 100]. Standard: 50.")
          @RequestParam(required = false, defaultValue = "50")
          final int limit) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return ResponseEntity.ok(
        snapshotService.searchArchivePage(actor.user().id(), query, filter, cursor, limit));
  }
}
