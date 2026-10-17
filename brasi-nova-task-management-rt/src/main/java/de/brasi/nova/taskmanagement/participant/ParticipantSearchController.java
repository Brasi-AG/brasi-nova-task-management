package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.participant.model.ParticipantPage;
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
 * Handgeschriebener Facade-Controller fuer die Teilnehmer-Suche (arch-v2 §7).
 *
 * <p>Route: {@code GET /api/v1/tasks/participants} — fester Pfad, kein Pfad-Parameter.
 * Spring ordnet konkreten Pfad ({@code participants}) STETS vor dem variablen Pfad
 * ({@code {id}} in {@link de.brasi.nova.taskmanagement.task.TaskFacadeController}) ein.
 *
 * <p>Tenant-Kontext: wird vom KSF-Filter aus dem JWT gesetzt; kein {@code runWithTenant} noetig.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class ParticipantSearchController {

  private final ParticipantSearchService searchService;
  private final UserAccessor userAccessor;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public ParticipantSearchController(
      final ParticipantSearchService searchService,
      final UserAccessor userAccessor) {
    this.searchService = searchService;
    this.userAccessor = userAccessor;
  }

  /**
   * Sucht nach aktiven Benutzern des aktuellen Mandanten als Teilnehmer-Kandidaten.
   *
   * <p>Route {@code /api/v1/tasks/participants} wird NICHT als {@code {id}=participants}
   * gefangen — Spring bevorzugt den konkreten Pfad (Routing-Beweis per IT).
   *
   * @param q      Suchbegriff (null/blank = kein Filter)
   * @param cursor opaker Keyset-Cursor (null = erste Seite)
   * @param limit  Seitengroesse (default 50, geclampt [1, 100])
   * @return 200 OK mit {@link ParticipantPage}
   */
  @GetMapping("/participants")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<ParticipantPage> searchParticipants(
      @RequestParam(required = false) final String q,
      @RequestParam(required = false) final String cursor,
      @RequestParam(required = false, defaultValue = "50") final int limit) {

    final CurrentActor actor = CurrentActor.require(userAccessor);
    final ParticipantPage page =
        searchService.search(actor.tenant().id(), q, cursor, limit);
    return ResponseEntity.ok(page);
  }
}
