package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.division.model.DivisionCandidatePage;
import de.brasi.nova.taskmanagement.inbox.model.ArchiveSearchPageSchema;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.web.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller der Vorschlags-API fuer Abteilungen (Intake #190).
 *
 * <p>Route: {@code GET /api/v1/tasks/divisions} - fester Pfad wie {@code
 * /api/v1/tasks/participants}; Spring ordnet den konkreten Pfad vor dem variablen {@code {id}} von
 * {@code TaskFacadeController} ein. Nur lesend, daher ohne {@code @TranslatesLockConflicts}.
 *
 * <p>Mandant aus dem JWT (KSF-Filter); die Queries binden {@code :tenantId}.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class DivisionCandidateController {

  private final DivisionCandidateService candidateService;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public DivisionCandidateController(final DivisionCandidateService candidateService) {
    this.candidateService = candidateService;
  }

  /**
   * Liefert Vorschlaege fuer die Erwaehnung von Abteilungen im Kommentar.
   *
   * @param q Suchbegriff ueber den Namen (optional, nach dem Trimmen hoechstens 200 Zeichen)
   * @param ids UUIDs fuer die Vorschau (optional, hoechstens 50; gewinnt vor {@code q}); fehlt der
   *     Parameter, ist der Wert {@code null}, ein leerer Parameter ({@code ?ids=}) ist ein 400
   * @param limit hoechste Zahl der Vorschlaege der Suche (1 bis 100, Vorgabe 20)
   * @return 200 OK mit {@link DivisionCandidatePage}
   */
  @Operation(
      summary = "Abteilungen für Erwähnungen vorschlagen",
      description =
          """
          Liefert die AKTIVEN Abteilungen des EIGENEN MANDANTEN (Mandant aus dem JWT) als \
          Vorschlaege fuer divisionMentions beim Kommentar (POST /api/v1/tasks/{id}/comments). \
          Grundlage ist der Stand, den task-management von application uebernommen hat; \
          unbekannte, nicht aktive und mandantenfremde Abteilungen erscheinen nie.

          Je Abteilung: divisionId (der Wert fuer divisionMentions), name (null, solange \
          application keinen Namen geliefert hat) und memberCount (Mitglieder mit Unterabteilungen, \
          die einen aktiven Benutzer im eigenen Mandanten haben: so viele Personen erreicht eine \
          Erwaehnung hoechstens). memberCount zaehlt nur aktive Benutzer; die Personengrenze der \
          Erwaehnung am Kommentar zaehlt dagegen alle Mitglieder, auch solche ohne aktiven \
          Benutzer. Ein Client darf memberCount daher nicht gegen diese Grenze vorpruefen.

          Suche: q filtert als Teiltreffer ueber den Namen, unabhaengig von Gross- und \
          Kleinschreibung; Leerzeichen aussen werden entfernt (danach hoechstens 200 Zeichen, \
          sonst 400), %, _ und [ werden woertlich gesucht. Abteilungen ohne Namen fallen bei \
          gesetztem q heraus. Ohne q (oder leer) kommen die aktiven Abteilungen bis limit (1 bis \
          100, Vorgabe 20); es gibt keine weiteren Seiten, weitere Abteilungen sind nur ueber q \
          zu finden.

          Vorschau: ids liefert die Abteilungen zu bekannten UUIDs (hoechstens 50, Parameter \
          wiederholt oder kommagetrennt, leere Eintraege sind ein Fehler, auch ein Parameter ohne \
          Wert wie ids=); unbekannte, nicht aktive und mandantenfremde UUIDs fehlen ohne Fehler. \
          Sind ids gesetzt, wird q ignoriert (aber weiterhin auf hoechstens 200 Zeichen \
          geprueft) und limit nicht angewendet (aber weiterhin auf 1 bis 100 geprueft).

          Sortierung: nach Name (Abteilungen ohne Namen zuletzt). Bei gleichem Namen und unter den \
          Abteilungen ohne Namen ist die Reihenfolge stabil (technisch nach divisionId in \
          GUID-Sortierung, fachlich bedeutungslos), aber NICHT alphabetisch nach divisionId.""")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description =
            "Vorschlaege (aktive Abteilungen des eigenen Mandanten); leer, wenn nichts passt.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = DivisionCandidatePage.class),
                examples = {
                  @ExampleObject(
                      name = "alle",
                      summary = "Ohne Parameter: aktive Abteilungen bis limit, ohne Namen zuletzt",
                      value = DivisionCandidateApiExamples.ALL),
                  @ExampleObject(
                      name = "suche",
                      summary = "Mit q=süd: Teiltreffer ueber den Namen",
                      value = DivisionCandidateApiExamples.SEARCH),
                  @ExampleObject(
                      name = "vorschau",
                      summary = "Mit ids: nur bekannte aktive Abteilungen, unbekannte fehlen",
                      value = DivisionCandidateApiExamples.PREVIEW)
                })),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Parameter: limit ausserhalb von 1 bis 100, q laenger als 200 Zeichen (nach"
                + " dem Entfernen der Leerzeichen aussen), mehr als 50 ids, ein leerer Eintrag in"
                + " ids (etwa ids=, oder ein Parameter ohne Wert ids=), ein Eintrag in ids ist"
                + " keine UUID oder limit ist keine Zahl.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "limit-ausserhalb",
                      summary = "limit ausserhalb von 1 bis 100",
                      value = DivisionCandidateApiExamples.BAD_REQUEST_LIMIT),
                  @ExampleObject(
                      name = "zu-viele-ids",
                      summary = "Mehr als 50 ids",
                      value = DivisionCandidateApiExamples.BAD_REQUEST_IDS)
                })),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = DivisionCandidateApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "403",
        description = "Fehlende Berechtigung task-management:TASK_READ.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = DivisionCandidateApiExamples.FORBIDDEN)))
  })
  @GetMapping("/divisions")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  public ResponseEntity<DivisionCandidatePage> searchDivisionCandidates(
      @Parameter(
              description =
                  "Suchbegriff: Teiltreffer ueber den Namen der Abteilung, unabhaengig von Gross-"
                      + " und Kleinschreibung; %, _ und [ werden woertlich gesucht. Leer oder"
                      + " fehlend = kein Filter. Hoechstens 200 Zeichen nach dem Entfernen der"
                      + " Leerzeichen aussen (sonst 400, auch bei gesetztem ids). Ohne Wirkung,"
                      + " wenn ids gesetzt ist.",
              schema = @Schema(type = "string", maxLength = 200),
              example = "vertrieb")
          @RequestParam(required = false)
          final String q,
      @Parameter(
              description =
                  "UUIDs bekannter Abteilungen fuer die Vorschau (hoechstens 50; Parameter"
                      + " wiederholen oder kommagetrennt; ein leerer Eintrag ist ein 400, auch ein"
                      + " Parameter ohne Wert wie ids=)."
                      + " Unbekannte, nicht aktive und mandantenfremde UUIDs fehlen in der Antwort"
                      + " ohne Fehler. Gesetzt gewinnt ids vor q, limit wird dann nicht"
                      + " angewendet.",
              array =
                  @ArraySchema(
                      maxItems = 50,
                      schema =
                          @Schema(
                              type = "string",
                              format = "uuid",
                              description = "UUID einer Abteilung (divisionId).",
                              example = "5d1c0a3e-2b4f-4c6d-8e9f-0a1b2c3d4e5f")))
          @RequestParam(required = false)
          final List<UUID> ids,
      @Parameter(
              description =
                  "Hoechste Zahl der Vorschlaege der Suche, 1 bis 100 (sonst 400). Vorgabe 20.",
              schema =
                  @Schema(
                      type = "integer",
                      format = "int32",
                      minimum = "1",
                      maximum = "100",
                      defaultValue = "20"),
              example = "20")
          @RequestParam(required = false, defaultValue = "20")
          final int limit) {
    return ResponseEntity.ok(candidateService.find(q, ids, limit));
  }
}
