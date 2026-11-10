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
 * <p>Route: {@code GET /api/v1/tasks/participants} — fester Pfad, kein Pfad-Parameter. Spring
 * ordnet konkreten Pfad ({@code participants}) STETS vor dem variablen Pfad ({@code {id}} in {@link
 * de.brasi.nova.taskmanagement.task.TaskFacadeController}) ein.
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
      final ParticipantSearchService searchService, final UserAccessor userAccessor) {
    this.searchService = searchService;
    this.userAccessor = userAccessor;
  }

  /**
   * Sucht nach aktiven Benutzern des aktuellen Mandanten als Teilnehmer-Kandidaten.
   *
   * <p>Route {@code /api/v1/tasks/participants} wird NICHT als {@code {id}=participants} gefangen —
   * Spring bevorzugt den konkreten Pfad (Routing-Beweis per IT).
   *
   * @param q Suchbegriff (null/blank = kein Filter)
   * @param cursor opaker Keyset-Cursor (null = erste Seite)
   * @param limit Seitengroesse (default 50, geclampt [1, 100])
   * @return 200 OK mit {@link ParticipantPage}
   */
  @Operation(
      summary = "Teilnehmer-Kandidaten suchen",
      description =
          """
          Liefert die AKTIVEN BENUTZER (mit Benutzerkonto) des EIGENEN MANDANTEN (Mandant aus dem \
          JWT) als Kandidaten fuer Zuweisung (assignees), Beteiligte (participants), Uebergabe und \
          Erwaehnungen (mentions). Mitarbeiter-Stammdaten ohne Benutzerkonto werden nicht geliefert.

          Verbindlich: Fuer diese Schreibaufrufe ist die `userRef` aus dieser Antwort zu \
          verwenden. Unbekannte, inaktive oder mandantenfremde userRefs werden dort abgelehnt \
          (in der Regel 400; bei Uebergabe durch Nicht-Mitglieder ohne TASK_WRITE ggf. 403). \
          Mentions benachrichtigen nur aktive Mitglieder der Aufgabe; andere userRefs (auch \
          gueltige aus dieser Liste) werden dort still verworfen.

          Ohne q (oder bei leerem q bzw. nur Leerzeichen) werden ALLE aktiven Benutzer des \
          Mandanten geliefert (seitenweise, siehe limit/cursor). q ist ein reiner \
          Teiltreffer-Suchfilter, kein Kontext-Parameter und kein exakter Lookup.

          Die Reihenfolge ist stabil (technisch nach userRef in GUID-Sortierung, fachlich \
          bedeutungslos), aber NICHT alphabetisch. Das Frontend sortiert fuer die Anzeige selbst \
          (z.B. nach fullName).""")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Seite mit Teilnehmer-Kandidaten (aktive Benutzer des eigenen Mandanten).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ParticipantPage.class),
                examples = {
                  @ExampleObject(
                      name = "alle-kandidaten",
                      summary = "Ohne q: alle aktiven Benutzer des Mandanten (letzte Seite)",
                      value =
                          """
                          {"items": [\
                          {"userRef": "550e8400-e29b-41d4-a716-446655440000",\
                           "loginName": "max.mustermann@example.com",\
                           "fullName": "Max Mustermann"},\
                          {"userRef": "660e8400-e29b-41d4-a716-446655440001",\
                           "loginName": "erika.musterfrau@example.com",\
                           "fullName": "Erika Musterfrau"},\
                          {"userRef": "770e8400-e29b-41d4-a716-446655440002",\
                           "loginName": "hans.beispiel@example.com",\
                           "fullName": "Hans Beispiel"}],\
                          "nextCursor": null,\
                          "hasMore": false}"""),
                  @ExampleObject(
                      name = "suche",
                      summary = "Mit q=musterfrau: Teiltreffer auf loginName oder fullName",
                      value =
                          """
                          {"items": [\
                          {"userRef": "660e8400-e29b-41d4-a716-446655440001",\
                           "loginName": "erika.musterfrau@example.com",\
                           "fullName": "Erika Musterfrau"}],\
                          "nextCursor": null,\
                          "hasMore": false}"""),
                  @ExampleObject(
                      name = "erste-seite-mit-weiteren",
                      summary = "Mit limit=2: erste Seite, weitere Seiten vorhanden",
                      value =
                          """
                          {"items": [\
                          {"userRef": "550e8400-e29b-41d4-a716-446655440000",\
                           "loginName": "max.mustermann@example.com",\
                           "fullName": "Max Mustermann"},\
                          {"userRef": "660e8400-e29b-41d4-a716-446655440001",\
                           "loginName": "erika.musterfrau@example.com",\
                           "fullName": "Erika Musterfrau"}],\
                          "nextCursor": "NjYwZTg0MDAtZTI5Yi00MWQ0LWE3MTYtNDQ2NjU1NDQwMDAx",\
                          "hasMore": true}""")
                })),
    @ApiResponse(
        responseCode = "400",
        description = "Ungueltiger cursor oder nicht-numerisches limit.",
        content =
            @Content(
                mediaType = "application/json",
                examples =
                    @ExampleObject(
                        name = "ungueltiger-cursor",
                        summary = "cursor ist nicht dekodierbar",
                        value =
                            """
                            {"timestamp": "2026-11-10T14:13:16.4007581",\
                             "status": 400,\
                             "error": "Bad Request",\
                             "message": "Ungueltiger cursor.",\
                             "path": "/task-management/api/v1/tasks/participants",\
                             "errorKey": "NOVA_VALIDATION_FAILED",\
                             "traceId": null}"""))),
    @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
    @ApiResponse(
        responseCode = "403",
        description = "Fehlende Berechtigung task-management:TASK_READ.",
        content =
            @Content(
                mediaType = "application/json",
                examples =
                    @ExampleObject(
                        name = "fehlende-berechtigung",
                        summary = "Permission task-management:TASK_READ fehlt",
                        value =
                            """
                            {"timestamp": "2026-11-10T14:13:16.4007581",\
                             "status": 403,\
                             "error": "Forbidden",\
                             "message": "Zugriff verweigert. Sie haben nicht die erforderlichen \
                            Berechtigungen, um diese Aktion auszuführen.",\
                             "path": "/task-management/api/v1/tasks/participants",\
                             "errorKey": "NOVA_ACCESS_DENIED",\
                             "traceId": null}""")))
  })
  @GetMapping("/participants")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<ParticipantPage> searchParticipants(
      @Parameter(
              description =
                  "Suchfilter: Teiltreffer (enthaelt) auf loginName ODER fullName,"
                      + " Gross-/Kleinschreibung egal (gemaess DB-Kollation). Leer oder nur"
                      + " Leerzeichen = kein Filter (alle aktiven Benutzer). Auch eine"
                      + " vollstaendige Mailadresse ist nur ein Teiltreffer-Filter, kein exakter"
                      + " Lookup (z.B. trifft anna@ auch hanna@).",
              example = "musterfrau")
          @RequestParam(required = false)
          final String q,
      @Parameter(
              description =
                  "Opaker Cursor. Den nextCursor der vorigen Antwort unveraendert UND mit"
                      + " identischem q weitergeben (wird serverseitig nicht geprueft; ein"
                      + " anderes q fuehrt zu uebersprungenen Treffern). Leer = erste Seite."
                      + " Ungueltiger Cursor -> 400.")
          @RequestParam(required = false)
          final String cursor,
      @Parameter(
              description =
                  "Seitengroesse. Default 50; Werte ausserhalb [1,100] werden geklemmt (0 -> 1,"
                      + " 500 -> 100).")
          @RequestParam(required = false, defaultValue = "50")
          final int limit) {

    final CurrentActor actor = CurrentActor.require(userAccessor);
    final ParticipantPage page = searchService.search(actor.tenant().id(), q, cursor, limit);
    return ResponseEntity.ok(page);
  }
}
