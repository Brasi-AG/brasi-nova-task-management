package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.participant.model.ParticipantPage;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
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
  @Operation(
      summary = "Teilnehmer-Kandidaten suchen",
      description = "Sucht aktive Benutzer des aktuellen Mandanten als Kandidaten fuer Aufgaben-Zuweisungen."
          + " Unterstuetzt Freitextsuche (q) und Keyset-Paginierung.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Seite mit Teilnehmer-Kandidaten.",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ParticipantPage.class),
              examples = @ExampleObject(value = "{"
                  + "\"items\": [{\"userRef\": \"550e8400-e29b-41d4-a716-446655440000\","
                  + "\"loginName\": \"m.mustermann@example.com\","
                  + "\"fullName\": \"Max Mustermann\"}],"
                  + "\"nextCursor\": null,"
                  + "\"hasMore\": false"
                  + "}"))),
      @ApiResponse(responseCode = "400", description = "Ungueltiger cursor."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende TASK_READ-Berechtigung.")
  })
  @GetMapping("/participants")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<ParticipantPage> searchParticipants(
      @Parameter(description = "Suchbegriff fuer Loginname oder vollstaendigen Namen (leer = kein Filter).")
      @RequestParam(required = false) final String q,
      @Parameter(description = "Opaker Keyset-Cursor fuer die naechste Seite (leer = erste Seite).")
      @RequestParam(required = false) final String cursor,
      @Parameter(description = "Seitengroesse, geclampt auf [1, 100]. Default: 50.")
      @RequestParam(required = false, defaultValue = "50") final int limit) {

    final CurrentActor actor = CurrentActor.require(userAccessor);
    final ParticipantPage page =
        searchService.search(actor.tenant().id(), q, cursor, limit);
    return ResponseEntity.ok(page);
  }
}
