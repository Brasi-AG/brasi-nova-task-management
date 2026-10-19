package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.TaskInboxGeneration;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import io.keel.framework.api.exception.BusinessRuleException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Verwaltet den monotonen Inbox-Generationszaehler je (Mandant, Benutzer) fuer die
 * Snapshot-Rejection (Finding 2 / Option B).
 *
 * <p>Keine eigene {@code @Transactional}: Die Schreibpfade laufen in der Transaktion des Aufrufers
 * ({@link AssigneeService#updateAssignees}/{@link AssigneeService#exit} sind {@code @Transactional}).
 * Damit ist der Bump atomar mit der ausloesenden Membership-Mutation.
 */
@Service
public class InboxGenerationService {

  private static final Logger log = LoggerFactory.getLogger(InboxGenerationService.class);

  private final TaskInboxGenerationQueryRepository repository;

  /** Konstruktor-Injektion des {@link TaskInboxGenerationQueryRepository}. */
  public InboxGenerationService(final TaskInboxGenerationQueryRepository repository) {
    this.repository = repository;
  }

  /**
   * Aktuelle Generation des Benutzers im aktuellen Mandanten.
   *
   * @param userRef UUID des Benutzers
   * @return die Generation; {@code 0}, wenn noch keine Zeile existiert (Benutzer ohne destruktive
   *     Membership-Mutation)
   */
  public long currentGeneration(final UUID userRef) {
    final List<TaskInboxGeneration> existing = repository.findByUserRef(userRef);
    return existing.isEmpty() ? 0L : existing.get(0).getGeneration();
  }

  /**
   * Erhoeht die Generation des Benutzers atomar um 1; legt die Zeile bei Erstzugriff race-sicher an
   * (Start 0). Laueft in der Transaktion des Aufrufers.
   *
   * @param tenantId interner Mandanten-Schluessel (fuer die Anlage der Erstzeile)
   * @param userRef  UUID des Benutzers
   * @throws IllegalStateException wenn der atomare Increment nicht genau eine Zeile trifft (kein
   *     stiller Fehl-Bump, der eine Snapshot-Invalidierung verschlucken wuerde)
   */
  public void bump(final long tenantId, final UUID userRef) {
    ensure(tenantId, userRef);
    final int updated = repository.incrementGeneration(userRef);
    if (updated != 1) {
      throw new IllegalStateException(
          "Inbox-Generation-Bump betraf "
              + updated
              + " Zeilen (erwartet genau 1) fuer Benutzer "
              + userRef);
    }
    log.debug("InboxGenerationService.bump: Generation fuer Benutzer {} erhoeht.", userRef);
  }

  /**
   * Stellt idempotent sicher, dass eine Generationszeile (Start 0) existiert. Re-Read-Translate gegen
   * den UNIQUE-Race (nebenlaeufiger Erst-Insert): bei {@link BusinessRuleException} erneut lesen;
   * existiert die Zeile nun, idempotent weiter, sonst rethrow.
   */
  private void ensure(final long tenantId, final UUID userRef) {
    if (!repository.findByUserRef(userRef).isEmpty()) {
      return;
    }
    final TaskInboxGeneration row = new TaskInboxGeneration();
    row.setTenantId(tenantId);
    row.setUserRef(userRef);
    row.setGeneration(0L);
    try {
      repository.save(row);
    } catch (final BusinessRuleException concurrentInsert) {
      if (repository.findByUserRef(userRef).isEmpty()) {
        throw concurrentInsert;
      }
      log.debug(
          "InboxGenerationService.ensure: Nebenlaeufige Anlage erkannt fuer Benutzer {} -"
              + " idempotent.",
          userRef);
    }
  }
}
