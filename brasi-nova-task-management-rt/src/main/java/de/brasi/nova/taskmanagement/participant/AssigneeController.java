package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.inbox.model.ArchiveSearchPageSchema;
import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.participant.model.ExitInput;
import de.brasi.nova.taskmanagement.participant.model.ParticipantAddInput;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.task.TaskHateoas;
import io.keel.framework.api.hateoas.annotation.HateoasLinkTarget;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.web.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer Assignee-Verwaltung und Austritt (arch-v2 Abschnitt 7).
 *
 * <p>Die Admin-/Voll-Endpunkte ({@code updateAssignees}, {@code addParticipants}) tragen eine
 * {@link RequiresPermission}-Annotation (arch-v2 Abschnitt 8). {@code exit} ist seit dem
 * Standard-Selfservice (Slice 4) nur noch authenticated-only (anonym -&gt; 401 ueber die
 * KSF-Filter-Chain); der objekt-level Membership-Guard im {@link AssigneeService} ersetzt das grobe
 * Permission-Gate (grant-freier WRITE-Member darf austreten/uebergeben, die Handover-Grenze bleibt
 * TASK_WRITE-geschuetzt). Actor-UUID kommt ausschliesslich aus dem Security-Context, NIEMALS aus
 * Client-Eingabedaten.
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
   * <p>Neue Assignees erhalten eine ASSIGN-Notification und werden fuer diese Aufgabe entstummt
   * (der Mute-Marker wird zurueckgesetzt, Folge von Intake #158); unveraendert bleibende Assignees
   * behalten ihren Mute. Abgewaehlte werden mit EXIT_KIND=UNSUBSCRIBED markiert. Eine ausgetretene
   * oder deaktivierte Teilnehmerzeile einer neu zugewiesenen Person wird reaktiviert (Intake #163).
   *
   * @param id externe UUID der Aufgabe
   * @param input Ziel-Assignee-Menge
   * @return 204 No Content
   */
  @Operation(
      summary = "Assignees aktualisieren",
      description =
          "Ersetzt die aktuelle Assignee-Menge durch die gewuenschte Ziel-Menge (PUT-Set-Semantik)."
              + " Neu zugewiesene Benutzer erhalten eine ASSIGN-Notification."
              + " Neu zugewiesene Benutzer werden fuer diese Aufgabe entstummt: eine zuvor gesetzte"
              + " Stummschaltung (PUT /api/v1/tasks/{id}/mute) wird aufgehoben; das gilt auch fuer"
              + " zuvor ausgetretene oder als reine Teilnehmer gefuehrte Benutzer, die jetzt"
              + " zugewiesen werden. Assignees, die in der Ziel-Menge unveraendert bleiben, behalten"
              + " ihre Stummschaltung."
              + " Zuvor ausgetretene Personen oder solche mit deaktivierter Teilnehmerzeile (der"
              + " Benutzer selbst ist aktiv) werden dabei wieder als Assignee aufgenommen."
              + " Ein ausgelassener Creator wird nur de-assigned und bleibt Mitglied der Aufgabe"
              + " (kein Austritt).")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Assignees erfolgreich aktualisiert."),
    @ApiResponse(
        responseCode = "400",
        description = "Ungueltige Eingabe (z.B. unbekannte Benutzer-UUID, null-Elemente).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.BAD_REQUEST_ASSIGNEES))),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = ParticipantApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "403",
        description = "Fehlende TASK_WRITE-Berechtigung.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.FORBIDDEN_ASSIGNEES))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder keine aktive Mitgliedschaft (neutral"
                + " maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.NOT_FOUND_ASSIGNEES))),
    @ApiResponse(
        responseCode = "409",
        description =
            "Zwei Ursachen, in beiden Faellen wurde nichts geaendert. (1) Nebenlaeufiger"
                + " Versionskonflikt: paralleler Schreibzugriff auf die Aufgabe, z. B. gleichzeitige"
                + " Mitgliederaenderung, Kommentar oder Patch; die Anfrage gegen den frischen Stand"
                + " wiederholen. (2) Die vorhandene Teilnehmerzeile einer neu zuzuweisenden Person"
                + " ist keine PERSON-Zeile des Mandanten (TEAM-Zeile oder fremder Mandant,"
                + " Datenfehler); sie wird nicht ueberschrieben und die Anfrage scheitert, bis der"
                + " Datenbestand bereinigt ist. Ausgetretene Personen oder solche mit deaktivierter"
                + " Teilnehmerzeile (bei aktivem Benutzer) sind kein Konfliktgrund: sie werden"
                + " reaktiviert.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.CONFLICT_ASSIGNEES)))
  })
  @PutMapping("/{id}/assignees")
  @HateoasLinkTarget(TaskHateoas.Targets.UPDATE_ASSIGNEES)
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> updateAssignees(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description = "Gewuenschte Ziel-Menge der Assignees (ersetzt den aktuellen Stand).",
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = AssigneeUpdateInput.class),
                      examples = {
                        @ExampleObject(
                            name = "zuweisen",
                            summary = "Zwei Benutzer als Assignees setzen",
                            description =
                                "Neu hinzukommende Benutzer werden zugewiesen und entstummt;"
                                    + " nicht mehr genannte Assignees werden abgewaehlt.",
                            value =
                                "{\"assignees\":[\""
                                    + ParticipantApiExamples.USER_REF_A
                                    + "\",\""
                                    + ParticipantApiExamples.USER_REF_B
                                    + "\"]}"),
                        @ExampleObject(
                            name = "alle-abwaehlen",
                            summary = "Alle Nicht-Creator-Assignees abwaehlen",
                            description =
                                "Ein leeres Array leert die Menge; ein Creator wird nur"
                                    + " de-assigned und bleibt Mitglied.",
                            value = "{\"assignees\":[]}")
                      }))
          @RequestBody
          final AssigneeUpdateInput input) {
    assigneeService.updateAssignees(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Aktueller Akteur verlaesst die Aufgabe (HANDOVER oder UNSUBSCRIBED).
   *
   * <p>Bei HANDOVER werden die {@code handedOverToIds} zu Assignees und erhalten eine
   * HANDOVER-Notification. Ziele, die vorher kein aktiver Assignee waren, werden zudem fuer diese
   * Aufgabe entstummt (Mute-Marker zurueckgesetzt, Folge von Intake #158); ein bereits aktiver
   * Assignee behaelt seinen Mute. Der Akteur wird de-assignt. Mit {@code shouldStay=true} bleibt
   * der Akteur reiner Teilnehmer (kein Austritt). Ein Creator kann die Aufgabe nur per HANDOVER mit
   * {@code shouldStay=true} abgeben (sonst 409).
   *
   * @param id externe UUID der Aufgabe
   * @param input Austritts-Eingabe (exitKind, handedOverToIds, shouldStay)
   * @return 204 No Content
   */
  @Operation(
      summary = "Aufgabe verlassen / uebergeben",
      description =
          "Der angemeldete Benutzer verlaesst die Aufgabe oder uebergibt sie."
              + " exitKind: HANDOVER (Aufgabe uebergeben) oder UNSUBSCRIBED (Aufgabe abgemeldet)."
              + " Bei HANDOVER werden handedOverToIds zu Assignees."
              + " Ziele, die vorher kein aktiver Assignee waren, werden dabei fuer diese Aufgabe"
              + " entstummt: eine zuvor gesetzte Stummschaltung (PUT /api/v1/tasks/{id}/mute)"
              + " wird aufgehoben. Ein Ziel, das bereits aktiver Assignee ist, behaelt seine"
              + " Stummschaltung. Die HANDOVER-Benachrichtigung erreicht alle Ziele ohnehin."
              + " shouldStay=true laesst den Akteur als reinen Teilnehmer verbleiben (kein"
              + " Austritt). Der Creator kann die Aufgabe nur per HANDOVER mit shouldStay=true"
              + " abgeben (ansonsten 409).")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Aufgabe erfolgreich verlassen/uebergeben."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Eingabe: UNSUBSCRIBED mit handedOverToIds/shouldStay; shouldStay=true ohne"
                + " Ziele; Selbstziel; unbekannte/fremde Ziel-UUID; mehr als 100 Ziele;"
                + " null-Element.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.BAD_REQUEST_EXIT))),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = ParticipantApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "403",
        description =
            "Standard-Selfservice (Slice 4): der Akteur ist bestaetigtes Mitglied, aber ein"
                + " HANDOVER-Ziel ist noch kein WRITE-Member (neu/ausgetreten/inaktiv/TEAM/fremder"
                + " Mandant) -> die Ziel-Einfuehrung erfordert TASK_WRITE.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.FORBIDDEN_EXIT))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder keine aktive Mitgliedschaft (neutral"
                + " maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.NOT_FOUND_EXIT))),
    @ApiResponse(
        responseCode = "409",
        description =
            "Der Creator kann die Aufgabe nur per HANDOVER mit shouldStay=true abgeben;"
                + " ensureAssignee-Auswahlvertrag fail-closed (Ziel-Zeile TEAM oder fremdes"
                + " Basis-TENANT_ID); oder nebenlaeufiger Versionskonflikt (Retry gegen frischen"
                + " Stand).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.CONFLICT_EXIT)))
  })
  @PostMapping("/{id}/exit")
  @HateoasLinkTarget(TaskHateoas.Targets.EXIT)
  public ResponseEntity<Void> exit(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description = "Art des Austritts, Uebergabeziele und optionaler Verbleib.",
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = ExitInput.class),
                      examples = {
                        @ExampleObject(
                            name = "uebergeben",
                            summary = "Aufgabe an einen Benutzer uebergeben und verlassen",
                            description =
                                "Das Ziel wird Assignee (entstummt, falls es vorher kein aktiver"
                                    + " Assignee war); der Akteur verlaesst die Aufgabe.",
                            value =
                                "{\"exitKind\":\"HANDOVER\",\"handedOverToIds\":[\""
                                    + ParticipantApiExamples.USER_REF_B
                                    + "\"],\"shouldStay\":false}"),
                        @ExampleObject(
                            name = "abmelden",
                            summary = "Aufgabe abmelden",
                            description = "Der Akteur meldet sich von der Aufgabe ab.",
                            value = "{\"exitKind\":\"UNSUBSCRIBED\"}")
                      }))
          @RequestBody
          final ExitInput input) {
    assigneeService.exit(id, input);
    return ResponseEntity.noContent().build();
  }

  /**
   * Fuegt Benutzer additiv als Teilnehmer der Aufgabe hinzu (idempotent).
   *
   * <p>Verleiht ausschliesslich die Teilnehmer-Rolle (PARTICIPANT_FLAG), NIE die Assignee-Rolle.
   * Bereits aktive Teilnehmer werden uebersprungen; ein leeres Array ist ein No-Op. Jede
   * tatsaechlich neu hinzugefuegte oder aus dem Austritt reaktivierte Person erhaelt eine
   * PARTICIPANT-Benachrichtigung (Intake #147); der Akteur und schon aktive Mitglieder nicht. Der
   * Typ ist von der Stummschaltung ausgenommen, das Hinzufuegen hebt einen Mute nicht auf.
   *
   * @param id externe UUID der Aufgabe
   * @param input hinzuzufuegende Teilnehmer-UUIDs
   * @return 204 No Content
   */
  @Operation(
      summary = "Teilnehmer hinzufuegen",
      description =
          "Fuegt die angegebenen Benutzer additiv als Teilnehmer der Aufgabe hinzu (idempotent)."
              + " Verleiht ausschliesslich die Teilnehmer-Rolle, nicht die Assignee-Rolle."
              + " Bereits aktive Mitglieder der Aufgabe werden uebersprungen; ein leeres Array ist"
              + " ein No-Op. Zuvor ausgetretene Personen oder solche mit deaktivierter Teilnehmerzeile"
              + " (der Benutzer selbst ist aktiv) werden als reine Teilnehmer"
              + " reaktiviert."
              + " Jede Person, die dabei tatsaechlich neu hinzukommt oder reaktiviert wird, erhaelt"
              + " eine Benachrichtigung vom Typ PARTICIPANT (gespeichert, abrufbar ueber"
              + " GET /api/v1/notifications; zusaetzlich nach dem Commit nach bestem Bemuehen"
              + " per WebSocket an offene Sitzungen mit Recht NOTIFICATION_READ); der aufrufende Benutzer selbst und bereits aktive Mitglieder"
              + " erhalten keine, ein wiederholter Aufruf loest keine weitere aus. PARTICIPANT ist"
              + " direkt persoenlich und von der Stummschaltung ausgenommen: die Benachrichtigung"
              + " kommt auch bei einer stummgeschalteten Aufgabe an, das Hinzufuegen hebt die"
              + " Stummschaltung aber nicht auf (anders als eine neue Zuweisung). Alles oder"
              + " nichts: ist eine UUID kein aktiver Benutzer des Mandanten, wird niemand"
              + " hinzugefuegt und niemand benachrichtigt (400).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "204",
        description =
            "Teilnehmer erfolgreich hinzugefuegt (oder No-Op); neu hinzugekommene Personen sind"
                + " benachrichtigt."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Eingabe: UUID ist kein aktiver Benutzer des Mandanten (unbekannt, fremder"
                + " Mandant, inaktiv), Feld participantIds fehlt oder ist null, null-Element,"
                + " mehr als 100 Eintraege, kein gueltiges JSON oder keine UUID im Pfad.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "unbekannter-benutzer",
                      summary = "UUID ist kein aktiver Benutzer des Mandanten",
                      value = ParticipantApiExamples.BAD_REQUEST_PARTICIPANTS),
                  @ExampleObject(
                      name = "participantIds-fehlt",
                      summary = "Body ohne das Pflichtfeld participantIds",
                      value = ParticipantApiExamples.BAD_REQUEST_PARTICIPANTS_BODY)
                })),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = ParticipantApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "403",
        description = "Fehlende TASK_WRITE-Berechtigung.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.FORBIDDEN_PARTICIPANTS))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder keine aktive Mitgliedschaft des"
                + " Aufrufers (neutral maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.NOT_FOUND_PARTICIPANTS))),
    @ApiResponse(
        responseCode = "409",
        description =
            "Zwei Ursachen, in beiden Faellen wurde nichts geaendert. (1) Nebenlaeufiger Konflikt:"
                + " paralleler Schreibzugriff auf dieselbe Teilnehmerzeile oder den persoenlichen"
                + " Zustand der Person (Versionskonflikt) bzw. gleichzeitiges erstes Hinzufuegen;"
                + " die Anfrage gegen den frischen Stand wiederholen (der Aufruf ist idempotent)."
                + " (2) Die vorhandene Teilnehmerzeile einer Person ist keine PERSON-Zeile des"
                + " Mandanten (TEAM-Zeile oder fremder Mandant, Datenfehler); sie wird nicht"
                + " ueberschrieben und die Anfrage scheitert, bis der Datenbestand bereinigt ist."
                + " Ausgetretene Personen oder solche mit deaktivierter Teilnehmerzeile (bei aktivem"
                + " Benutzer) sind kein Konfliktgrund: sie werden"
                + " reaktiviert.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = ParticipantApiExamples.CONFLICT_PARTICIPANTS)))
  })
  @PostMapping("/{id}/participants")
  @HateoasLinkTarget(TaskHateoas.Targets.ADD_PARTICIPANTS)
  @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
  public ResponseEntity<Void> addParticipants(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description =
                  "UUIDs der hinzuzufuegenden Benutzer (hoechstens 100, additiv, idempotent).",
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = ParticipantAddInput.class),
                      examples = {
                        @ExampleObject(
                            name = "hinzufuegen",
                            summary = "Zwei Benutzer als Teilnehmer hinzufuegen",
                            description =
                                "Neu hinzukommende oder ausgetretene Benutzer werden Teilnehmer"
                                    + " und benachrichtigt; aktive Mitglieder werden"
                                    + " uebersprungen.",
                            value =
                                "{\"participantIds\":[\""
                                    + ParticipantApiExamples.USER_REF_A
                                    + "\",\""
                                    + ParticipantApiExamples.USER_REF_B
                                    + "\"]}"),
                        @ExampleObject(
                            name = "no-op",
                            summary = "Leeres Array",
                            description =
                                "Ein leeres Array aendert nichts und benachrichtigt niemanden.",
                            value = "{\"participantIds\":[]}")
                      }))
          @RequestBody
          final ParticipantAddInput input) {
    assigneeService.addParticipants(id, input);
    return ResponseEntity.noContent().build();
  }
}
