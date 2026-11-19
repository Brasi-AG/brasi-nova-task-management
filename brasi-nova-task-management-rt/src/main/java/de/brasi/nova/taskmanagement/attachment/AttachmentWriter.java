package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentQueryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Legt die Zeilen der Anhaenge an und schreibt ihre Dateien (Intake #152). Laeuft nur innerhalb der
 * Transaktion des Aufrufers ({@link Propagation#MANDATORY}).
 *
 * <p>Ablauf je Datei: Insert mit {@code SPEICHERSCHLUESSEL = null} (KSF vergibt die ID erst im
 * Insert), Schluessel aus der ID ({@link AttachmentStorageKeys#contentKey}), Inhaltsdatei
 * schreiben, bei vorhandener Vorschau das Vorschaubild schreiben, dann Update derselben Instanz mit
 * dem Schluessel (der KSF-AuditAspect hebt die Version beim ersten {@code save}, das zweite ist ein
 * Update mit passender Version). Committet wird nur der vollstaendige Zustand; es entstehen nie
 * Metadaten ohne Datei.
 *
 * <p>Rollback: je Transaktion wird genau eine {@link TransactionSynchronization} registriert. Sie
 * merkt sich die Schluessel der Dateien, die <em>in dieser Transaktion</em> tatsaechlich
 * geschrieben wurden (erst nach erfolgreichem {@code writeContent}), und loescht nach einem
 * Rollback nur diese (Inhalt und Vorschaubild) ueber {@link
 * AttachmentContentProvider#deleteQuietly}. Fremde oder vorher vorhandene Dateien werden nie
 * angefasst.
 */
@Component
public class AttachmentWriter {

  private static final Logger log = LoggerFactory.getLogger(AttachmentWriter.class);

  private final TaskAttachmentQueryRepository attachmentRepository;
  private final AttachmentContentProvider contentProvider;

  /**
   * Konstruktor-Injektion.
   *
   * @param attachmentRepository Repository der Anhaenge
   * @param contentProvider Dateispeicher der Anhaenge
   */
  public AttachmentWriter(
      final TaskAttachmentQueryRepository attachmentRepository,
      final AttachmentContentProvider contentProvider) {
    this.attachmentRepository = attachmentRepository;
    this.contentProvider = contentProvider;
  }

  /**
   * Speichert vorbereitete Anhaenge an einer Aufgabe oder einem ihrer Kommentare.
   *
   * <p>Die {@link PreparedAttachment}s (und damit die hochgeladenen Dateien) sind nur waehrend des
   * Requests lesbar; sie werden hier synchron gelesen, nie nach dem Commit oder asynchron.
   *
   * @param task Aufgabe, an der die Anhaenge haengen (liefert Aufgaben- und Mandanten-ID)
   * @param commentId interne Kommentar-ID; {@code null} fuer Anhaenge direkt an der Aufgabe
   * @param actorRef UUID der hochladenden Person
   * @param prepared gepruefte und vorbereitete Dateien
   * @return die gespeicherten Anhaenge in der Reihenfolge der Eingabe, mit Schluessel
   * @throws org.springframework.transaction.IllegalTransactionStateException ohne laufende
   *     Transaktion
   * @throws IllegalStateException wenn eine Datei bereits existiert oder der Speicherpfad fehlt
   * @throws java.io.UncheckedIOException bei einem Schreibfehler
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<TaskAttachment> store(
      final Task task,
      final Long commentId,
      final UUID actorRef,
      final List<PreparedAttachment> prepared) {
    final RollbackCleanup cleanup = cleanupForCurrentTransaction();
    final List<TaskAttachment> stored = new ArrayList<>(prepared.size());
    for (final PreparedAttachment file : prepared) {
      stored.add(storeOne(task, commentId, actorRef, file, cleanup));
    }
    return stored;
  }

  private TaskAttachment storeOne(
      final Task task,
      final Long commentId,
      final UUID actorRef,
      final PreparedAttachment file,
      final RollbackCleanup cleanup) {
    final TaskAttachment attachment = new TaskAttachment();
    attachment.setTenantId(task.getTenantId());
    attachment.setTaskId(task.getId());
    attachment.setCommentId(commentId);
    attachment.setFileName(file.fileName());
    attachment.setMimeType(file.type().mimeType());
    attachment.setFileExtension(file.extension());
    attachment.setSizeBytes(file.sizeBytes());
    attachment.setSha256(file.sha256Hex());
    attachment.setStorageKey(null);
    attachment.setThumbnailPresent(file.hasThumbnail());
    attachment.setUploadedByRef(actorRef);
    final TaskAttachment inserted = attachmentRepository.save(attachment);

    final String storageKey =
        AttachmentStorageKeys.contentKey(task.getTenantId(), inserted.getId(), file.extension());
    contentProvider.writeContent(storageKey, file.source());
    // Erst nach erfolgreichem Schreiben merken: eine vorhandene Datei (writeContent wirft) gehoert
    // nicht dieser Transaktion und darf beim Rollback nicht geloescht werden.
    cleanup.remember(storageKey);
    if (file.hasThumbnail()) {
      contentProvider.writeThumbnail(storageKey, file.thumbnailJpeg());
    }

    inserted.setStorageKey(storageKey);
    final TaskAttachment saved = attachmentRepository.save(inserted);
    log.debug(
        "Anhang {} (id={}) fuer Aufgabe {} unter {} gespeichert.",
        saved.getUuid(),
        saved.getId(),
        task.getId(),
        storageKey);
    return saved;
  }

  /**
   * Liefert die Aufraeum-Synchronisation der laufenden Transaktion; beim ersten Aufruf je
   * Transaktion wird sie registriert. Die Synchronisationen einer ausgesetzten Transaktion sind
   * hier nicht sichtbar, daher gehoert eine gefundene Instanz immer zur laufenden Transaktion.
   */
  private RollbackCleanup cleanupForCurrentTransaction() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      // Ohne Synchronisation koennten Dateien nach einem Rollback liegen bleiben.
      throw new IllegalStateException(
          "Anhaenge koennen nur in einer Transaktion mit aktiver Synchronisation gespeichert"
              + " werden.");
    }
    for (final TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof RollbackCleanup cleanup && cleanup.owner == this) {
        return cleanup;
      }
    }
    final RollbackCleanup cleanup = new RollbackCleanup(this, contentProvider);
    TransactionSynchronizationManager.registerSynchronization(cleanup);
    return cleanup;
  }

  /**
   * Loescht nach einem Rollback die in dieser Transaktion geschriebenen Dateien. Nach einem Commit
   * passiert nichts; bei unbekanntem Ausgang (Commit-Fehler) bleiben die Dateien liegen, denn die
   * Zeilen koennten committet sein (lieber eine verwaiste Datei als Metadaten ohne Datei).
   */
  private static final class RollbackCleanup implements TransactionSynchronization {

    private final AttachmentWriter owner;
    private final AttachmentContentProvider contentProvider;
    private final List<String> writtenKeys = new ArrayList<>();

    private RollbackCleanup(
        final AttachmentWriter owner, final AttachmentContentProvider contentProvider) {
      this.owner = owner;
      this.contentProvider = contentProvider;
    }

    private void remember(final String storageKey) {
      writtenKeys.add(storageKey);
    }

    @Override
    public void afterCompletion(final int status) {
      if (status == STATUS_ROLLED_BACK) {
        for (final String storageKey : writtenKeys) {
          contentProvider.deleteQuietly(storageKey);
          log.info("Anhang-Datei {} nach Rollback der Transaktion entfernt.", storageKey);
        }
      } else if (status == STATUS_UNKNOWN && !writtenKeys.isEmpty()) {
        log.warn(
            "Ausgang der Transaktion unbekannt; {} Anhang-Datei(en) bleiben liegen: {}",
            writtenKeys.size(),
            writtenKeys);
      }
    }
  }
}
