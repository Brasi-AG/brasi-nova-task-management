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
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Handgeschriebener Facade-Controller fuer Anhaenge an Aufgaben (Intake #152, arch-v2 §7).
 *
 * <p>Standard-Selfservice (authenticated-only via KSF-Filter-Chain); die Autorisierung erfolgt
 * objekt-level ueber den {@code TaskMembershipGuard} in den delegierten Services: Hochladen
 * verlangt WRITE-Mitgliedschaft, Lesen (Liste, Inhalt, Download, Vorschaubild) READ-Mitgliedschaft
 * (auch Ausgetretene); alle anderen erhalten die neutrale 404. Akteur und Mandant kommen
 * ausschliesslich aus dem Security-Context. Der Controller enthaelt keine Fachlogik; er delegiert
 * an {@link AttachmentUploadService} (Pruefen und Speichern), {@link AttachmentCommandService}
 * (weitere Kommandos) und {@link AttachmentQueryService} (Lesen und Ausliefern).
 *
 * <p>Auslieferung: Der Service oeffnet den Strom (Fehler entstehen synchron als 404), der Body
 * kopiert ihn nur ({@link StreamingResponseBody}). Jede Antwort traegt einen schwachen ETag und
 * {@code Cache-Control: private}; ein passender {@code If-None-Match} ergibt 304 ohne Inhalt.
 */
@RestController
@TranslatesLockConflicts(message = TranslatesLockConflicts.TASK)
@RequestMapping("/api/v1/tasks")
public class AttachmentController {

  /** Header gegen das Erraten der Inhaltsart durch den Browser. */
  private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

  private static final String NOSNIFF = "nosniff";

  // ---- OpenAPI-Texte der Auslieferung --------------------------------------------------------

  private static final String DELIVERY_DESCRIPTION =
      " Jede Antwort traegt einen schwachen ETag aus der Pruefsumme des Inhalts und"
          + " Cache-Control private; sendet der Client den ETag in If-None-Match, antwortet der"
          + " Endpunkt mit 304 ohne Inhalt. Abruf nur mit Bearer-JWT: lesen duerfen alle"
          + " Mitglieder der Aufgabe, auch ausgetretene. Auch Anhaenge der Kommentare werden"
          + " ueber die Aufgabe ausgeliefert. Fuer alle anderen, fuer unbekannte, deaktivierte"
          + " oder fremde Anhaenge und fuer eine fehlende Datei antwortet der Endpunkt mit 404.";

  private static final String CONTENT_OK_DESCRIPTION =
      "Der Inhalt des Anhangs. Der echte Content-Type ist die gespeicherte Art des Anhangs (hier"
          + " stellvertretend application/octet-stream), Content-Length die Groesse in Bytes.";

  private static final String NOT_MODIFIED_DESCRIPTION =
      "Der ETag aus If-None-Match ist aktuell: keine Daten, nur ETag und Cache-Control.";

  private static final String ETAG_DESCRIPTION =
      "Schwacher ETag des ausgelieferten Inhalts, fuer If-None-Match beim naechsten Abruf.";

  private static final String CONTENT_CACHE_DESCRIPTION =
      "private mit max-age aus der Konfiguration (Vorgabe ein Tag).";

  private static final String THUMBNAIL_CACHE_DESCRIPTION =
      "private mit max-age aus der Konfiguration (Vorgabe zehn Tage).";

  private static final String IF_NONE_MATCH_DESCRIPTION =
      "ETag(s) aus dem Cache des Clients, durch Komma getrennt, oder *; passt einer, antwortet"
          + " der Endpunkt mit 304 ohne Inhalt.";

  private static final String BAD_REQUEST_DESCRIPTION =
      "Keine gueltige UUID als Aufgabe oder Anhang im Pfad.";

  private static final String UNAUTHORIZED_DESCRIPTION = "Kein oder ungueltiges JWT.";

  private static final String NOT_FOUND_DESCRIPTION =
      "Aufgabe nicht sichtbar oder der Anfrager ist kein Mitglied, Anhang unbekannt, deaktiviert"
          + " oder an einer anderen Aufgabe, oder die Datei fehlt im Speicher (neutral"
          + " maskiert).";

  private final AttachmentUploadService uploadService;
  private final AttachmentCommandService commandService;
  private final AttachmentQueryService queryService;

  /**
   * Konstruktor-Injektion der Anhang-Services.
   *
   * @param uploadService prueft und speichert hochgeladene Dateien
   * @param commandService transaktionale Kommandos der Anhaenge
   * @param queryService liest und liefert Anhaenge aus
   */
  public AttachmentController(
      final AttachmentUploadService uploadService,
      final AttachmentCommandService commandService,
      final AttachmentQueryService queryService) {
    this.uploadService = uploadService;
    this.commandService = commandService;
    this.queryService = queryService;
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

  /**
   * Liefert die Anhaenge einer Aufgabe (ohne Kommentar-Anhaenge) in Hochlade-Reihenfolge.
   *
   * @param id externe UUID der Aufgabe
   * @return 200 OK mit den Anhaengen und ihren Abruf-Links
   */
  @Operation(
      summary = "Anhänge einer Aufgabe laden",
      description =
          "Liefert die aktiven Anhaenge der Aufgabe in der Reihenfolge des Hochladens (aelteste"
              + " zuerst), ohne die Anhaenge der Kommentare. Der Inhalt steht nicht im JSON; jeder"
              + " Anhang traegt Links auf Inhalt, Download und (bei PDF und Bildern) Vorschaubild."
              + " removable zeigt, ob der Anfrager den Anhang entfernen darf (aktives Mitglied und"
              + " hochladende Person oder Ersteller der Aufgabe). Lesen duerfen alle Mitglieder der"
              + " Aufgabe, auch ausgetretene; fuer alle anderen ist die Aufgabe nicht sichtbar"
              + " (404).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Die Anhaenge der Aufgabe, aelteste zuerst; leere Liste ohne Anhaenge.",
        content =
            @Content(
                mediaType = "application/json",
                array = @ArraySchema(schema = @Schema(implementation = AttachmentModel.class)),
                examples =
                    @ExampleObject(
                        name = "pdf-und-text",
                        summary = "Eigenes PDF und Textdatei einer anderen Person",
                        value = AttachmentApiExamples.LIST))),
    @ApiResponse(
        responseCode = "400",
        description = "Keine gueltige UUID im Pfad.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = AttachmentApiExamples.BAD_REQUEST_PATH))),
    @ApiResponse(
        responseCode = "401",
        description = UNAUTHORIZED_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = AttachmentApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant, deaktiviert oder der Anfrager ist kein"
                + " Mitglied der Aufgabe (neutral maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = AttachmentApiExamples.NOT_FOUND)))
  })
  @GetMapping("/{id}/attachments")
  @HateoasLinkTarget(TaskHateoas.Targets.LIST_ATTACHMENTS)
  public ResponseEntity<List<AttachmentModel>> list(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id) {
    return ResponseEntity.ok(queryService.listTaskAttachments(id));
  }

  /**
   * Liefert den Inhalt eines Anhangs zur Anzeige ({@code Content-Disposition: inline}).
   *
   * @param id externe UUID der Aufgabe
   * @param attachmentId externe UUID des Anhangs
   * @param ifNoneMatch ETag(s) aus dem Cache des Clients oder {@code null}
   * @return 200 mit dem Inhalt oder 304 ohne Inhalt
   */
  @Operation(
      summary = "Inhalt eines Anhangs anzeigen",
      description =
          "Liefert den Inhalt eines Anhangs der Aufgabe zur Anzeige im Browser"
              + " (Content-Disposition inline mit dem Originalnamen nach RFC 5987). Der echte"
              + " Content-Type ist die beim Hochladen gepruefte Art des Anhangs (etwa"
              + " application/pdf oder image/png), dazu X-Content-Type-Options nosniff."
              + DELIVERY_DESCRIPTION)
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = CONTENT_OK_DESCRIPTION,
        headers = {
          @Header(name = "ETag", description = ETAG_DESCRIPTION, schema = @Schema(type = "string")),
          @Header(
              name = "Cache-Control",
              description = CONTENT_CACHE_DESCRIPTION,
              schema = @Schema(type = "string"))
        },
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = "string", format = "binary"),
                examples =
                    @ExampleObject(
                        name = "pdf",
                        summary = "Anfang eines PDF (Base64)",
                        value = AttachmentApiExamples.CONTENT_BINARY))),
    @ApiResponse(
        responseCode = "304",
        description = NOT_MODIFIED_DESCRIPTION,
        headers = {
          @Header(name = "ETag", description = ETAG_DESCRIPTION, schema = @Schema(type = "string")),
          @Header(
              name = "Cache-Control",
              description = CONTENT_CACHE_DESCRIPTION,
              schema = @Schema(type = "string"))
        }),
    @ApiResponse(
        responseCode = "400",
        description = BAD_REQUEST_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = AttachmentApiExamples.BAD_REQUEST_CONTENT_PATH))),
    @ApiResponse(
        responseCode = "401",
        description = UNAUTHORIZED_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = AttachmentApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description = NOT_FOUND_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "aufgabe",
                      summary = "Aufgabe nicht sichtbar oder kein Mitglied",
                      value = AttachmentApiExamples.NOT_FOUND_CONTENT_TASK),
                  @ExampleObject(
                      name = "anhang",
                      summary = "Anhang unbekannt oder Datei fehlt",
                      value = AttachmentApiExamples.NOT_FOUND_CONTENT_ATTACHMENT)
                }))
  })
  @GetMapping("/{id}/attachments/{attachmentId}/content")
  public ResponseEntity<StreamingResponseBody> content(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @Parameter(description = "Externe UUID des Anhangs.", required = true) @PathVariable
          final UUID attachmentId,
      @Parameter(
              in = ParameterIn.HEADER,
              name = HttpHeaders.IF_NONE_MATCH,
              description = IF_NONE_MATCH_DESCRIPTION)
          @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false)
          final String ifNoneMatch) {
    return deliver(
        queryService.serveContent(id, attachmentId, ifNoneMatch), ContentDisposition.inline());
  }

  /**
   * Liefert den Inhalt eines Anhangs als Download ({@code Content-Disposition: attachment}).
   *
   * @param id externe UUID der Aufgabe
   * @param attachmentId externe UUID des Anhangs
   * @param ifNoneMatch ETag(s) aus dem Cache des Clients oder {@code null}
   * @return 200 mit dem Inhalt oder 304 ohne Inhalt
   */
  @Operation(
      summary = "Anhang herunterladen",
      description =
          "Liefert den Inhalt eines Anhangs der Aufgabe als Download (Content-Disposition"
              + " attachment mit dem Originalnamen nach RFC 5987). Der echte Content-Type ist die"
              + " beim Hochladen gepruefte Art des Anhangs (etwa application/pdf oder"
              + " text/plain), dazu X-Content-Type-Options nosniff."
              + DELIVERY_DESCRIPTION)
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = CONTENT_OK_DESCRIPTION,
        headers = {
          @Header(name = "ETag", description = ETAG_DESCRIPTION, schema = @Schema(type = "string")),
          @Header(
              name = "Cache-Control",
              description = CONTENT_CACHE_DESCRIPTION,
              schema = @Schema(type = "string"))
        },
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = "string", format = "binary"),
                examples =
                    @ExampleObject(
                        name = "pdf",
                        summary = "Anfang eines PDF (Base64)",
                        value = AttachmentApiExamples.CONTENT_BINARY))),
    @ApiResponse(
        responseCode = "304",
        description = NOT_MODIFIED_DESCRIPTION,
        headers = {
          @Header(name = "ETag", description = ETAG_DESCRIPTION, schema = @Schema(type = "string")),
          @Header(
              name = "Cache-Control",
              description = CONTENT_CACHE_DESCRIPTION,
              schema = @Schema(type = "string"))
        }),
    @ApiResponse(
        responseCode = "400",
        description = BAD_REQUEST_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples =
                    @ExampleObject(value = AttachmentApiExamples.BAD_REQUEST_DOWNLOAD_PATH))),
    @ApiResponse(
        responseCode = "401",
        description = UNAUTHORIZED_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = AttachmentApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description = NOT_FOUND_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "aufgabe",
                      summary = "Aufgabe nicht sichtbar oder kein Mitglied",
                      value = AttachmentApiExamples.NOT_FOUND_DOWNLOAD_TASK),
                  @ExampleObject(
                      name = "anhang",
                      summary = "Anhang unbekannt oder Datei fehlt",
                      value = AttachmentApiExamples.NOT_FOUND_DOWNLOAD_ATTACHMENT)
                }))
  })
  @GetMapping("/{id}/attachments/{attachmentId}/download")
  public ResponseEntity<StreamingResponseBody> download(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @Parameter(description = "Externe UUID des Anhangs.", required = true) @PathVariable
          final UUID attachmentId,
      @Parameter(
              in = ParameterIn.HEADER,
              name = HttpHeaders.IF_NONE_MATCH,
              description = IF_NONE_MATCH_DESCRIPTION)
          @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false)
          final String ifNoneMatch) {
    return deliver(
        queryService.serveContent(id, attachmentId, ifNoneMatch), ContentDisposition.attachment());
  }

  /**
   * Liefert das Vorschaubild (JPEG) eines Anhangs; fehlt die Datei, wird es neu erzeugt.
   *
   * @param id externe UUID der Aufgabe
   * @param attachmentId externe UUID des Anhangs
   * @param ifNoneMatch ETag(s) aus dem Cache des Clients oder {@code null}
   * @return 200 mit dem Vorschaubild oder 304 ohne Inhalt
   */
  @Operation(
      summary = "Vorschaubild eines Anhangs laden",
      description =
          "Liefert das Vorschaubild eines Anhangs der Aufgabe als JPEG (erste Seite eines PDF"
              + " bzw. verkleinertes Bild), Content-Disposition inline und X-Content-Type-Options"
              + " nosniff. Nur PDF, PNG und JPEG haben ein Vorschaubild; fuer Office-Dokumente und"
              + " Text antwortet der Endpunkt mit 404. Fehlt die Datei des Vorschaubilds, wird sie"
              + " beim Abruf aus dem Inhalt erzeugt und abgelegt (ohne Aenderung der Daten des"
              + " Anhangs); scheitert die Erzeugung, ebenfalls 404. Der ETag enthaelt die Breite"
              + " des Vorschaubilds."
              + DELIVERY_DESCRIPTION)
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Das Vorschaubild als JPEG.",
        headers = {
          @Header(name = "ETag", description = ETAG_DESCRIPTION, schema = @Schema(type = "string")),
          @Header(
              name = "Cache-Control",
              description = THUMBNAIL_CACHE_DESCRIPTION,
              schema = @Schema(type = "string"))
        },
        content =
            @Content(
                mediaType = "image/jpeg",
                schema = @Schema(type = "string", format = "binary"),
                examples =
                    @ExampleObject(
                        name = "jpeg",
                        summary = "Anfang eines JPEG (Base64)",
                        value = AttachmentApiExamples.THUMBNAIL_BINARY))),
    @ApiResponse(
        responseCode = "304",
        description = NOT_MODIFIED_DESCRIPTION,
        headers = {
          @Header(name = "ETag", description = ETAG_DESCRIPTION, schema = @Schema(type = "string")),
          @Header(
              name = "Cache-Control",
              description = THUMBNAIL_CACHE_DESCRIPTION,
              schema = @Schema(type = "string"))
        }),
    @ApiResponse(
        responseCode = "400",
        description = BAD_REQUEST_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples =
                    @ExampleObject(value = AttachmentApiExamples.BAD_REQUEST_THUMBNAIL_PATH))),
    @ApiResponse(
        responseCode = "401",
        description = UNAUTHORIZED_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = AttachmentApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht sichtbar oder der Anfrager ist kein Mitglied, Anhang unbekannt,"
                + " deaktiviert oder an einer anderen Aufgabe, Typ ohne Vorschaubild, Datei fehlt"
                + " oder die Erzeugung des Vorschaubilds ist gescheitert (neutral maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "aufgabe",
                      summary = "Aufgabe nicht sichtbar oder kein Mitglied",
                      value = AttachmentApiExamples.NOT_FOUND_THUMBNAIL_TASK),
                  @ExampleObject(
                      name = "anhang",
                      summary = "Anhang unbekannt oder ohne Vorschaubild",
                      value = AttachmentApiExamples.NOT_FOUND_THUMBNAIL_ATTACHMENT)
                }))
  })
  @GetMapping("/{id}/attachments/{attachmentId}/thumbnail")
  public ResponseEntity<StreamingResponseBody> thumbnail(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @Parameter(description = "Externe UUID des Anhangs.", required = true) @PathVariable
          final UUID attachmentId,
      @Parameter(
              in = ParameterIn.HEADER,
              name = HttpHeaders.IF_NONE_MATCH,
              description = IF_NONE_MATCH_DESCRIPTION)
          @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false)
          final String ifNoneMatch) {
    return deliver(
        queryService.serveThumbnail(id, attachmentId, ifNoneMatch), ContentDisposition.inline());
  }

  /**
   * Baut die Antwort einer Auslieferung: 304 nur mit ETag und Cache-Control, sonst 200 mit allen
   * Headern und einem Body, der den im Service geoeffneten Strom kopiert und schliesst.
   */
  private static ResponseEntity<StreamingResponseBody> deliver(
      final AttachmentDelivery delivery, final ContentDisposition.Builder disposition) {
    final CacheControl cacheControl = CacheControl.maxAge(delivery.maxAge()).cachePrivate();
    if (delivery.notModified()) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .eTag(delivery.etag())
          .cacheControl(cacheControl)
          .build();
    }
    final HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(delivery.mimeType()));
    if (delivery.contentLength() >= 0) {
      headers.setContentLength(delivery.contentLength());
    }
    headers.setETag(delivery.etag());
    headers.setCacheControl(cacheControl);
    headers.set(CONTENT_TYPE_OPTIONS, NOSNIFF);
    headers.setContentDisposition(
        delivery.fileName() == null
            ? disposition.build()
            : disposition.filename(delivery.fileName(), StandardCharsets.UTF_8).build());
    final InputStream stream = delivery.stream();
    final StreamingResponseBody body =
        out -> {
          try (InputStream in = stream) {
            in.transferTo(out);
          }
        };
    return new ResponseEntity<>(body, headers, HttpStatus.OK);
  }
}
