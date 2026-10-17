package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import de.brasi.nova.taskmanagement.task.model.TaskDetailModel;
import de.brasi.nova.taskmanagement.task.model.TaskPatchInput;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
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
 * <p>Jeder Handler traegt eine {@link RequiresPermission}-Annotation mit der vollen Authority
 * (Pflicht gemaess arch-v2 §8 + Handler-Sentinel-IT).
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
  @PostMapping
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Map<String, UUID>> create(@RequestBody final TaskCreateInput input) {
    final UUID id = commandService.create(input);
    return ResponseEntity
        .created(URI.create("/api/v1/tasks/" + id))
        .body(Map.of("id", id));
  }

  /**
   * Patcht eine bestehende Aufgabe (title, dueDate, statusCode).
   *
   * @param id    externe UUID der Aufgabe
   * @param input Patch-Eingabe
   * @return 204 No Content
   */
  @PatchMapping("/{id}")
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> patch(
      @PathVariable final UUID id, @RequestBody final TaskPatchInput input) {
    commandService.patch(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Liefert das Detail einer Aufgabe und setzt READ_AT fuer den aktuellen Benutzer.
   *
   * @param id externe UUID der Aufgabe
   * @return 200 OK mit {@link TaskDetailModel}
   */
  @GetMapping("/{id}")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<TaskDetailModel> detail(@PathVariable final UUID id) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID userRef = actor.user().id();

    final TaskDetailModel model = detailService.loadDetail(id, tenantId);

    // READ_AT fuer den aktuellen Benutzer setzen (Best-Effort, kein Fehler bei fehlendem State)
    detailService.markReadForUser(id, tenantId, userRef, userStateService);

    return ResponseEntity.ok(model);
  }
}
