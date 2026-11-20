package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.comment.model.CommentWithAttachmentsSchema;
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
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.Part;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Handgeschriebener Facade-Controller fuer Kommentar-Operationen (arch-v2 §7).
 *
 * <p>Alle Handler sind Standard-Selfservice (authenticated-only via KSF-Filter-Chain); die
 * Autorisierung erfolgt objekt-level ueber den {@code TaskMembershipGuard} in den delegierten
 * Services: {@code getComments} (GET) verlangt READ-Member, {@code createComment} und {@code
 * createCommentWithAttachments} (POST) WRITE-Member (Non-/Ex-Member -> neutrale 404 bzw. kein
 * Comment-Write; beim Kommentar mit Anhaengen vor jeder Dateipruefung). Actor-UUID kommt
 * ausschliesslich aus dem Security-Context, NIEMALS aus Client-Eingabedaten. Der Controller
 * enthaelt keine Fachlogik — er delegiert an {@link CommentService} (Schreiben), {@link
 * CommentAttachmentService} (Kommentar mit Anhaengen: Dateipruefung, dann Schreiben ueber {@link
 * CommentService}) und {@link CommentQueryService} (Lesen).
 */
@RestController
@TranslatesLockConflicts(message = TranslatesLockConflicts.TASK)
@RequestMapping("/api/v1/tasks")
public class CommentController {

  private final CommentService commentService;
  private final CommentQueryService commentQueryService;
  private final CommentAttachmentService commentAttachmentService;
  private final CommentPartReader commentPartReader;

  /**
   * Konstruktor-Injektion der Kommentar-Services.
   *
   * @param commentService transaktionale Kommando-Operationen der Kommentare
   * @param commentQueryService liest die Kommentare
   * @param commentAttachmentService prueft die Dateien eines Kommentars mit Anhaengen und legt ihn
   *     an
   * @param commentPartReader liest den Teil {@code comment} des Multipart-Requests als JSON
   */
  public CommentController(
      final CommentService commentService,
      final CommentQueryService commentQueryService,
      final CommentAttachmentService commentAttachmentService,
      final CommentPartReader commentPartReader) {
    this.commentService = commentService;
    this.commentQueryService = commentQueryService;
    this.commentAttachmentService = commentAttachmentService;
    this.commentPartReader = commentPartReader;
  }

  /**
   * Liefert alle aktiven Kommentare einer Aufgabe als flache, chronologische Liste.
   *
   * <p>Der Client baut den Antwort-Baum ueber {@code parentCommentId}. Standard-Selfservice:
   * READ-Member-Guard im Service (konsistent mit {@code GET /tasks/{id}}); nicht gefundene/fremde/
   * deaktivierte Aufgabe ODER kein Mitglied wird neutral als 404 maskiert.
   *
   * @param id externe UUID der Aufgabe
   * @return 200 OK mit chronologischer Liste von {@link CommentModel}
   */
  @Operation(
      summary = "Kommentare einer Aufgabe lesen",
      description =
          "Liefert die flache, chronologische Kommentarliste einer Aufgabe."
              + " Der Client rekonstruiert den Antwort-Baum ueber parentCommentId; sie ist null bei"
              + " einem Kommentar ohne Eltern-Kommentar oder wenn der Eltern-Kommentar nicht mehr"
              + " aktiv ist. Jeder Kommentar traegt im Feld attachments seine aktiven Anhaenge in"
              + " der Reihenfolge des Hochladens (aelteste zuerst; ohne Anhang eine leere Liste)."
              + " Der Inhalt steht nicht im JSON: abgerufen werden die Anhaenge ueber die"
              + " Anhang-Endpunkte der Aufgabe (Links je Anhang), auch von ausgetretenen"
              + " Mitgliedern. Anhaenge eines Kommentars lassen sich nicht entfernen (removable"
              + " ist immer false) und stehen nicht in der Liste der Anhaenge der Aufgabe. Lesen"
              + " duerfen Mitglieder der Aufgabe, auch ausgetretene (Archiv); fuer alle anderen"
              + " ist die Aufgabe nicht sichtbar (404).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Kommentare chronologisch aufsteigend, jeder mit seinen Anhaengen.",
        content =
            @Content(
                mediaType = "application/json",
                array = @ArraySchema(schema = @Schema(implementation = CommentModel.class)),
                examples =
                    @ExampleObject(
                        name = "kommentar-und-antwort",
                        summary = "Ein Kommentar und eine Antwort darauf",
                        value = CommentApiExamples.LIST_COMMENTS))),
    @ApiResponse(
        responseCode = "400",
        description = "Ungueltige Anfrage: keine gueltige UUID im Pfad.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = CommentApiExamples.BAD_REQUEST_PATH))),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = CommentApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder kein Mitglied (neutral maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = CommentApiExamples.NOT_FOUND)))
  })
  @GetMapping("/{id}/comments")
  @HateoasLinkTarget(TaskHateoas.Targets.GET_COMMENTS)
  public ResponseEntity<List<CommentModel>> getComments(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id) {
    return ResponseEntity.ok(commentQueryService.getComments(id));
  }

  /**
   * Erstellt einen neuen Kommentar fuer eine Aufgabe.
   *
   * <p>Optional als Antwort auf einen anderen Kommentar ({@code parentCommentId} im Body). Ein
   * Eltern-Kommentar loest eine REPLY-Benachrichtigung an dessen Autor aus. Die in {@code mentions}
   * genannten Personen erhalten eine MENTION: Mitglieder der Aufgabe direkt; aktive Benutzer des
   * eigenen Mandanten, die noch kein Mitglied sind, werden zuvor als Beteiligte hinzugefuegt (nur
   * Teilnehmer, nie Assignee), wenn der Kommentierende dieselbe Berechtigung wie fuer {@code POST
   * /participants} hat (Intake #156); sonst werden sie still verworfen, ebenso unbekannte, inaktive
   * oder mandantenfremde UUIDs.
   *
   * @param id externe UUID der Aufgabe
   * @param input Kommentar-Eingabe
   * @return 201 Created mit {@link CommentModel} des neuen Kommentars
   */
  @Operation(
      summary = "Kommentar erstellen",
      description =
          "Erstellt einen neuen Kommentar fuer eine Aufgabe, optional als Antwort auf einen anderen"
              + " Kommentar (parentCommentId). Nur aktive Mitglieder der Aufgabe duerfen"
              + " kommentieren. Benachrichtigungen: Der Autor des Eltern-Kommentars erhaelt REPLY,"
              + " die in mentions genannten Personen erhalten MENTION (auch bei stummgeschalteter"
              + " Aufgabe), bei einem Kommentar ohne Eltern-Kommentar erhalten die nicht"
              + " stummgeschalteten Assignees COMMENT; die aufrufende Person selbst wird nie"
              + " benachrichtigt. Erwaehnungen sind explizite UUIDs in mentions und werden nicht"
              + " aus dem Text gelesen. Ist eine erwaehnte Person ein aktiver Benutzer des eigenen"
              + " Mandanten, aber noch kein aktives Mitglied der Aufgabe (auch wer ausgetreten ist oder"
              + " eine deaktivierte Teilnehmerzeile hat; der Benutzer selbst ist aktiv),"
              + " wird sie als Beteiligte hinzugefuegt, sofern die aufrufende Person Beteiligte"
              + " hinzufuegen darf, also dieselbe Berechtigung TASK_WRITE hat wie bei POST"
              + " /api/v1/tasks/{id}/participants: Sie wird reiner Teilnehmer (nie Assignee; ein"
              + " Ausgetretener wird als reiner Teilnehmer reaktiviert, eine Stummschaltung bleibt)"
              + " und erhaelt nur die MENTION, keine zusaetzliche PARTICIPANT-Benachrichtigung."
              + " Fehlt der aufrufenden Person die Berechtigung, werden erwaehnte Nicht-Mitglieder"
              + " still verworfen (kein Fehler, keine Aufnahme, keine Benachrichtigung), erwaehnte"
              + " Mitglieder werden weiter benachrichtigt. Unbekannte, inaktive und"
              + " mandantenfremde UUIDs in mentions werden immer still verworfen (kein 400). Alles"
              + " oder nichts: bei einem Fehler wird weder der Kommentar noch eine Aufnahme"
              + " gespeichert.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description =
            "Kommentar erfolgreich erstellt; die Benachrichtigungen sind gespeichert, erwaehnte"
                + " Nicht-Mitglieder sind (mit Berechtigung) Beteiligte der Aufgabe.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CommentModel.class),
                examples = {
                  @ExampleObject(
                      name = "top-level",
                      summary = "Kommentar ohne Eltern-Kommentar",
                      description = "parentCommentId ist null.",
                      value = CommentApiExamples.CREATED_TOP_LEVEL),
                  @ExampleObject(
                      name = "antwort",
                      summary = "Antwort auf einen Kommentar",
                      value = CommentApiExamples.CREATED_REPLY)
                })),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Eingabe: text fehlt oder ist leer, mehr als 100 oder ein null-Element in"
                + " mentions, Eltern-Kommentar unbekannt, zu einer anderen Aufgabe gehoerig oder"
                + " nicht aktiv, kein gueltiges JSON oder keine UUID im Pfad. Eine unbekannte,"
                + " inaktive oder mandantenfremde UUID in mentions ist kein Fehler (still"
                + " verworfen).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "eltern-kommentar-ungueltig",
                      summary = "Eltern-Kommentar unbekannt, fremd oder inaktiv",
                      value = CommentApiExamples.BAD_REQUEST_PARENT),
                  @ExampleObject(
                      name = "text-fehlt",
                      summary = "Body ohne Text oder mit leerem Text",
                      value = CommentApiExamples.BAD_REQUEST_BODY)
                })),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = CommentApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant oder kein aktives Mitglied der Aufgabe"
                + " (neutral maskiert).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = CommentApiExamples.NOT_FOUND))),
    @ApiResponse(
        responseCode = "409",
        description =
            "Mehrere Ursachen, in allen Faellen wurde nichts gespeichert (weder Kommentar noch"
                + " Aufnahme). (1) Nebenlaeufiger Konflikt: die Aufgabe (Zaehler und Aktivitaet)"
                + " oder bei der Aufnahme einer erwaehnten Person deren Teilnehmerzeile bzw."
                + " persoenlicher Zustand wurde zwischenzeitlich geaendert (Versionskonflikt) oder"
                + " gleichzeitig erstmals angelegt; die Anfrage gegen den frischen Stand"
                + " wiederholen. (2) Die vorhandene Teilnehmerzeile einer erwaehnten Person, die"
                + " aufgenommen werden soll, ist keine PERSON-Zeile des Mandanten (TEAM-Zeile oder"
                + " fremder Mandant, Datenfehler); sie wird nicht ueberschrieben und die Anfrage"
                + " scheitert, bis der Datenbestand bereinigt ist. Ausgetretene Personen oder solche mit"
                + " deaktivierter Teilnehmerzeile (bei aktivem Benutzer) sind als"
                + " Erwaehnte kein Konfliktgrund: sie werden reaktiviert."
                + LockConflictApiExamples.DESCRIPTION_SUFFIX,
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = CommentApiExamples.CONFLICT)))
  })
  @PostMapping("/{id}/comments")
  @HateoasLinkTarget(TaskHateoas.Targets.CREATE_COMMENT)
  public ResponseEntity<CommentModel> createComment(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description =
                  "Kommentartext, optional Eltern-Kommentar und erwaehnte Benutzer (hoechstens"
                      + " 100).",
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = CommentCreateInput.class),
                      examples = {
                        @ExampleObject(
                            name = "top-level",
                            summary = "Kommentar ohne Eltern-Kommentar",
                            value = CommentApiExamples.REQUEST_TOP_LEVEL),
                        @ExampleObject(
                            name = "mit-erwaehnung",
                            summary = "Kommentar mit Erwaehnung einer Person",
                            description =
                                "Ist die Person noch kein Mitglied der Aufgabe, wird sie mit der"
                                    + " Berechtigung TASK_WRITE als Beteiligte hinzugefuegt und"
                                    + " erhaelt nur die MENTION; ohne Berechtigung wird die"
                                    + " Erwaehnung still verworfen.",
                            value = CommentApiExamples.REQUEST_WITH_MENTION),
                        @ExampleObject(
                            name = "antwort",
                            summary = "Antwort auf einen Kommentar",
                            value = CommentApiExamples.REQUEST_REPLY)
                      }))
          @RequestBody
          final CommentCreateInput input) {
    final CommentModel model = commentService.createComment(id, input);
    return ResponseEntity.status(HttpStatus.CREATED).body(model);
  }

  /**
   * Erstellt einen Kommentar mit einer oder mehreren Dateien (alles oder nichts, Intake #152).
   *
   * <p>Der Teil {@code comment} folgt den Regeln von {@link #createComment} (Text Pflicht,
   * Eltern-Kommentar, Erwaehnungen, Benachrichtigungen), die Teile {@code files} den Regeln des
   * Hochladens an der Aufgabe (hoechstens 20 je Kommentar). Keine eigene Link-Relation: das Recht
   * zeigt {@code add-comment}. Den Ablauf beschreibt {@link
   * CommentAttachmentService#createCommentWithAttachments}.
   *
   * <p>Den Teil {@code comment} liest {@link CommentPartReader} als JSON, gleich mit welchem
   * Content-Type er ankommt: {@code application/json} ist empfohlen, ein Teil ohne Content-Type
   * oder mit {@code text/plain} (so sendet ihn ein Browser mit {@code FormData.append}) wird mit
   * demselben JSON angenommen. Das Lesen steht vor der Pruefung der Mitgliedschaft.
   *
   * <p>Der Teil {@code comment} wird als {@link Part} angenommen, nicht als {@code byte[]}, {@code
   * String} oder {@link MultipartFile} (Intake #152 Teil B): Ein {@code byte[]} oder {@code String}
   * liest Spring ueber einen MessageConverter, und danach laeuft jede {@code RequestBodyAdvice}.
   * Die KSF-Advice {@code NovaTenantBindingRequestBodyAdvice} (ksf-web) behandelt dabei jedes Array
   * als {@code Object[]} und scheitert an Primitiv-Arrays wie {@code byte[]} mit {@code
   * ClassCastException} (500). Ein {@link Part} loest Spring direkt aus der Anfrage auf, ohne
   * MessageConverter und ohne Advice. {@link MultipartFile} passt nicht, weil ein Teil ohne
   * Dateinamen (so sendet ihn {@code FormData.append('comment', string)}) in der Multipart-Anfrage
   * nicht als Datei gefuehrt wird.
   *
   * @param id externe UUID der Aufgabe
   * @param comment Teil {@code comment} (JSON, UTF-8); den Inhalt liest {@link CommentPartReader}
   * @param files hochgeladene Dateien (Teile {@code files}, mindestens einer)
   * @return 201 Created mit {@link CommentModel} des neuen Kommentars samt Anhaengen
   * @throws MissingServletRequestPartException wenn der Teil {@code comment} leer ist; er gilt wie
   *     ein fehlender Teil (HTTP 400, wie bisher)
   */
  @Operation(
      summary = "Kommentar mit Anhängen erstellen",
      description =
          "Erstellt einen Kommentar mit einer oder mehreren Dateien als multipart/form-data: der"
              + " Teil comment enthaelt den Kommentar als JSON (empfohlen Content-Type"
              + " application/json, Felder wie bei POST /api/v1/tasks/{id}/comments), je Datei ein"
              + " Teil files. Ein Teil comment ohne Content-Type oder mit text/plain, wie ihn ein"
              + " Browser mit FormData.append('comment', JSON.stringify(...)) sendet, wird mit"
              + " demselben JSON ebenfalls angenommen (UTF-8). Der Text ist Pflicht; ein Kommentar"
              + " ohne Datei geht ueber POST"
              + " /api/v1/tasks/{id}/comments. Fuer den Kommentar gelten dieselben Regeln wie dort:"
              + " optional Antwort auf einen anderen Kommentar (parentCommentId), Erwaehnungen als"
              + " explizite UUIDs in mentions (erwaehnte Nicht-Mitglieder werden mit der"
              + " Berechtigung TASK_WRITE als Beteiligte aufgenommen, sonst still verworfen). Fuer"
              + " die Dateien gelten dieselben Regeln wie beim Hochladen an der Aufgabe: erlaubt"
              + " sind PDF, Excel (xls, xlsx), Word (doc, docx), PowerPoint (ppt, pptx), Text (txt,"
              + " csv), Bilder (png, jpg, jpeg) und Video (mp4); geprueft werden Endung und"
              + " Inhalt. Die Grenze"
              + " je Datei haengt vom Tarif des Mandanten ab (Vorgaben: STANDARD 5 MB, EXPERT bis"
              + " 10 MB, ENTERPRISE bis 15 MB; harte Obergrenze fuer alle 15 MB). Leere Dateien"
              + " werden abgelehnt. Ein Kommentar hat hoechstens 20 Anhaenge (die Anhaenge der"
              + " Aufgabe zaehlen nicht mit). Fuer PDF und Bilder entsteht beim Hochladen ein"
              + " Vorschaubild. Alles oder nichts: ist der Text leer, eine Datei ungueltig oder"
              + " scheitert ein Schritt, wird weder der Kommentar noch ein Anhang, eine Datei oder"
              + " eine Aufnahme gespeichert. Benachrichtigungen wie bei einem Kommentar ohne"
              + " Anhang: der Autor des Eltern-Kommentars erhaelt REPLY, die in mentions genannten"
              + " Personen MENTION, bei einem Kommentar ohne Eltern-Kommentar die nicht"
              + " stummgeschalteten Assignees COMMENT; die aufrufende Person wird nie"
              + " benachrichtigt, fuer die Anhaenge gibt es keine eigene Benachrichtigung. Die"
              + " Anhaenge stehen am Kommentar (Feld attachments, auch in GET"
              + " /api/v1/tasks/{id}/comments), nicht in der Liste der Anhaenge der Aufgabe;"
              + " abgerufen werden sie ueber die Anhang-Endpunkte der Aufgabe, entfernen lassen"
              + " sie sich nicht. Kommentieren duerfen nur aktive Mitglieder der Aufgabe; fuer alle"
              + " anderen ist die Aufgabe nicht sichtbar (404), auch bei ungueltigem Dateityp,"
              + " Inhalt oder Tarifgrenze.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description =
                  "Multipart-Formular mit dem Kommentar im Teil comment (JSON, empfohlen mit"
                      + " Content-Type application/json; ohne Content-Type oder als text/plain"
                      + " wird dasselbe JSON ebenfalls angenommen) und mindestens einer Datei im"
                      + " Teil files (mehrere Teile mit demselben Namen fuer mehrere Dateien).",
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                      schema = @Schema(implementation = CommentWithAttachmentsSchema.class),
                      encoding =
                          @Encoding(
                              name = "comment",
                              contentType = MediaType.APPLICATION_JSON_VALUE),
                      examples =
                          @ExampleObject(
                              name = "text-und-zwei-dateien",
                              summary = "Kommentar mit Erwaehnung und zwei Dateien",
                              description =
                                  "Der Teil comment ist JSON, der Binaerinhalt steht in den"
                                      + " Teilen files; das Beispiel zeigt nur die Struktur.",
                              value = CommentApiExamples.REQUEST_WITH_ATTACHMENTS))))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description =
            "Kommentar und alle Dateien gespeichert; die Antwort traegt die neuen Anhaenge in"
                + " Upload-Reihenfolge mit Abruf-Links (nicht entfernbar), die Benachrichtigungen"
                + " sind gespeichert.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CommentModel.class),
                examples =
                    @ExampleObject(
                        name = "pdf-und-text",
                        summary = "Kommentar mit einem PDF und einer Textdatei",
                        value = CommentApiExamples.CREATED_WITH_ATTACHMENTS))),
    @ApiResponse(
        responseCode = "400",
        description =
            "Ungueltige Anfrage, nichts wurde gespeichert. Vor der Pruefung der Mitgliedschaft,"
                + " also auch fuer Nicht-Mitglieder, werden geprueft und mit 400 abgelehnt: ein"
                + " fehlender Teil comment oder files, ein nicht lesbares JSON im Teil comment, ein"
                + " fehlender oder leerer text, mehr als 100 oder ein null-Element in mentions, die"
                + " Groessengrenze des Multipart-Requests (eine Datei ueber der harten Obergrenze"
                + " oder die ganze Anfrage zu gross), ein nicht lesbares multipart/form-data und"
                + " eine ungueltige UUID im Pfad. Erst danach, und nur fuer aktive Mitglieder"
                + " (Nicht-Mitglieder erhalten die neutrale 404), werden die Dateien geprueft:"
                + " Dateityp nicht erlaubt, leere Datei, Datei groesser als die Grenze des Tarifs,"
                + " Inhalt passt nicht zur Endung, mehr als 20 Dateien. Zuletzt wird der"
                + " Eltern-Kommentar geprueft: unbekannt, zu einer anderen Aufgabe gehoerig oder"
                + " nicht aktiv. Eine unbekannte, inaktive oder mandantenfremde UUID in mentions"
                + " ist kein Fehler (still verworfen).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = {
                  @ExampleObject(
                      name = "text-fehlt",
                      summary = "Teil comment ohne Text oder mit leerem Text",
                      value = CommentApiExamples.BAD_REQUEST_WITH_ATTACHMENTS_TEXT),
                  @ExampleObject(
                      name = "eltern-kommentar-ungueltig",
                      summary = "Eltern-Kommentar unbekannt, fremd oder inaktiv",
                      value = CommentApiExamples.BAD_REQUEST_WITH_ATTACHMENTS_PARENT),
                  @ExampleObject(
                      name = "typ-nicht-erlaubt",
                      summary = "Endung einer Datei nicht in der Erlaubtliste",
                      value = CommentApiExamples.BAD_REQUEST_WITH_ATTACHMENTS_TYPE),
                  @ExampleObject(
                      name = "tarifgrenze",
                      summary = "Datei groesser als die Grenze des Tarifs",
                      value = CommentApiExamples.BAD_REQUEST_WITH_ATTACHMENTS_TARIFF_LIMIT),
                  @ExampleObject(
                      name = "anzahl",
                      summary = "Mehr als 20 Dateien",
                      value = CommentApiExamples.BAD_REQUEST_WITH_ATTACHMENTS_COUNT),
                  @ExampleObject(
                      name = "kommentar-fehlt",
                      summary = "Kein Teil comment im Formular",
                      value = CommentApiExamples.BAD_REQUEST_WITH_ATTACHMENTS_MISSING_COMMENT),
                  @ExampleObject(
                      name = "dateien-fehlen",
                      summary = "Kein Teil files im Formular",
                      value = CommentApiExamples.BAD_REQUEST_WITH_ATTACHMENTS_MISSING_FILES),
                  @ExampleObject(
                      name = "keine-uuid",
                      summary = "Keine gueltige UUID im Pfad",
                      value = CommentApiExamples.BAD_REQUEST_WITH_ATTACHMENTS_PATH)
                })),
    @ApiResponse(
        responseCode = "401",
        description = "Kein oder ungueltiges JWT.",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ArchiveSearchPageSchema.Problem.class),
                examples = @ExampleObject(value = CommentApiExamples.UNAUTHORIZED))),
    @ApiResponse(
        responseCode = "404",
        description =
            "Aufgabe nicht gefunden, fremder Mandant, deaktiviert oder kein aktives Mitglied der"
                + " Aufgabe (neutral maskiert, auch bei ungueltigem Dateityp, Inhalt oder"
                + " Tarifgrenze).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = CommentApiExamples.NOT_FOUND_WITH_ATTACHMENTS))),
    @ApiResponse(
        responseCode = "409",
        description =
            "Mehrere Ursachen, in allen Faellen wurde nichts gespeichert (weder Kommentar noch"
                + " Anhang, Datei oder Aufnahme). (1) Nebenlaeufiger Konflikt wie bei POST"
                + " /api/v1/tasks/{id}/comments: die Aufgabe (Zaehler und Aktivitaet) oder bei der"
                + " Aufnahme einer erwaehnten Person deren Teilnehmerzeile bzw. persoenlicher"
                + " Zustand wurde zwischenzeitlich geaendert (Versionskonflikt) oder gleichzeitig"
                + " erstmals angelegt; die Anfrage gegen den frischen Stand wiederholen. (2)"
                + " Gleichzeitiger Insert: beim Anlegen der Anhaenge kann bei gleichzeitiger"
                + " Vergabe neuer Nummern ein Sequenzkonflikt auftreten; die Anfrage wiederholen."
                + " (3) Die vorhandene Teilnehmerzeile einer erwaehnten Person, die aufgenommen"
                + " werden soll, ist keine PERSON-Zeile des Mandanten (Datenfehler); die Anfrage"
                + " scheitert, bis der Datenbestand bereinigt ist."
                + LockConflictApiExamples.DESCRIPTION_SUFFIX,
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = CommentApiExamples.CONFLICT_WITH_ATTACHMENTS)))
  })
  @PostMapping(
      value = "/{id}/comments/with-attachments",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<CommentModel> createCommentWithAttachments(
      @Parameter(description = "Externe UUID der Aufgabe.", required = true) @PathVariable
          final UUID id,
      @Parameter(hidden = true) @RequestPart("comment") final Part comment,
      @Parameter(hidden = true) @RequestPart("files") final List<MultipartFile> files)
      throws MissingServletRequestPartException {
    final CommentCreateInput input = commentPartReader.read(comment);
    final CommentModel model =
        commentAttachmentService.createCommentWithAttachments(id, input, files);
    return ResponseEntity.status(HttpStatus.CREATED).body(model);
  }
}
