package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import de.brasi.nova.taskmanagement.task.model.TaskDetailModel;
import de.brasi.nova.taskmanagement.task.model.TaskPatchInput;
import de.brasi.nova.taskmanagement.web.model.CreatedResourceResponse;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer Aufgaben (arch-v2 §7).
 *
 * <p>{@code create} traegt die grobe {@link RequiresPermission}-Authority (TASK_WRITE). Die
 * member-scoped Handler {@code detail} (GET) und {@code patch} (PATCH) sind
 * Standard-Selfservice (authenticated-only via KSF-Filter-Chain); ihre Autorisierung erfolgt
 * objekt-level ueber den {@code TaskMembershipGuard} im jeweiligen Service (Non-/Ex-Member ->
 * neutrale 404), nicht mehr ueber ein grobes {@code @RequiresPermission}-Gate.
 *
 * <p>Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}),
 * NIEMALS aus Client-Eingabedaten.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskFacadeController {

  private final TaskCommandService commandService;
  private final TaskDetailService detailService;
  private final UserStateService userStateService;
  private final UserAccessor userAccessor;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public TaskFacadeController(
      final TaskCommandService commandService,
      final TaskDetailService detailService,
      final UserStateService userStateService,
      final UserAccessor userAccessor) {
    this.commandService = commandService;
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
      description = "Legt eine neue Aufgabe an. Direction wird serverseitig auf CUSTOMER_INTERNAL erzwungen."
          + " Der angemeldete Benutzer wird automatisch als Creator und erster Assignee eingetragen."
          + " Optional koennen initiale assignees (UUIDs aktiver Benutzer des Mandanten), tagIds (UUIDs"
          + " aktiver globaler Katalog-Tags) sowie die persoenlichen Marker isPinned/isFlagged des"
          + " Erstellers mitgegeben werden. Die Anlage ist atomar (All-or-Nothing): ist ein assignee"
          + " oder tag unbekannt/inaktiv, wird mit 400 abgelehnt und KEINE Aufgabe angelegt.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "201",
          description = "Aufgabe erfolgreich angelegt.",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = CreatedResourceResponse.class),
              examples = @ExampleObject(value = "{\"id\": \"550e8400-e29b-41d4-a716-446655440000\"}"))),
      @ApiResponse(responseCode = "400",
          description = "Ungueltige Eingabe (z.B. unbekannter priorityCode, unbekannter/inaktiver"
              + " Assignee oder Tag, mehr als 100 assignees/tagIds)."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_WRITE-Berechtigung.")
  })
  @PostMapping
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Map<String, UUID>> create(@RequestBody final TaskCreateInput input) {
    final UUID id = commandService.create(input);
    return ResponseEntity
        .created(URI.create("/api/v1/tasks/" + id))
        .body(Map.of("id", id));
  }

  /**
   * Patcht eine bestehende Aufgabe (title, dueDate, statusCode, tagIds, startDate).
   *
   * @param id    externe UUID der Aufgabe
   * @param input Patch-Eingabe
   * @return 204 No Content
   */
  @Operation(
      summary = "Aufgabe patchen",
      description = "Aktualisiert einzelne Felder einer Aufgabe (title, dueDate, statusCode, tagIds,"
          + " startDate). Nur gesendete Felder werden geaendert (PatchValue-Semantik: absent ="
          + " unveraendert, null = Datumsfeld loeschen).")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Aufgabe erfolgreich aktualisiert."),
      @ApiResponse(responseCode = "400", description = "Ungueltige Eingabe (z.B. unbekannter statusCode, unbekannte Tag-UUID)."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @PatchMapping("/{id}")
  public ResponseEntity<Void> patch(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable final UUID id,
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
      description = "Liefert alle Details einer Aufgabe inkl. Historieneintraegen."
          + " Setzt READ_AT fuer den angemeldeten Benutzer (Best-Effort).")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Aufgabe erfolgreich geladen.",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = TaskDetailModel.class),
              examples = @ExampleObject(value = "{"
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
      @ApiResponse(responseCode = "404", description = "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).")
  })
  @GetMapping("/{id}")
  public ResponseEntity<TaskDetailModel> detail(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable final UUID id) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID userRef = actor.user().id();

    final TaskDetailModel model = detailService.loadDetail(id, tenantId, actor.tenant().uuid(), userRef);

    // READ_AT fuer den aktuellen Benutzer setzen (Best-Effort, kein Fehler bei fehlendem State)
    detailService.markReadForUser(id, tenantId, userRef, userStateService);

    return ResponseEntity.ok(model);
  }
}
