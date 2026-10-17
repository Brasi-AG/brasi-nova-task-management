package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.participant.model.ExitInput;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer Assignee-Verwaltung und Austritt (arch-v2 §7).
 *
 * <p>Jeder Handler traegt eine {@link RequiresPermission}-Annotation (Pflicht gemaess arch-v2 §8).
 * Actor-UUID kommt ausschliesslich aus dem Security-Context, NIEMALS aus Client-Eingabedaten.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class AssigneeController {

  private final AssigneeService assigneeService;

  /** Konstruktor-Injektion des {@link AssigneeService}. */
  public AssigneeController(final AssigneeService assigneeService) {
    this.assigneeService = assigneeService;
  }

  /**
   * Aktualisiert die Assignee-Menge einer Aufgabe (PUT-Semantik: Ziel-Menge).
   *
   * <p>Neue Assignees erhalten eine ASSIGN-Notification; abgewaehlte werden mit
   * EXIT_KIND=UNSUBSCRIBED markiert.
   *
   * @param id    externe UUID der Aufgabe
   * @param input Ziel-Assignee-Menge
   * @return 204 No Content
   */
  @PutMapping("/{id}/assignees")
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> updateAssignees(
      @PathVariable final UUID id, @RequestBody final AssigneeUpdateInput input) {
    assigneeService.updateAssignees(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Aktueller Akteur verlaesst die Aufgabe (HANDOVER oder UNSUBSCRIBED).
   *
   * <p>Bei HANDOVER wird eine HANDOVER-Notification emittiert.
   *
   * @param id    externe UUID der Aufgabe
   * @param input Austritts-Art
   * @return 204 No Content
   */
  @PostMapping("/{id}/exit")
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> exit(
      @PathVariable final UUID id, @RequestBody final ExitInput input) {
    assigneeService.exit(id, input);
    return ResponseEntity.noContent().build();
  }
}
