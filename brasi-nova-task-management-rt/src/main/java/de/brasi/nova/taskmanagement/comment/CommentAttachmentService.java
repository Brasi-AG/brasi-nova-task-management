package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.attachment.AttachmentTaskAccess;
import de.brasi.nova.taskmanagement.attachment.AttachmentUploadPreparer;
import de.brasi.nova.taskmanagement.attachment.PreparedAttachment;
import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Kommentar mit Anhaengen anlegen (Intake #152 Teil B): Pruefung der Dateien vor der Transaktion,
 * Speichern von Kommentar und Anhaengen in einer Transaktion von {@link CommentService}.
 *
 * <p>Bewusst ohne eigene Transaktion, wie {@code AttachmentUploadService}: Typpruefung, Pruefsumme
 * und Vorschaubild ({@link AttachmentUploadPreparer}) koennen bei grossen Dateien dauern und sollen
 * keine Datenbanksperre halten. Die Mitgliedschaft wird vor jeder Dateipruefung verlangt ({@code
 * requireWriteMember}), damit ein Nicht-Mitglied immer die neutrale 404 bekommt und nie ueber eine
 * 400 etwas ueber die Aufgabe erfaehrt; {@link CommentService#createComment(UUID,
 * CommentCreateInput, List)} prueft sie in der Transaktion erneut.
 *
 * <p>Die Hoechstzahl gilt je Kommentar: ein neuer Kommentar hat noch keine Anhaenge, daher bereitet
 * der Preparer mit {@code activeCount = 0} vor; die Anhaenge direkt an der Aufgabe zaehlen nicht
 * mit. Den Text prueft bereits {@link CommentCreateInput} beim Lesen des Teils {@code comment} (vor
 * diesem Service), ein leerer Text erreicht die Dateipruefung also nie.
 *
 * <p>Die hochgeladenen Dateien sind nur waehrend des Requests lesbar; sie werden synchron
 * verarbeitet, nie nach dem Commit oder asynchron.
 */
@Service
public class CommentAttachmentService {

  /** Ein neuer Kommentar hat noch keine aktiven Anhaenge. */
  private static final int NO_ACTIVE_ATTACHMENTS = 0;

  private final UserAccessor userAccessor;
  private final AttachmentTaskAccess taskAccess;
  private final AttachmentUploadPreparer preparer;
  private final CommentService commentService;

  /**
   * Konstruktor-Injektion.
   *
   * @param userAccessor liefert den angemeldeten Benutzer
   * @param taskAccess laedt die Aufgabe und prueft die Mitgliedschaft
   * @param preparer prueft und bereitet die Dateien vor
   * @param commentService speichert Kommentar und Anhaenge in einer Transaktion
   */
  public CommentAttachmentService(
      final UserAccessor userAccessor,
      final AttachmentTaskAccess taskAccess,
      final AttachmentUploadPreparer preparer,
      final CommentService commentService) {
    this.userAccessor = userAccessor;
    this.taskAccess = taskAccess;
    this.preparer = preparer;
    this.commentService = commentService;
  }

  /**
   * Prueft die Dateien und legt den Kommentar mit ihnen an (alles oder nichts).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input Kommentar-Eingabe (Text bereits geprueft)
   * @param files hochgeladene Dateien in Upload-Reihenfolge
   * @return der neue Kommentar mit seinen Anhaengen in Upload-Reihenfolge
   * @throws io.keel.framework.api.exception.EntityNotFoundException wenn die Aufgabe nicht sichtbar
   *     oder der Akteur kein aktives Mitglied ist (HTTP 404, vor jeder Dateipruefung)
   * @throws EntityValidationException wenn eine Datei ungueltig ist, mehr Dateien als erlaubt
   *     vorliegen oder der Eltern-Kommentar ungueltig ist (HTTP 400); es wird nichts gespeichert
   */
  public CommentModel createCommentWithAttachments(
      final UUID taskExternalId, final CommentCreateInput input, final List<MultipartFile> files) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    taskAccess.requireWriteMember(taskExternalId, actor);
    final List<PreparedAttachment> prepared =
        preparer.prepare(actor.tenant().id(), NO_ACTIVE_ATTACHMENTS, files);
    return commentService.createComment(taskExternalId, input, prepared);
  }
}
