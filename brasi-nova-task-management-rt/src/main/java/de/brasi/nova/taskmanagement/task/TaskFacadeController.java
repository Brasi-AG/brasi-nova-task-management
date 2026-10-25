package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.task.model.TaskCreateFromSourceInput;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import de.brasi.nova.taskmanagement.task.model.TaskDetailModel;
import de.brasi.nova.taskmanagement.task.model.TaskPatchInput;
import de.brasi.nova.taskmanagement.web.model.CreatedResourceResponse;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer Aufgaben (arch-v2 §7).
 *
 * <p>Die manuelle {@code create}-Route traegt {@code TASK_WRITE}; {@code createFromSource} traegt
 * die eigene {@code TASK_CREATE}-Authority. Die member-scoped Handler {@code detail} (GET) und
 * {@code patch} (PATCH) sind Standard-Selfservice (authenticated-only via KSF-Filter-Chain); ihre
 * Autorisierung erfolgt objekt-level ueber den {@code TaskMembershipGuard} im jeweiligen Service
 * (Non-/Ex-Member -> neutrale 404), nicht mehr ueber ein grobes {@code @RequiresPermission}-Gate.
 *
 * <p>Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}), NIEMALS aus
 * Client-Eingabedaten.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskFacadeController {

  private final TaskCommandService commandService;
  private final TaskSourceCreateService sourceCreateService;
  private final TaskDetailService detailService;
  private final UserStateService userStateService;
  private final UserAccessor userAccessor;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public TaskFacadeController(
      final TaskCommandService commandService,
      final TaskSourceCreateService sourceCreateService,
      final TaskDetailService detailService,
      final UserStateService userStateService,
      final UserAccessor userAccessor) {
    this.commandService = commandService;
    this.sourceCreateService = sourceCreateService;
    this.detailService = detailService;
    this.userStateService = userStateService;
    this.userAccessor = userAccessor;
  }

  /**
   * Legt eine neue Aufgabe an.
   *
   * <p>Gibt HTTP 201 mit {@code {"id": "<uuid>"}} zurueck.
   *
   * @param input Eingabe-DTO
   * @return 201 Created mit Body {@code {"id": "<uuid>"}}
   */
  @Operation(
      summary = "Aufgabe anlegen",
      description =
          "Legt eine manuelle Aufgabe ohne Quellkontext an. Direction wird serverseitig auf CUSTOMER_INTERNAL erzwungen."
              + " Der angemeldete Benutzer wird automatisch als Creator und erster Assignee eingetragen."
              + " Optional koennen initiale assignees (UUIDs aktiver Benutzer des Mandanten), tagIds (UUIDs"
              + " aktiver globaler Katalog-Tags) sowie die persoenlichen Marker isPinned/isFlagged des"
              + " Erstellers mitgegeben werden. Die Anlage ist atomar (All-or-Nothing): ist ein assignee"
              + " oder tag unbekannt/inaktiv, wird mit 400 abgelehnt und KEINE Aufgabe angelegt.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Aufgabe erfolgreich angelegt.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CreatedResourceResponse.class),
                examples =
                    @ExampleObject(value = "{\"id\": \"550e8400-e29b-41d4-a716-446655440000\"}"))),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Eingabe (z.B. source auf manueller Route, unbekannter"
                + " priorityCode, unbekannter/inaktiver Assignee oder Tag, mehr als 100"
                + " assignees/tagIds)."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(responseCode = "403", description = "Fehlende TASK_WRITE-Berechtigung.")
  })
  @PostMapping
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Map<String, UUID>> create(@RequestBody final TaskCreateInput input) {
    if (input.source() != null) {
      throw new io.keel.framework.api.validation.EntityValidationException(
          "Quellkontext erfordert /api/v1/tasks/from-source.");
    }
    final UUID id = commandService.create(input);
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + id)).body(Map.of("id", id));
  }

  /** Context create has its own exact authority and mandatory idempotency receipt. */
  @Operation(
      summary = "Aufgabe aus registriertem Quellkontext anlegen",
      description =
          "Erfordert TASK_CREATE und einen aktuell lesbaren Quellkontext. "
              + "Initiale Assignees duerfen aktive Benutzer desselben Mandanten sein. "
              + "Ein gleicher Idempotency-Key mit gleicher effektiver Eingabe liefert die bestehende "
              + "Aufgaben-ID nur bei weiterhin lesbarer Quelle und aktiver Teilnahme zurueck.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Neue Aufgabe atomar angelegt.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CreatedResourceResponse.class))),
    @ApiResponse(
        responseCode = "200",
        description = "Idempotenter Replay derselben Aufgabe.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CreatedResourceResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Quelle, Key oder Create-Eingabe ungueltig.",
        content = @Content),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content = @Content),
    @ApiResponse(
        responseCode = "403",
        description = "Fehlende TASK_CREATE-Berechtigung.",
        content = @Content),
    @ApiResponse(
        responseCode = "404",
        description = "Quelle oder aktive Teilnahme nicht lesbar.",
        content = @Content),
    @ApiResponse(
        responseCode = "409",
        description = "Key fuer andere Eingabe verwendet.",
        content = @Content)
  })
  @PostMapping("/from-source")
  @RequiresPermission(TaskManagementPermissions.TASK_CREATE)
  public ResponseEntity<Map<String, UUID>> createFromSource(
      @Parameter(
              name = "Idempotency-Key",
              in = ParameterIn.HEADER,
              required = true,
              description = "1 bis 128 sichtbare ASCII-Zeichen; Mandant/Akteur-spezifisch.",
              schema =
                  @Schema(
                      type = "string",
                      minLength = 1,
                      maxLength = 128,
                      pattern = "^[!-~]{1,128}$"))
          @RequestHeader(value = "Idempotency-Key", required = false)
          final String idempotencyKey,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = TaskCreateFromSourceInput.class)))
          @RequestBody
          final TaskCreateInput input) {
    final TaskSourceCreateResult result = sourceCreateService.create(input, idempotencyKey);
    if (!result.created()) {
      return ResponseEntity.ok(Map.of("id", result.taskId()));
    }
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + result.taskId()))
        .body(Map.of("id", result.taskId()));
  }

  /**
   * Patcht eine bestehende Aufgabe (title, dueDate, statusCode, tagIds, startDate).
   *
   * @param id externe UUID der Aufgabe
   * @param input Patch-Eingabe
   * @return 204 No Content
   */
  @Operation(
      summary = "Aufgabe patchen",
      description =
          "Aktualisiert einzelne Felder einer Aufgabe (title, dueDate, statusCode, tagIds,"
              + " startDate). Nur gesendete Felder werden geaendert (PatchValue-Semantik: absent ="
              + " unveraendert, null = Datumsfeld loeschen).")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Aufgabe erfolgreich aktualisiert."),
    @ApiResponse(
        responseCode = "400",
        description = "Ungueltige Eingabe (z.B. unbekannter statusCode, unbekannte Tag-UUID)."),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @PatchMapping("/{id}")
  public ResponseEntity<Void> patch(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @RequestBody final TaskPatchInput input) {
    commandService.patch(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Liefert das Detail einer Aufgabe und setzt READ_AT fuer den aktuellen Benutzer.
   *
   * @param id externe UUID der Aufgabe
   * @return 200 OK mit {@link TaskDetailModel}
   */
  @Operation(
      summary = "Aufgabe laden",
      description =
          "Liefert alle Details einer Aufgabe inkl. Historieneintraegen."
              + " Setzt READ_AT fuer den angemeldeten Benutzer (Best-Effort).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Aufgabe erfolgreich geladen.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TaskDetailModel.class),
                examples =
                    @ExampleObject(
                        value =
                            "{"
                                + "\"id\": \"550e8400-e29b-41d4-a716-446655440000\","
                                + "\"title\": \"Jahresabschluss vorbereiten\","
                                + "\"description\": null,"
                                + "\"statusCode\": \"OPEN\","
                                + "\"priorityCode\": \"NORMAL\","
                                + "\"directionCode\": \"CUSTOMER_INTERNAL\","
                                + "\"startDate\": null,"
                                + "\"dueDate\": \"2026-12-31\","
                                + "\"lastActivityAt\": \"2026-10-18T08:00:00Z\","
                                + "\"commentCount\": 0,"
                                + "\"history\": []"
                                + "}"))),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @GetMapping("/{id}")
  public ResponseEntity<TaskDetailModel> detail(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID userRef = actor.user().id();

    final TaskDetailModel model =
        detailService.loadDetail(id, tenantId, actor.tenant().uuid(), userRef);

    // READ_AT fuer den aktuellen Benutzer setzen (Best-Effort, kein Fehler bei fehlendem State)
    detailService.markReadForUser(id, tenantId, userRef, userStateService);

    return ResponseEntity.ok(model);
  }
}
