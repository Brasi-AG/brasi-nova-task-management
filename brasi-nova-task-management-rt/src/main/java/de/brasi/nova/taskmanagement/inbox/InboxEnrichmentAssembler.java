package de.brasi.nova.taskmanagement.inbox;

import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.repository.HandoverTargetRow;
import de.brasi.nova.taskmanagement.repository.TaskTagUuidRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Rein funktionaler Assembler der Inbox-Read-Anreicherung (BE-1): gruppiert die drei
 * Batch-Row-Mengen ({@link TaskParticipant}, {@link TaskTagUuidRow}, {@link TaskUserState}) der
 * FIXEN Seite je Aufgabe und leitet pro Aufgabe das {@link TaskEnrichment} ab. KEINE DB-Zugriffe,
 * keine KSF-Abhaengigkeiten; der {@code InboxSnapshotService} ruft die Batch-Querys auf und
 * uebergibt die Ergebnisse.
 *
 * <p>Konsistenz zur Keyset-/Archiv-Mechanik (SPEC O-1/O-2):
 *
 * <ul>
 *   <li>Anzeigelisten (Ersteller/Assignees/Teilnehmer) sind PERSON-only ({@code PARTICIPANT_KIND}),
 *       Team-Aufloesung folgt mit BE-T.
 *   <li>Der ACTOR-bezogene {@code exitKind} wird asOf-gebunden EXAKT unter den Zeitbedingungen des
 *       {@code findArchiveLeftPage}-Praedikats ermittelt (ANGELEGT_AM &le; asOf; EXIT_AT null ODER
 *       &le; asOf; UNSUBSCRIBED_AT &le; asOf) und ist NICHT auf PERSON eingeschraenkt. Praezedenz:
 *       HANDOVER vor UNSUBSCRIBED; sonst {@code null}.
 * </ul>
 */
@Component
public class InboxEnrichmentAssembler {

  /** Austrittsart "Uebergabe" in der Wire-Form ({@code ExitModel.kind}). */
  public static final String EXIT_HANDOVER = "handover";

  /** Austrittsart "Abgemeldet" in der Wire-Form ({@code ExitModel.kind}). */
  public static final String EXIT_UNSUBSCRIBED = "unsubscribed";

  private static final Comparator<UUID> BY_STRING = Comparator.comparing(UUID::toString);

  /**
   * Baut je uebergebener Aufgaben-ID genau einen {@link TaskEnrichment}-Eintrag (auch leer, wenn
   * keine Rows vorliegen). Reihenfolge der Ergebnis-Map = Reihenfolge von {@code taskIds}.
   *
   * @param taskIds interne IDs der Seiten-Aufgaben (Reihenfolge wird beibehalten)
   * @param participantRows alle aktiven Teilnehmer-Rows der Seite (inkl. Austritts-Rows)
   * @param tagRows {@code (taskId, tagUuid)}-Projektionen der Seite (inkl. inaktiver Tags)
   * @param actorStateRows aktive {@link TaskUserState}-Rows des Anfragers (max. eine je Aufgabe)
   * @param handoverRows {@code (taskId, toRef)}-Zeilen des sichtbaren Austritts-Handover-
   *     Ereignisses des Anfragers je Aufgabe (BE-7, aus {@code findVisibleHandoverTargets}; bereits
   *     actor-/asOf-gescoped). {@code toRef} kann {@code null} sein (Handover ohne Ziele). KEIN
   *     DB-Zugriff hier; der Service laedt sie vor.
   * @param userRef UUID des Anfragers (Actor)
   * @param asOf Wasserzeichen des Fensters (NICHT {@code now()})
   * @return Map taskId -&gt; Anreicherung (vollstaendig fuer alle {@code taskIds})
   */
  public Map<Long, TaskEnrichment> assemble(
      final List<Long> taskIds,
      final List<TaskParticipant> participantRows,
      final List<TaskTagUuidRow> tagRows,
      final List<TaskUserState> actorStateRows,
      final List<HandoverTargetRow> handoverRows,
      final UUID userRef,
      final LocalDateTime asOf) {

    final Map<Long, List<TaskParticipant>> participantsByTask =
        participantRows.stream().collect(Collectors.groupingBy(TaskParticipant::getTaskId));
    final Map<Long, List<UUID>> tagsByTask =
        tagRows.stream()
            .collect(
                Collectors.groupingBy(
                    TaskTagUuidRow::taskId,
                    Collectors.mapping(TaskTagUuidRow::tagUuid, Collectors.toList())));
    // Pro (Aufgabe, Anfrager) existiert hoechstens eine aktive Row (UNIQUE TASK_ID + USER_REF).
    final Map<Long, TaskUserState> actorStateByTask =
        actorStateRows.stream()
            .collect(Collectors.toMap(TaskUserState::getTaskId, s -> s, (a, b) -> a));
    // BE-7: Uebergabeziele je Aufgabe aus den vorgeladenen Handover-Rows (DB-frei). Null-toRef
    // (Selbst-Handover ohne Ziele) traegt KEINE ID bei -> die Liste bleibt leer, ohne die Aufgabe
    // aus der Map zu verlieren.
    final Map<Long, List<UUID>> handoverTargetsByTask = new LinkedHashMap<>();
    for (final HandoverTargetRow row : handoverRows) {
      final List<UUID> targets =
          handoverTargetsByTask.computeIfAbsent(row.taskId(), k -> new ArrayList<>());
      if (row.toRef() != null) {
        targets.add(row.toRef());
      }
    }

    final Map<Long, TaskEnrichment> result = new LinkedHashMap<>();
    for (final Long taskId : taskIds) {
      final List<TaskParticipant> ps = participantsByTask.getOrDefault(taskId, List.of());
      final TaskUserState state = actorStateByTask.get(taskId);

      final UUID createdById =
          ps.stream()
              .filter(p -> p.isCreatorFlag() && p.getParticipantKind() == ParticipantKind.PERSON)
              .map(TaskParticipant::getParticipantRef)
              .findFirst()
              .orElse(null);

      final List<UUID> assigneeIds =
          ps.stream()
              .filter(
                  p ->
                      p.getParticipantKind() == ParticipantKind.PERSON
                          && p.isAssigneeFlag()
                          && p.getExitKind() == null)
              .map(TaskParticipant::getParticipantRef)
              .distinct()
              .sorted(BY_STRING)
              .toList();
      final Set<UUID> assigneeSet = new HashSet<>(assigneeIds);

      final List<UUID> participantIds =
          ps.stream()
              .filter(
                  p ->
                      p.getParticipantKind() == ParticipantKind.PERSON
                          && p.isParticipantFlag()
                          && p.getExitKind() == null)
              .map(TaskParticipant::getParticipantRef)
              .filter(ref -> !assigneeSet.contains(ref) && !ref.equals(createdById))
              .distinct()
              .sorted(BY_STRING)
              .toList();

      final List<UUID> tagIds =
          tagsByTask.getOrDefault(taskId, List.of()).stream().distinct().sorted(BY_STRING).toList();

      final boolean pinned = state != null && state.isPinned();
      final boolean flagged = state != null && state.isFlagged();
      final boolean muted = state != null && state.isMuted();
      final ActorExit actorExit = deriveExit(ps, state, userRef, asOf);
      final String exitKind = actorExit == null ? null : actorExit.kind();
      final LocalDateTime exitedAt = actorExit == null ? null : actorExit.exitedAt();

      // BE-7: Uebergabeziele NUR bei sichtbarem Handover-Austritt. Die Rows sind vom Service
      // bereits
      // actor-/asOf-/SHOULD_STAY=false-gescoped; die Praezedenz (HANDOVER vor UNSUBSCRIBED) wird
      // hier
      // ueber exitKind durchgesetzt -> bei "unsubscribed"/null bleiben die Ziele leer (kein Leak).
      final List<UUID> handedOverToIds;
      if (EXIT_HANDOVER.equals(exitKind)) {
        final List<UUID> targets = handoverTargetsByTask.getOrDefault(taskId, List.of());
        handedOverToIds = targets.stream().distinct().sorted(BY_STRING).toList();
      } else {
        handedOverToIds = List.of();
      }

      result.put(
          taskId,
          new TaskEnrichment(
              createdById,
              assigneeIds,
              participantIds,
              tagIds,
              pinned,
              flagged,
              muted,
              exitKind,
              handedOverToIds,
              exitedAt));
    }
    return result;
  }

  /**
   * Leitet den asOf-gebundenen Austritt des Anfragers samt Austrittszeitpunkt ab (deckungsgleich
   * mit dem {@code findArchiveLeftPage}-Praedikat). HANDOVER hat Vorrang vor UNSUBSCRIBED.
   *
   * <p>Austrittszeitpunkt (Intake #150): {@code EXIT_AT} der Teilnehmerzeile, die den Austritt
   * belegt. Fehlt er dort (Altdaten; {@code isActorExit} laesst {@code EXIT_AT = null} zu) oder
   * belegt nur der {@link TaskUserState} die Abmeldung ({@code UNSUBSCRIBED_AT}, ohne
   * Austritts-Teilnehmerzeile), gilt {@code UNSUBSCRIBED_AT} des States. Beide Werte setzt jeder
   * Austrittspfad gemeinsam ({@code AssigneeService}); der Rueckgriff greift nur bei Altdaten.
   * Fehlen beide, bleibt der Zeitpunkt {@code null}, nie erfunden.
   *
   * @return Austritt oder {@code null} (aktives Mitglied bzw. kein Austritt bis {@code asOf})
   */
  private ActorExit deriveExit(
      final List<TaskParticipant> ps,
      final TaskUserState state,
      final UUID userRef,
      final LocalDateTime asOf) {

    final LocalDateTime stateUnsubscribedAt = state == null ? null : state.getUnsubscribedAt();

    final TaskParticipant handover = findActorExit(ps, userRef, ExitKind.HANDOVER, asOf);
    if (handover != null) {
      return new ActorExit(EXIT_HANDOVER, firstNonNull(handover.getExitAt(), stateUnsubscribedAt));
    }

    final TaskParticipant unsubscribed = findActorExit(ps, userRef, ExitKind.UNSUBSCRIBED, asOf);
    if (unsubscribed != null) {
      return new ActorExit(
          EXIT_UNSUBSCRIBED, firstNonNull(unsubscribed.getExitAt(), stateUnsubscribedAt));
    }
    final boolean unsubscribedByState =
        state != null
            && notAfter(state.getCreatedAt(), asOf)
            && stateUnsubscribedAt != null
            && notAfter(stateUnsubscribedAt, asOf);
    if (unsubscribedByState) {
      return new ActorExit(EXIT_UNSUBSCRIBED, stateUnsubscribedAt);
    }
    return null;
  }

  /**
   * Erste Austritts-Teilnehmerzeile des Anfragers mit der gegebenen Art ({@link #isActorExit}) oder
   * {@code null}. Je (Aufgabe, Person) gibt es hoechstens eine Zeile (Unique-Key aus V3).
   */
  private TaskParticipant findActorExit(
      final List<TaskParticipant> ps,
      final UUID userRef,
      final ExitKind kind,
      final LocalDateTime asOf) {
    return ps.stream().filter(p -> isActorExit(p, userRef, kind, asOf)).findFirst().orElse(null);
  }

  /** Liefert {@code first}, falls gesetzt, sonst {@code second} (beide duerfen null sein). */
  private static LocalDateTime firstNonNull(final LocalDateTime first, final LocalDateTime second) {
    return first != null ? first : second;
  }

  /**
   * Abgeleiteter Austritt des Anfragers.
   *
   * @param kind Austrittsart in der Wire-Form ({@link #EXIT_HANDOVER} oder {@link
   *     #EXIT_UNSUBSCRIBED})
   * @param exitedAt Austrittszeitpunkt (lokale Wanduhr der Persistenz-Zone) oder {@code null}
   */
  private record ActorExit(String kind, LocalDateTime exitedAt) {}

  /**
   * Actor-Austritts-Row mit bestimmter Art, asOf-gebunden: {@code PARTICIPANT_REF = userRef},
   * {@code EXIT_KIND = kind}, beigetreten vor dem Wasserzeichen ({@code ANGELEGT_AM <= asOf}) und
   * ausgetreten spaetestens zum Wasserzeichen ({@code EXIT_AT null ODER <= asOf}). Nicht auf {@code
   * PARTICIPANT_KIND} eingeschraenkt (deckungsgleich mit dem left-SQL).
   */
  private boolean isActorExit(
      final TaskParticipant p, final UUID userRef, final ExitKind kind, final LocalDateTime asOf) {
    return userRef.equals(p.getParticipantRef())
        && p.getExitKind() == kind
        && notAfter(p.getCreatedAt(), asOf)
        && (p.getExitAt() == null || notAfter(p.getExitAt(), asOf));
  }

  /** {@code true}, wenn {@code t} nicht {@code null} und nicht nach {@code asOf} liegt. */
  private static boolean notAfter(final LocalDateTime t, final LocalDateTime asOf) {
    return t != null && !t.isAfter(asOf);
  }
}
