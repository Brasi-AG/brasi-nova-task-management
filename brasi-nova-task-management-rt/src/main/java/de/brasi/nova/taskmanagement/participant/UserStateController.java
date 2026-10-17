package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.participant.model.MarkerInput;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer persoenliche Marker (pin/flag/mute) (arch-v2 §7).
 *
 * <p>Jeder Handler traegt eine {@link RequiresPermission}-Annotation (Pflicht gemaess arch-v2 §8).
 * Die fachliche Logik (Actor-Aufloesung, 404-Masking, Inbox-Invariante, Persistenz) liegt
 * ausschliesslich im {@link MarkerService}.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class UserStateController {

  private final MarkerService markerService;

  /** Konstruktor-Injektion des {@link MarkerService}. */
  public UserStateController(final MarkerService markerService) {
    this.markerService = markerService;
  }

  /**
   * Setzt den Pinned-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * @param id    externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = angepinnt, {@code false} = nicht angepinnt)
   * @return 204 No Content
   */
  @PutMapping("/{id}/pin")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<Void> pin(
      @PathVariable final UUID id, @RequestBody final MarkerInput input) {
    markerService.setPinned(id, input.value());
    return ResponseEntity.noContent().build();
  }

  /**
   * Setzt den Flagged-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * @param id    externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = markiert, {@code false} = nicht markiert)
   * @return 204 No Content
   */
  @PutMapping("/{id}/flag")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<Void> flag(
      @PathVariable final UUID id, @RequestBody final MarkerInput input) {
    markerService.setFlagged(id, input.value());
    return ResponseEntity.noContent().build();
  }

  /**
   * Setzt den Muted-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * @param id    externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = stummgeschaltet, {@code false} = nicht stumm)
   * @return 204 No Content
   */
  @PutMapping("/{id}/mute")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<Void> mute(
      @PathVariable final UUID id, @RequestBody final MarkerInput input) {
    markerService.setMuted(id, input.value());
    return ResponseEntity.noContent().build();
  }
}
