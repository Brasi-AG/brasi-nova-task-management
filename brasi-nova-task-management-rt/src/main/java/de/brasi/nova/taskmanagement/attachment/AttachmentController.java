package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.attachment.model.AttachmentUploadSchema;
import de.brasi.nova.taskmanagement.inbox.model.ArchiveSearchPageSchema;
import de.brasi.nova.taskmanagement.task.TaskHateoas;
import de.brasi.nova.taskmanagement.web.LockConflictApiExamples;
import de.brasi.nova.taskmanagement.web.TranslatesLockConflicts;
import io.keel.framework.api.hateoas.annotation.HateoasLinkTarget;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Handgeschriebener Facade-Controller fuer Anhaenge an Aufgaben (Intake #152, arch-v2 §7).
 *
 * <p>Standard-Selfservice (authenticated-only via KSF-Filter-Chain); die Autorisierung erfolgt
 * objekt-level ueber den {@code TaskMembershipGuard} in den delegierten Services: Hochladen
 * verlangt WRITE-Mitgliedschaft (Non-/Ex-Mitglied: neutrale 404). Akteur und Mandant kommen
 * ausschliesslich aus dem Security-Context. Der Controller enthaelt keine Fachlogik; er delegiert
 * an {@link AttachmentUploadService} (Pruefen und Speichern) und {@link AttachmentCommandService}
 * (weitere Kommandos).
 */
@RestController
@TranslatesLockConflicts(message = TranslatesLockConflicts.TASK)
@RequestMapping("/api/v1/tasks")
public class AttachmentController {

  private final AttachmentUploadService uploadService;
  private final AttachmentCommandService commandService;

  /**
   * Konstruktor-Injektion der Anhang-Services.
   *
   * @param uploadService prueft und speichert hochgeladene Dateien
   * @param commandService transaktionale Kommandos der Anhaenge
   */
  public AttachmentController(
      final AttachmentUploadService uploadService, final AttachmentCommandService commandService) {
    this.uploadService = uploadService;
    this.commandService = commandService;
  }

  /**
   * Haengt eine oder mehrere Dateien an eine Aufgabe (alles oder nichts).
   *
   * @param id externe UUID der Aufgabe
   * @param files hochgeladene Dateien (Teil {@code files}, mindestens einer)
   * @return 201 Created mit den neuen Anhaengen in Upload-Reihenfolge
   */
  @Operation(
      // Nicht „Dateien an eine Aufgabe anhängen“: die Sprachprüfung des OpenAPI-Gates zaehlt „an“
      // als englisch und „eine“ als deutsch, der Gleichstand gilt als nicht deutsch.
      summary = "Dateien einer Aufgabe anhängen",
      description =
          "Haengt eine oder mehrere Dateien als multipart/form-data (je Datei ein Teil files) an"
              + " die Aufgabe. Erlaubt sind PDF, Excel (xls, xlsx), Word (doc, docx), PowerPoint"
              + " (ppt, pptx), Text (txt) und Bilder (png, jpg, jpeg); geprueft werden Endung und"
              + " Inhalt. Die Grenze je Datei haengt vom Tarif des Mandanten ab (Vorgaben:"
              + " STANDARD 5 MB, EXPERT bis 10 MB, ENTERPRISE bis 15 MB; harte Obergrenze fuer"
              + " alle 15 MB). Leere Dateien werden abgelehnt. Eine Aufgabe hat hoechstens 20"
              + " aktive Anhaenge (Kommentar-Anhaenge zaehlen nicht mit). Alles oder nichts: ist"
              + " eine Datei ungueltig, wird keine gespeichert. Fuer PDF und Bilder entsteht beim"
              + " Hochladen ein Vorschaubild; scheitert es, wird die Datei trotzdem gespeichert"
              + " und das Vorschaubild beim ersten Abruf erzeugt. Hochladen ist Aktivitaet an der"
              + " Aufgabe: die letzte Aktivitaet wird fortgeschrieben, je Datei entsteht ein"
              + " Historieneintrag, und die Inbox aller Leser wird aktualisiert; eine eigene"
              + " Benachrichtigung gibt es nicht. Hochladen duerfen nur aktive Mitglieder der"
              + " Aufgabe; fuer alle anderen ist die Aufgabe nicht sichtbar (404), auch wenn die"
              + " Dateien ungueltig sind.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description =
                  "Multipart-Formular mit mindestens einer Datei im Teil files (mehrere Teile"
                      + " mit demselben Namen fuer mehrere Dateien).",
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                      schema = @Schema(implementation = AttachmentUploadSchema.class),
                      examples =
                          @ExampleObject(
                              name = "eine-datei",
                              summary = "Eine Datei im Teil files",
                              description =
                                  "Der Binaerinhalt steht im Formularteil; das Beispiel zeigt nur"
                                      + " die Struktur.",
                              value = AttachmentApiExamples.UPLOAD_REQUEST))))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description =
            "Alle Dateien gespeichert; die neuen Anhaenge in Upload-Reihenfolge mit Abruf-Links.",
        content =
            @Content(
                mediaType = "application/json",
                array = @ArraySchema(schema = @Schema(implementation = AttachmentModel.class)),
                examples =
                    @ExampleObject(
                        name = "pdf-und-text",
                        summary = "Ein PDF mit Vorschaubild und eine Textdatei ohne",
                        value = AttachmentApiExamples.UPLOAD_CREATED))),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Anfrage, nichts wurde gespeichert: Dateityp nicht erlaubt, leere Datei,"
                + " Datei groesser als die Grenze des Tarifs oder die harte Obergrenze, Inhalt"
                + " passt nicht zur Endung, die Aufgabe haette mehr als 20 aktive Anhaenge, Teil"
                + " files fehlt, kein lesbares multipart/form-data oder keine UUID im Pfad.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "typ-nicht-erlaubt",
                      summary = "Endung nicht in der Erlaubtliste",
                      value = AttachmentApiExamples.BAD_REQUEST_TYPE),
                  @ExampleObject(
                      name = "leere-datei",
                      summary = "Datei ohne Inhalt",
                      value = AttachmentApiExamples.BAD_REQUEST_EMPTY),
                  @ExampleObject(
                      name = "tarifgrenze",
                      summary = "Datei groesser als die Grenze des Tarifs",
                      value = AttachmentApiExamples.BAD_REQUEST_TARIFF_LIMIT),
                  @ExampleObject(
                      name = "obergrenze",
                      summary = "Datei groesser als die harte Obergrenze",
                      value = AttachmentApiExamples.BAD_REQUEST_HARD_LIMIT),
                  @ExampleObject(
                      name = "anzahl",
                      summary = "Zu viele aktive Anhaenge an der Aufgabe",
                      value = AttachmentApiExamples.BAD_REQUEST_COUNT),
                  @ExampleObject(
                      name = "inhalt",
                      summary = "Inhalt passt nicht zur Endung",
                      value = AttachmentApiExamples.BAD_REQUEST_CONTENT),
                  @ExampleObject(
                      name = "teil-fehlt",
                      summary = "Kein Teil files im Formular",
                      value = AttachmentApiExamples.BAD_REQUEST_MISSING_PART),
                  @ExampleObject(
                      name = "multipart-unlesbar",
                      summary = "Kein lesbares multipart/form-data",
                      value = AttachmentApiExamples.BAD_REQUEST_MULTIPART),
                  @ExampleObject(
                      name = "keine-uuid",
                      summary = "Keine gueltige UUID im Pfad",
                      value = AttachmentApiExamples.BAD_REQUEST_PATH)
                })),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = AttachmentApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant, deaktiviert oder kein aktives Mitglied der"
                + " Aufgabe (neutral maskiert, auch bei ungueltigen Dateien).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = AttachmentApiExamples.NOT_FOUND))),
    @ApiResponse(
        responseCode = "409",
        description =
            "Nebenlaeufiger Konflikt: die Aufgabe wurde zwischenzeitlich geaendert"
                + " (Versionskonflikt); nichts wurde gespeichert, auch keine Datei. Die Anfrage"
                + " gegen den frischen Stand wiederholen."
                + LockConflictApiExamples.DESCRIPTION_SUFFIX,
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "versionskonflikt",
                      summary = "Aufgabe zwischenzeitlich geaendert",
                      value = AttachmentApiExamples.CONFLICT_VERSION),
                  @ExampleObject(
                      name = "sperrkonflikt",
                      summary = "Sperrkonflikt der Datenbank",
                      value = AttachmentApiExamples.CONFLICT_LOCK)
                }))
  })
  @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @HateoasLinkTarget(TaskHateoas.Targets.UPLOAD_ATTACHMENTS)
  public ResponseEntity<List<AttachmentModel>> upload(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @Parameter(hidden = true) @RequestPart("files") final List<MultipartFile> files) {
    final List<AttachmentModel> created = uploadService.upload(id, files);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }
}
