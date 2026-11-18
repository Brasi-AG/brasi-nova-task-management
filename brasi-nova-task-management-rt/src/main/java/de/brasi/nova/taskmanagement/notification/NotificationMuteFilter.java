package de.brasi.nova.taskmanagement.notification;

import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Zentrale Stummschalt-Regel fuer Benachrichtigungen (Intake #158).
 *
 * <p>Hat eine Person eine Aufgabe stummgeschaltet (persoenlicher Marker {@code IS_MUTED} im aktiven
 * {@link TaskUserState}, gesetzt ueber {@code MarkerService#setMuted}), erhaelt sie fuer diese
 * Aufgabe keine Benachrichtigungen mehr - ausser den Typen in {@link #EXEMPT_TYPES}:
 *
 * <ul>
 *   <li>{@code MENTION} und {@code REPLY}: direkte @-Erwaehnung und Antwort auf einen eigenen
 *       Kommentar (laut Issue);
 *   <li>{@code ASSIGN} und {@code HANDOVER}: direkte persoenliche Zuweisung bzw. Uebergabe;
 *   <li>{@code PARTICIPANT}: direkte persoenliche Aufnahme als Beteiligte (Intake #147),
 *       gleichrangig mit ASSIGN und HANDOVER. Das Hinzufuegen hebt einen vorhandenen Mute NICHT
 *       auf;
 *   <li>{@code REMOVED}: direkte persoenliche Mitteilung, dass ein Ersteller oder aktiver Assignee
 *       die Person aus der Aufgabe entfernt hat (Intake #145), Gegenstueck zu PARTICIPANT. Sie
 *       betrifft die eigene Mitgliedschaft und nicht die Aktivitaet der Aufgabe, die der Mute
 *       ausblenden soll; die Person ist danach ausgetreten, ohne die Mitteilung erfuehre sie von
 *       ihrem Ausschluss nur durch das Fehlen der Aufgabe in der Inbox. Das Entfernen aendert den
 *       Mute-Marker nicht.
 * </ul>
 *
 * <p>Unterdrueckt werden damit {@code COMMENT}, {@code DONE}, {@code CANCELLED}, {@code CHANGE},
 * {@code DUE} und {@code SYSTEM} sowie jeder kuenftige Typ, der nicht ausdruecklich ausgenommen
 * wird (die Regel ist als Ausnahmeliste formuliert, weil das Issue "saemtliche Benachrichtigungen
 * ausser ..." verlangt).
 *
 * <p>Fehlt der aktive {@link TaskUserState}, gilt die Person als nicht stumm. Das Push-Signal
 * {@code snapshot-invalidated} ({@code InboxSignalService}) ist keine Benachrichtigung und wird von
 * dieser Regel nicht beruehrt.
 *
 * <p><b>Buendelung:</b> Die Ausloesepfade (Kommentar, Statuswechsel DONE/CANCELLED, Faelligkeit)
 * lesen die Mute-Zustaende in GENAU EINER Abfrage je Ereignis ({@link
 * TaskUserStateQueryRepository#findMutedByTaskId}), und zwar im Lese-Block VOR dem versionierten
 * TASK-save. Die Abfrage fragt nach den stummen Personen der AUFGABE (konstante Parameterzahl)
 * statt nach den Kandidaten ({@code IN}-Liste): eine Aufgabe kann mehr aktive Beteiligte haben, als
 * das SQL-Server-Limit von 2100 Parametern zulaesst. Die Schnittmenge mit den Kandidaten bildet
 * dieser Filter in Java. Bewusst nicht zentral in {@link NotificationService#emit}: dort liefe die
 * Lesung je Empfaenger (N+1) und NACH dem TASK-save; ein {@code TASK_USER_STATE}-Read unter dem
 * TASK-X-Lock invertierte die Lock-Reihenfolge gegen {@code AssigneeService#exit} (UserState vor
 * TASK) und koennte auf MSSQL einen Deadlock ausloesen. Pfade, die nur ausgenommene Typen
 * emittieren ({@code ASSIGN}/{@code HANDOVER}/{@code PARTICIPANT}/{@code REMOVED} in {@code
 * TaskCommandService#create} und {@code AssigneeService}), brauchen den Filter nicht; er waere dort
 * ein No-Op ohne Abfrage.
 *
 * <p><b>Mute und (Wieder-)Zuweisung:</b> Eine NEUE Zuweisung hebt den Mute der Aufgabe fuer die
 * zugewiesene Person auf: {@code UserStateService#reactivateAsAssignee} setzt {@code IS_MUTED} auf
 * {@code false} (PUT /assignees fuer neu hinzukommende Personen und Ziele einer Uebergabe per
 * HANDOVER, die vorher kein aktiver Assignee waren). Die {@code ASSIGN}- bzw. {@code
 * HANDOVER}-Benachrichtigung kommt ohnehin an, weil beide Typen ausgenommen sind; spaetere
 * Benachrichtigungen (z.B. {@code COMMENT}) erreichen die Person danach wieder. Unveraendert
 * bleibende Assignees eines PUT, ein bereits aktiver Assignee als Uebergabeziel, reine Teilnehmer
 * ({@code UserStateService#reactivate} bei {@code addParticipants}) und die Marker-Pfade lassen den
 * Mute unveraendert; ein neu angelegter State startet nicht stumm. Auch die {@code
 * PARTICIPANT}-Benachrichtigung beim Hinzufuegen als Beteiligte kommt trotz Mute an, hebt ihn aber
 * nicht auf.
 */
@Component
public class NotificationMuteFilter {

  private static final Logger log = LoggerFactory.getLogger(NotificationMuteFilter.class);

  /** Typen, die eine stummgeschaltete Person trotzdem erhaelt; alle anderen werden unterdrueckt. */
  static final Set<String> EXEMPT_TYPES =
      Set.of(
          NotificationTypeCodes.MENTION,
          NotificationTypeCodes.REPLY,
          NotificationTypeCodes.ASSIGN,
          NotificationTypeCodes.HANDOVER,
          NotificationTypeCodes.PARTICIPANT,
          NotificationTypeCodes.REMOVED);

  private final TaskUserStateQueryRepository userStateRepository;

  /** Konstruktor-Injektion des {@link TaskUserStateQueryRepository}. */
  public NotificationMuteFilter(final TaskUserStateQueryRepository userStateRepository) {
    this.userStateRepository = userStateRepository;
  }

  /**
   * Prueft, ob ein Benachrichtigungstyp fuer stummgeschaltete Empfaenger unterdrueckt wird.
   *
   * @param typeCode Typ-CODE aus {@link NotificationTypeCodes}
   * @return {@code true}, wenn der Typ stummschaltbar ist
   */
  public static boolean isMutable(final String typeCode) {
    return !EXEMPT_TYPES.contains(typeCode);
  }

  /**
   * Liefert die stummgeschalteten Personen unter den Kandidaten, fuer die eine Benachrichtigung des
   * Typs {@code typeCode} zu dieser Aufgabe unterdrueckt werden muss.
   *
   * <p>Genau eine Abfrage ohne Kandidatenliste, und nur wenn der Typ stummschaltbar ist, eine
   * Aufgabe vorliegt und die Kandidatenmenge nicht leer ist; sonst keine Abfrage und eine leere
   * Menge. Laeuft im Mandanten-Kontext des Aufrufers.
   *
   * @param taskId interne Aufgaben-ID (null = keine Aufgabe, nichts stumm)
   * @param typeCode Typ-CODE der geplanten Benachrichtigung
   * @param candidates Empfaenger-Kandidaten
   * @return die stummen Kandidaten (Teilmenge von {@code candidates})
   */
  public Set<UUID> findMuted(
      final Long taskId, final String typeCode, final Collection<UUID> candidates) {
    if (taskId == null || candidates.isEmpty() || !isMutable(typeCode)) {
      return Set.of();
    }
    final Set<UUID> distinctCandidates = new LinkedHashSet<>(candidates);
    final Set<UUID> muted =
        userStateRepository.findMutedByTaskId(taskId).stream()
            .map(TaskUserState::getUserRef)
            .filter(distinctCandidates::contains)
            .collect(Collectors.toUnmodifiableSet());
    if (!muted.isEmpty()) {
      log.debug(
          "NotificationMuteFilter: {} von {} Empfaengern haben Aufgabe {} stummgeschaltet -"
              + " {}-Benachrichtigung unterdrueckt.",
          muted.size(),
          distinctCandidates.size(),
          taskId,
          typeCode);
    }
    return muted;
  }

  /**
   * Entfernt die stummgeschalteten Personen aus der Empfaengerliste (Reihenfolge bleibt erhalten).
   *
   * @param taskId interne Aufgaben-ID
   * @param typeCode Typ-CODE der geplanten Benachrichtigung
   * @param candidates Empfaenger-Kandidaten
   * @return die Kandidaten ohne die stummen Personen
   */
  public List<UUID> withoutMuted(
      final Long taskId, final String typeCode, final Collection<UUID> candidates) {
    final Set<UUID> muted = findMuted(taskId, typeCode, candidates);
    if (muted.isEmpty()) {
      return List.copyOf(candidates);
    }
    return candidates.stream().filter(ref -> !muted.contains(ref)).toList();
  }
}
