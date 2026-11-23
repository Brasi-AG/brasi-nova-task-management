package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.division.DivisionMentionResolver;
import de.brasi.nova.taskmanagement.participant.AssigneeService;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ermittelt die Empfaenger einer Erwaehnung (Intake #156, #190) fuer Kommentar und Beschreibung.
 *
 * <p>Aus {@code CommentService#createComment} herausgeloest (Intake #190, Task 10), damit der PATCH
 * der Beschreibung ({@code TaskCommandService#patch}) denselben Weg nimmt. Ablauf in {@link
 * #collect}:
 *
 * <ol>
 *   <li>Erwaehnte Abteilungen ueber den {@link DivisionMentionResolver} auf die Personen der
 *       lokalen Projektion aufloesen (reiner Read; 400 bei unbekannter oder inaktiver Abteilung
 *       oder zu vielen Personen, dann ist nichts geschrieben). Die Projektion wird nur gelesen.
 *   <li>Mit den erwaehnten Personen vereinigen und deduplizieren, dann mandanten-scoped auf aktive
 *       Benutzer filtern ({@code findActiveByUuids}); nicht aufloesbare UUIDs fallen still weg.
 *   <li>Akteur (kein Selbst-Ping) und den ausgenommenen Empfaenger (beim Kommentar der Empfaenger
 *       der REPLY) ueberspringen.
 *   <li>WRITE-Mitglieder werden Ziel der MENTION. Nicht-Mitglieder werden nur mit dem Recht von
 *       {@code POST /participants} ({@link AssigneeService#mayAddParticipants()}) ueber den
 *       gemeinsamen Kern {@link AssigneeService#addAsParticipants} als reine Beteiligte aufgenommen
 *       (inklusive Verlauf "per Erwaehnung"); sonst still verworfen.
 * </ol>
 *
 * <p>Die Phasen sind einzeln aufrufbar, weil beide Aufrufer ihre Reads vor die erste Schreibung
 * legen muessen: {@link #resolve} (nur Reads) liefert die opake {@link Candidates}, {@link #admit}
 * (Aufnahme) nimmt sie entgegen. {@link Candidates} entsteht ausschliesslich in {@link #resolve}
 * (privater Konstruktor); niemand kann der Aufnahme eine selbst gebaute Kandidatenmenge reichen und
 * damit die Pruefung auf aktive Benutzer des eigenen Mandanten umgehen. {@link #collect} fasst
 * beide Phasen zusammen und bleibt fuer Aufrufer, die dazwischen nichts zu lesen haben (die
 * Produktion nutzt derzeit nur die Einzelphasen).
 *
 * <p>Laeuft in der Transaktion des Aufrufers ({@link Propagation#MANDATORY}); die Aufnahme liegt
 * dort vor dem TASK-save: sie schreibt Teilnehmer- und State-Zeilen (Mitgliedschaft vor Aufgabe,
 * Karte {@code participants-actions}). Benachrichtigung, Generation-Bump und Signal bleiben beim
 * Aufrufer.
 */
@Service
public class MentionRecipientService {

  private static final Logger log = LoggerFactory.getLogger(MentionRecipientService.class);

  private final UserReadRepository userReadRepository;
  private final TaskMembershipGuard membershipGuard;
  private final AssigneeService assigneeService;
  private final DivisionMentionResolver divisionMentionResolver;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public MentionRecipientService(
      final UserReadRepository userReadRepository,
      final TaskMembershipGuard membershipGuard,
      final AssigneeService assigneeService,
      final DivisionMentionResolver divisionMentionResolver) {
    this.userReadRepository = userReadRepository;
    this.membershipGuard = membershipGuard;
    this.assigneeService = assigneeService;
    this.divisionMentionResolver = divisionMentionResolver;
  }

  /**
   * Ermittelt die MENTION-Ziele und nimmt erwaehnte Nicht-Mitglieder (mit Recht) als Beteiligte
   * auf: {@link #resolve} und danach {@link #admit} in einem Schritt, ohne eigene Reads dazwischen.
   *
   * @param taskId interne Aufgaben-ID
   * @param tenantId interner Mandanten-Schluessel
   * @param actorRef handelnde Person (wird nie Ziel)
   * @param excludedRecipient Person, die keine MENTION bekommt (REPLY-Empfaenger), oder {@code
   *     null}
   * @param personRefs erwaehnte Personen (Duplikate erlaubt, nie {@code null})
   * @param divisionRefs erwaehnte Abteilungen (Duplikate erlaubt, nie {@code null})
   * @return Ziele der MENTION und die per Erwaehnung Aufgenommenen
   * @throws EntityValidationException wenn eine erwaehnte Abteilung unbekannt oder nicht aktiv ist
   *     oder die Abteilungen zu viele Personen umfassen (HTTP 400, vor jeder Schreibung)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public MentionRecipients collect(
      final Long taskId,
      final long tenantId,
      final UUID actorRef,
      final UUID excludedRecipient,
      final List<UUID> personRefs,
      final List<UUID> divisionRefs) {
    return admit(resolve(taskId, tenantId, actorRef, excludedRecipient, personRefs, divisionRefs));
  }

  /**
   * Lesephase: loest die Erwaehnten auf und teilt sie in WRITE-Mitglieder und Nicht-Mitglieder,
   * ohne etwas zu schreiben.
   *
   * <p>Getrennt von {@link #admit}, damit der Aufrufer die Reads vor die erste Schreibung legen
   * kann: {@code CommentService#createComment} liest zwischen beiden Phasen die Assignees fuer
   * COMMENT, {@code TaskCommandService#patch} ruft {@code resolve} noch vor der ersten Mutation
   * (Verlauf, Tags) und {@link #admit} erst danach, vor dem TASK-save. Ein 400 aus der
   * Abteilungsaufloesung kommt damit in beiden Pfaden vor jeder Schreibung.
   *
   * @param taskId interne Aufgaben-ID
   * @param tenantId interner Mandanten-Schluessel
   * @param actorRef handelnde Person (wird nie Ziel)
   * @param excludedRecipient Person, die keine MENTION bekommt (REPLY-Empfaenger), oder {@code
   *     null}
   * @param personRefs erwaehnte Personen (Duplikate erlaubt, nie {@code null})
   * @param divisionRefs erwaehnte Abteilungen (Duplikate erlaubt, nie {@code null})
   * @return die aufgeteilten Erwaehnten, nur fuer {@link #admit} bestimmt
   * @throws EntityValidationException wenn eine erwaehnte Abteilung unbekannt oder nicht aktiv ist
   *     oder die Abteilungen zu viele Personen umfassen (HTTP 400)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Candidates resolve(
      final Long taskId,
      final long tenantId,
      final UUID actorRef,
      final UUID excludedRecipient,
      final List<UUID> personRefs,
      final List<UUID> divisionRefs) {
    // Abteilungen VOR der Deduplizierung mit den Personen vereinigen (Intake #190); ab hier laufen
    // die Personen der Abteilungen denselben Weg wie die erwaehnten Personen.
    final Set<UUID> dedupedMentions = new LinkedHashSet<>(personRefs);
    dedupedMentions.addAll(divisionMentionResolver.resolve(divisionRefs));
    final List<UUID> mentionRefs;
    if (dedupedMentions.isEmpty()) {
      mentionRefs = List.of();
    } else {
      mentionRefs =
          userReadRepository.findActiveByUuids(dedupedMentions).stream()
              .map(User::getUuid)
              .toList();
    }

    final Set<UUID> members = new LinkedHashSet<>();
    final Set<UUID> nonMembers = new LinkedHashSet<>();
    for (final UUID mentionRef : mentionRefs) {
      if (mentionRef.equals(actorRef) || mentionRef.equals(excludedRecipient)) {
        continue;
      }
      // MENTION geht nur an WRITE-Mitglieder; Ausgetretene und Nicht-Teilnehmer sind Kandidaten
      // fuer die Aufnahme als Beteiligte (Intake #156).
      if (membershipGuard.isWriteMember(taskId, mentionRef, tenantId)) {
        members.add(mentionRef);
      } else {
        nonMembers.add(mentionRef);
      }
    }
    return new Candidates(taskId, tenantId, members, nonMembers);
  }

  /**
   * Schreibphase: nimmt die Nicht-Mitglieder aus {@link #resolve} (mit Recht) als Beteiligte auf
   * und liefert die Ziele der MENTION. Laeuft vor dem TASK-save des Aufrufers und in derselben
   * Transaktion wie {@link #resolve}.
   *
   * @param candidates Ergebnis von {@link #resolve} (nur dort erzeugbar)
   * @return Ziele der MENTION (Mitglieder und Aufgenommene) und die Aufgenommenen
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public MentionRecipients admit(final Candidates candidates) {
    final Set<UUID> admitted =
        admitNonMembers(candidates.taskId, candidates.tenantId, candidates.nonMembers);
    final Set<UUID> notifyTargets = new LinkedHashSet<>(candidates.members);
    notifyTargets.addAll(admitted);
    return new MentionRecipients(notifyTargets, admitted);
  }

  /**
   * Zwischenergebnis von {@link #resolve}: die Erwaehnten, getrennt nach WRITE-Mitgliedschaft.
   *
   * <p>Opak und nur ueber {@link #resolve} erhaeltlich: der Konstruktor ist privat und es gibt
   * keine Zugriffsmethoden. So kann kein Aufrufer die Pruefung auf aktive Benutzer des eigenen
   * Mandanten und den Ausschluss des Akteurs umgehen, die {@code addAsParticipants} vom Aufrufer
   * verlangt (sonst droht eine mandantenfremde Aufnahme).
   */
  public static final class Candidates {

    /** Interne Aufgaben-ID. */
    private final Long taskId;

    /** Interner Mandanten-Schluessel. */
    private final long tenantId;

    /** Erwaehnte WRITE-Mitglieder (Ziele der MENTION). */
    private final Set<UUID> members;

    /** Erwaehnte aktive Mandanten-Nutzer ohne WRITE-Mitgliedschaft. */
    private final Set<UUID> nonMembers;

    /**
     * Nur aus {@link MentionRecipientService#resolve}: unveraenderliche Kopien, die Reihenfolge
     * bleibt erhalten.
     */
    private Candidates(
        final Long taskId,
        final long tenantId,
        final Set<UUID> members,
        final Set<UUID> nonMembers) {
      this.taskId = taskId;
      this.tenantId = tenantId;
      this.members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
      this.nonMembers = Collections.unmodifiableSet(new LinkedHashSet<>(nonMembers));
    }
  }

  /**
   * Nimmt erwaehnte Nicht-Mitglieder als Beteiligte auf (Intake #156), sofern die handelnde Person
   * dazu berechtigt ist.
   *
   * <p>Ohne das Recht wird niemand aufgenommen: die Erwaehnung von Nicht-Mitgliedern entfaellt
   * still. Hat ein gleichzeitiger Aufruf eine Person schon aufgenommen (der Kern meldet sie dann
   * nicht), ist sie inzwischen WRITE-Mitglied und wird trotzdem gefuehrt.
   *
   * @param taskId interne Aufgaben-ID
   * @param tenantId interner Mandanten-Schluessel
   * @param nonMembers erwaehnte aktive Mandanten-Nutzer, die (noch) kein WRITE-Mitglied sind
   * @return die Personen, die danach WRITE-Mitglied sind
   */
  private Set<UUID> admitNonMembers(
      final Long taskId, final long tenantId, final Set<UUID> nonMembers) {
    if (nonMembers.isEmpty() || !assigneeService.mayAddParticipants()) {
      return Set.of();
    }
    final Set<UUID> members =
        new LinkedHashSet<>(assigneeService.addAsParticipants(taskId, tenantId, nonMembers));
    for (final UUID ref : nonMembers) {
      if (!members.contains(ref) && membershipGuard.isWriteMember(taskId, ref, tenantId)) {
        members.add(ref);
      }
    }
    log.info(
        "MentionRecipientService.admit: {} erwaehnte Person(en) in Aufgabe (id={}) als Beteiligte"
            + " aufgenommen, Mandant {}.",
        members.size(),
        taskId,
        tenantId);
    return members;
  }
}
