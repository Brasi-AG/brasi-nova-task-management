package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Hochladen von Dateien an eine Aufgabe (Intake #152): Pruefung vor der Transaktion, Speichern in
 * der Transaktion von {@link AttachmentCommandService}.
 *
 * <p>Bewusst ohne eigene Transaktion: Typpruefung, Pruefsumme und Vorschaubild ({@link
 * AttachmentUploadPreparer}) koennen bei grossen Dateien dauern und sollen keine Datenbanksperre
 * halten. Die Mitgliedschaft wird vor jeder Dateipruefung verlangt ({@code requireWriteMember}),
 * damit ein Nicht-Mitglied immer die neutrale 404 bekommt und nie ueber eine 400 etwas ueber die
 * Aufgabe erfaehrt. Die Zahl der aktiven Anhaenge wird hier fuer eine fruehe 400 gelesen und in der
 * Transaktion erneut geprueft.
 *
 * <p>Die hochgeladenen Dateien sind nur waehrend des Requests lesbar; sie werden synchron
 * verarbeitet, nie nach dem Commit oder asynchron.
 */
@Service
public class AttachmentUploadService {

  private final UserAccessor userAccessor;
  private final AttachmentTaskAccess taskAccess;
  private final TaskAttachmentQueryRepository attachmentRepository;
  private final AttachmentUploadPreparer preparer;
  private final AttachmentCommandService commandService;

  /**
   * Konstruktor-Injektion.
   *
   * @param userAccessor liefert den angemeldeten Benutzer
   * @param taskAccess laedt die Aufgabe und prueft die Mitgliedschaft
   * @param attachmentRepository Zahl der aktiven Anhaenge
   * @param preparer prueft und bereitet die Dateien vor
   * @param commandService speichert in einer Transaktion
   */
  public AttachmentUploadService(
      final UserAccessor userAccessor,
      final AttachmentTaskAccess taskAccess,
      final TaskAttachmentQueryRepository attachmentRepository,
      final AttachmentUploadPreparer preparer,
      final AttachmentCommandService commandService) {
    this.userAccessor = userAccessor;
    this.taskAccess = taskAccess;
    this.attachmentRepository = attachmentRepository;
    this.preparer = preparer;
    this.commandService = commandService;
  }

  /**
   * Prueft die Dateien und haengt sie an die Aufgabe (alles oder nichts).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param files hochgeladene Dateien in Upload-Reihenfolge
   * @return die neuen Anhaenge in Upload-Reihenfolge
   * @throws io.keel.framework.api.exception.EntityNotFoundException wenn die Aufgabe nicht sichtbar
   *     oder der Akteur kein aktives Mitglied ist (HTTP 404, vor jeder Dateipruefung)
   * @throws EntityValidationException wenn eine Datei ungueltig ist oder die Aufgabe zu viele
   *     Anhaenge bekaeme (HTTP 400); es wird nichts gespeichert
   */
  public List<AttachmentModel> upload(final UUID taskExternalId, final List<MultipartFile> files) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final Task task = taskAccess.requireWriteMember(taskExternalId, actor);
    final long activeCount = attachmentRepository.countActiveTaskAttachments(task.getId());
    final List<PreparedAttachment> prepared =
        preparer.prepare(actor.tenant().id(), Math.toIntExact(activeCount), files);
    return commandService.storeTaskAttachments(taskExternalId, prepared);
  }
}
