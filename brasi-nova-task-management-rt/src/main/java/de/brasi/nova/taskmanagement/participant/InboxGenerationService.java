package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.TaskInboxGeneration;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import io.keel.framework.api.exception.BusinessRuleException;
import java.util.Collection;
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
 *
 * <p><b>Deadlock-Robustheit (Codex #6):</b> Wo ueber MEHRERE Refs gebumpt wird
 * (create/updateAssignees/addParticipants/patch/createComment/exit), erfolgt der Bump ueber den
 * zentralen Helfer {@link #bumpSorted(long, Collection)} in DETERMINISTISCH SORTIERTER
 * Ref-Reihenfolge ({@code UUID.compareTo}), damit parallele Mutationen mit ueberlappender
 * Teilnehmermenge die {@code TASK_INBOX_GENERATION}-Zeilen stets in gleicher Reihenfolge sperren
 * (keine ueberkreuzte Lock-Order). Die EINE Vergleichsordnung ist hier zentralisiert, damit die
 * Aufrufer nicht divergieren koennen.
 *
 * <p><b>Einheitliche Save-dann-Bump-Ordnung (Codex #6):</b> Jeder Aufrufer, der die TASK-Zeile
 * mutiert (LAST_ACTIVITY_AT/VERSION via {@code taskRepository.save}), persistiert die TASK-Mutation
 * ZUERST und ruft {@link #bumpSorted(long, Collection)} erst DANACH (in derselben Tx) auf. Damit
 * wird ueberall zuerst die TASK-Zeile und erst dann die {@code TASK_INBOX_GENERATION}-Zeilen
 * gesperrt - keine invertierte TASK-vs-Generation-Lock-Ordnung zwischen Pfaden. Pfade ohne
 * TASK-save ({@code addParticipants}, {@code MarkerService}) koennen diese Inversion nicht ausloesen;
 * sie nutzen {@code bumpSorted}/{@code bump} dennoch fuer eine konsistente Generation-Lock-Ordnung.
 *
 * <p><b>Bekannte Luecke / Folge-Slice (Codex #6, bewusst NICHT in diesem Slice gefixt):</b> Der
 * Bump deckt nur die AKTIVEN Teilnehmer ({@code findActiveUserRefsByTaskId}, UNSUBSCRIBED_AT IS
 * NULL). Aendert eine Aktivitaets-Mutation (patch/createComment) das {@code LAST_ACTIVITY_AT} einer
 * Aufgabe, die ein bereits AUSGETRETENER Benutzer in seinem ARCHIV sieht
 * ({@code findArchiveLeftPage}/{@code findArchiveAllPage} via {@code UNSUBSCRIBED_AT}/{@code
 * EXIT_KIND}), wird dessen Archiv-Cursor NICHT invalidiert (ausgetretene Refs fehlen in der
 * aktiven Ref-Query) - sein alter Archiv-Cursor kann stale 200 (Skip/Dupe) statt 410 liefern. Ein
 * sauberer Fix muesste zusaetzlich die je-Teilnehmer-Union (ausgetretene UserStates + Participants
 * mit {@code EXIT_KIND}) fuer den Archiv-Bump beruecksichtigen; das vergroessert jedoch die
 * Lock-Flaeche jeder Aktivitaets-Mutation (gegenlaeufig zur Deadlock-Robustheit oben) und fuehrt
 * einen neuen Membership-Invariantentyp an drei Hot-Paths ein. Schadenswirkung begrenzt (nur
 * historische Archiv-Ansicht ausgetretener Nutzer, kein Daten-/Tenant-/aktiver-Inbox-Effekt; der
 * Client re-bootstrapt beim naechsten vollen Reload). Daher als eigener, gegateter Folge-Slice mit
 * eigenem IT zu fuehren, nicht erzwungen in diesen Slice.
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
   * Bumpt die Generation EINER Ref-Menge in DETERMINISTISCH SORTIERTER Reihenfolge
   * ({@code UUID.compareTo}). Zentralisiert die EINE Lock-Ordnung fuer alle Multi-Ref-Bump-Pfade
   * (create/updateAssignees/addParticipants/patch/createComment/exit), sodass parallele
   * Mutationen mit ueberlappender Teilnehmermenge die {@code TASK_INBOX_GENERATION}-Zeilen stets in
   * gleicher Reihenfolge sperren (keine ueberkreuzte Lock-Order -&gt; kein Deadlock).
   *
   * <p>Aendert NICHT, WELCHE Refs gebumpt werden - nur die Reihenfolge. Jeder einzelne Bump laueft
   * ueber {@link #bump(long, UUID)} (inkl. race-sicherer Erstanlage + Ein-Zeilen-Invariante).
   *
   * @param tenantId interner Mandanten-Schluessel (fuer die Anlage der Erstzeile)
   * @param refs     die zu bumpenden Benutzer-UUIDs (beliebige Reihenfolge; intern sortiert).
   *                 {@code null}-Elemente sind nicht zulaessig.
   */
  public void bumpSorted(final long tenantId, final Collection<UUID> refs) {
    if (refs == null || refs.isEmpty()) {
      return;
    }
    refs.stream().sorted().forEach(ref -> bump(tenantId, ref));
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
