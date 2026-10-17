package de.brasi.nova.taskmanagement.notification;

import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer Benachrichtigungen (arch-v2 §7).
 *
 * <p>Jeder Handler traegt eine {@link RequiresPermission}-Annotation (Pflicht gemaess arch-v2 §8).
 * Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}),
 * NIEMALS aus Client-Eingabedaten.
 *
 * <p>Route-Reihenfolge: {@code /read-all} wird VOR {@code /{id}/read} gemappt, damit
 * Spring den konkreten Pfad vor dem variablen matcht und {@code id=read-all} nicht faengt.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private final NotificationQueryService queryService;
  private final UserAccessor userAccessor;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public NotificationController(
      final NotificationQueryService queryService,
      final UserAccessor userAccessor) {
    this.queryService = queryService;
    this.userAccessor = userAccessor;
  }

  /**
   * Liefert eine Seite von Benachrichtigungen fuer den angemeldeten Benutzer.
   *
   * @param filter   "all" | "unread" (default: all)
   * @param cursor   opaker Cursor fuer Keyset-Paginierung (null = erste Seite)
   * @param limit    Seitengroesse (default 50, geclampt auf [1, 100])
   * @return 200 OK mit {@link NotificationPage}
   */
  @GetMapping
  @RequiresPermission(TaskManagementPermissions.NOTIFICATION_READ)
  public ResponseEntity<NotificationPage> listNotifications(
      @RequestParam(required = false) final String filter,
      @RequestParam(required = false) final String cursor,
      @RequestParam(required = false, defaultValue = "50") final int limit) {

    final CurrentActor actor = CurrentActor.require(userAccessor);
    final NotificationPage page = queryService.listFor(
        actor.user().id(),
        actor.tenant().id(),
        filter,
        cursor,
        limit);
    return ResponseEntity.ok(page);
  }

  /**
   * Markiert alle eigenen ungelesenen Benachrichtigungen als gelesen.
   *
   * <p>Bewusst EINE separate Mapping-Methode (kein Wildcard), damit Spring {@code /read-all}
   * nicht als {@code /{id}=read-all} interpretiert.
   *
   * @return 204 No Content
   */
  @PutMapping("/read-all")
  @RequiresPermission(TaskManagementPermissions.NOTIFICATION_WRITE)
  public ResponseEntity<Void> markAllRead() {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    queryService.markAllRead(actor.user().id(), actor.tenant().id());
    return ResponseEntity.noContent().build();
  }

  /**
   * Markiert eine einzelne Benachrichtigung als gelesen.
   *
   * @param id externe UUID der Benachrichtigung
   * @return 204 No Content
   */
  @PutMapping("/{id}/read")
  @RequiresPermission(TaskManagementPermissions.NOTIFICATION_WRITE)
  public ResponseEntity<Void> markRead(@PathVariable final UUID id) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    queryService.markRead(id, actor.user().id(), actor.tenant().id());
    return ResponseEntity.noContent().build();
  }
}
