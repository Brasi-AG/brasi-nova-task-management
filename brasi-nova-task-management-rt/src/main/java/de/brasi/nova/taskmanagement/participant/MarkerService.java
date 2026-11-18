package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.user.UserAccessor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaktionale Kommando-Operationen fuer persoenliche Marker (pin/flag/mute/seen) eines
 * Benutzers.
 *
 * <p>Jede Methode laedt den Actor aus dem Security-Context, prueft die Mandantenzugehoerigkeit der
 * Aufgabe (404-Masking) und stellt den {@link TaskUserState} via {@link UserStateService#ensure}
 * sicher, ohne {@code UNSUBSCRIBED_AT} zu veraendern (Inbox-Invariante).
 *
 * <p>Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}), NIEMALS aus
 * Client-Eingabedaten.
 */
@Service
public class MarkerService {

  private static final Logger log = LoggerFactory.getLogger(MarkerService.class);

  private final BaseTaskRepository taskRepository;
  private final TaskUserStateQueryRepository userStateQueryRepository;
  private final UserStateService userStateService;
  private final InboxSignalService inboxSignalService;
  private final InboxGenerationService inboxGenerationService;
  private final UserAccessor userAccessor;
  private final TaskMembershipGuard membershipGuard;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public MarkerService(
      final BaseTaskRepository taskRepository,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final UserStateService userStateService,
      final InboxSignalService inboxSignalService,
      final InboxGenerationService inboxGenerationService,
      final UserAccessor userAccessor,
      final TaskMembershipGuard membershipGuard) {
    this.taskRepository = taskRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.userStateService = userStateService;
    this.inboxSignalService = inboxSignalService;
    this.inboxGenerationService = inboxGenerationService;
    this.userAccessor = userAccessor;
    this.membershipGuard = membershipGuard;
  }

  /**
   * Setzt den Pinned-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * <p>Legt den {@link TaskUserState} bei Bedarf an (idempotent via ensure). {@code
   * UNSUBSCRIBED_AT} bleibt unveraendert (Inbox-Invariante).
   *
   * @param taskUuid externe UUID der Aufgabe
   * @param value gewuenschter Zielwert ({@code true} = angepinnt)
   */
  @Transactional
  public void setPinned(final UUID taskUuid, final boolean value) {
    applyMarker(taskUuid, state -> state.setPinned(value));
    log.debug("MarkerService.setPinned: IS_PINNED={} fuer Task {}.", value, taskUuid);
  }

  /**
   * Setzt den Flagged-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * <p>Legt den {@link TaskUserState} bei Bedarf an (idempotent via ensure). {@code
   * UNSUBSCRIBED_AT} bleibt unveraendert (Inbox-Invariante).
   *
   * @param taskUuid externe UUID der Aufgabe
   * @param value gewuenschter Zielwert ({@code true} = markiert)
   */
  @Transactional
  public void setFlagged(final UUID taskUuid, final boolean value) {
    applyMarker(taskUuid, state -> state.setFlagged(value));
    log.debug("MarkerService.setFlagged: IS_FLAGGED={} fuer Task {}.", value, taskUuid);
  }

  /**
   * Setzt den Muted-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * <p>Legt den {@link TaskUserState} bei Bedarf an (idempotent via ensure). {@code
   * UNSUBSCRIBED_AT} bleibt unveraendert (Inbox-Invariante).
   *
   * @param taskUuid externe UUID der Aufgabe
   * @param value gewuenschter Zielwert ({@code true} = stummgeschaltet)
   */
  @Transactional
  public void setMuted(final UUID taskUuid, final boolean value) {
    applyMarker(taskUuid, state -> state.setMuted(value));
    log.debug("MarkerService.setMuted: IS_MUTED={} fuer Task {}.", value, taskUuid);
  }

  /**
   * Markiert die Aufgabe fuer den aktuellen Benutzer als gesehen (Intake #150): setzt {@code
   * TASK_USER_STATE.SEEN_AT} auf jetzt. Idempotent im Sinne von PUT: jeder Aufruf setzt den
   * Zeitpunkt erneut auf jetzt, ohne weitere Wirkung.
   *
   * <p>Berechtigung exakt wie {@code GET /api/v1/tasks/{id}}: READ-Member ({@code
   * requireReadMember}, ausgetretene Mitglieder eingeschlossen, damit sie ihr Archiv-Detail als
   * gesehen markieren koennen). Unbekannte Aufgabe, fremder Mandant, deaktivierte Aufgabe und
   * Nicht-Mitglied ergeben dieselbe neutrale 404. Der Guard steht vor {@code ensure}, sodass ein
   * Nicht-Mitglied keine State-Zeile anlegt.
   *
   * <p>{@code ensure} legt den State nur an, wenn er fehlt, und aendert {@code UNSUBSCRIBED_AT} nie
   * (ein Ausgetretener bleibt ausgetreten). Keine Benachrichtigung.
   *
   * <p>Inbox-Generation: kein Bump bei vorhandenem State. {@code SEEN_AT} ist ein rein
   * persoenlicher Wert ohne Keyset- oder Mitgliedschaftswirkung: er aendert weder {@code
   * LAST_ACTIVITY_AT} noch Teilnehmer- oder Austrittsspalten und ist in keiner Inbox- oder
   * Archiv-Query Praedikat; die Seiten-Anreicherung der Inbox liefert ihn nicht aus. Ein offener
   * Cursor bleibt damit gueltig (Karte inbox-snapshot: reine Zustandsaenderung, kein Bump). Nur
   * wenn {@code ensure} den State erstmals anlegt, wird wie bei pin/flag/mute gebumpt und
   * invalidiert, weil eine neue State-Zeile Inbox-Sichtbarkeit herstellen kann.
   *
   * <p>Zeitbasis: {@code LocalDateTime.now()} wie {@code READ_AT} und wie die KSF-Audit-Zeit {@code
   * ANGELEGT_AM} der Kommentare, mit der {@code SEEN_AT} verglichen wird (lokale Wanduhr der
   * Persistenz-Zone, Lesson audit-time-zone).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @throws EntityNotFoundException bei unbekannter, fremder oder deaktivierter Aufgabe sowie fuer
   *     Nicht-Mitglieder (HTTP 404, neutral)
   */
  @Transactional
  public void markSeen(final UUID taskExternalId) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID userRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // READ-Guard wie GET /tasks/{id}, VOR ensure (kein Orphan-State fuer Nicht-Mitglieder).
    membershipGuard.requireReadMember(taskId, userRef, tenantId);

    final boolean created = userStateService.ensure(taskId, userRef, tenantId);
    final TaskUserState state =
        userStateQueryRepository.findByTaskIdAndUserRef(taskId, userRef).get(0);
    state.setSeenAt(LocalDateTime.now());
    userStateQueryRepository.save(state);
    log.debug("MarkerService.markSeen: SEEN_AT gesetzt fuer Task {}.", taskExternalId);

    if (created) {
      inboxGenerationService.bump(tenantId, userRef);
      inboxSignalService.invalidate(tenantId, List.of(userRef));
    }
  }

  /**
   * Gemeinsame Marker-Set-Logik:
   *
   * <ol>
   *   <li>Actor aus dem Security-Context laden.
   *   <li>Aufgabe tenant-scoped laden (404-Masking bei fremder/fehlender UUID).
   *   <li>{@link TaskUserState} sicherstellen via {@link UserStateService#ensure} (legt an falls
   *       nicht vorhanden; aendert UNSUBSCRIBED_AT NICHT - Inbox-Invariante).
   *   <li>Marker-Setter ausfuehren und speichern.
   *   <li>Nur bei Neuanlage eines aktiven {@link TaskUserState} (Membership-Etablierung):
   *       Inbox-Generation des Benutzers ATOMAR in dieser Tx bumpen + after-commit invalidieren
   *       (Snapshot-Rejection / Codex #6). Ein reines State-UPDATE bumpt/invalidiert NICHT.
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param markerSetter setzt den gewuenschten Marker am {@link TaskUserState}
   */
  private void applyMarker(
      final UUID taskExternalId, final java.util.function.Consumer<TaskUserState> markerSetter) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID userRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Standard-Selfservice (Slice 2, Henne-Ei): WRITE-Member-Guard VOR dem ensure-Pfad. Heute legt
    // ensure eine TASK_USER_STATE-Zeile an -> ein pin/flag/mute auf eine Fremd-Task erzeugte bisher
    // Inbox-Sichtbarkeit ohne Membership (Orphan-State). Mit Guard-vor-ensure wird fuer einen
    // Non-/Ex-Member KEINE neue state-Zeile mehr angelegt; er erhaelt neutrale 404.
    membershipGuard.requireWriteMember(taskId, userRef, tenantId);

    // UserState sicherstellen: ensure legt an (falls nicht vorhanden),
    // aendert UNSUBSCRIBED_AT NICHT (Inbox-Invariante). created=true bedeutet, dass durch
    // diesen Aufruf erstmals ein aktiver UserState entsteht -> der Task wird fuer den Benutzer
    // inbox-faehig.
    final boolean created = userStateService.ensure(taskId, userRef, tenantId);

    final List<TaskUserState> states =
        userStateQueryRepository.findByTaskIdAndUserRef(taskId, userRef);
    final TaskUserState state = states.get(0);

    markerSetter.accept(state);
    userStateQueryRepository.save(state);

    // Finding 2 / Option B / Codex #6 (dritter Membership-Fall): Entstand durch diesen Marker
    // erstmals ein aktiver UserState (created), ETABLIERT das die Inbox-Membership des Benutzers -
    // der Task erscheint ab jetzt in dessen aktiver Keyset-Query (TaskKeysetQueryRepository EXISTS
    // TASK_USER_STATE). Ein alter aktiver Cursor, dessen First-Page VOR dieser (uncommitteten)
    // Anlage entstand, lieferte sonst nach dem Commit weiter stale 200 (der neu sichtbare Task
    // fehlt), statt 410. Deshalb bei Neuanlage die Inbox-Generation des Benutzers ATOMAR in dieser
    // Tx bumpen (alte Cursor -> 410 beim naechsten Folge-Read) UND after-commit invalidieren.
    // Scope: der Benutzer selbst - nur seine aktive Liste aendert sich durch die reine
    // Membership-Etablierung (keine LAST_ACTIVITY_AT-Mutation, keine Teilnehmer-Summary-Aenderung
    // fuer Dritte). Ein reines State-UPDATE (pin/flag/mute auf bestehendem State) bumpt/invalidiert
    // NICHT - es hat keinen Keyset-/Membership-Effekt.
    if (created) {
      inboxGenerationService.bump(tenantId, userRef);
      inboxSignalService.invalidate(tenantId, List.of(userRef));
    }
  }

  /**
   * Laedt eine Aufgabe anhand ihrer externen UUID und prueft die Mandantenzugehoerigkeit.
   *
   * <p>Beide Fehlerfaelle (nicht gefunden / fremder Mandant) liefern eine identische, neutrale
   * {@link EntityNotFoundException} (HTTP 404), um keine Existenz-Information preiszugeben (arch-v2
   * §8 / Cross-Tenant-Disclosure-Schutz).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param tenantId erwarteter Mandanten-Schluessel
   * @return die gefundene Aufgabe
   * @throws EntityNotFoundException wenn nicht gefunden oder fremdem Tenant zugehoerig (HTTP 404)
   */
  private Task requireTask(final UUID taskExternalId, final long tenantId) {
    final Optional<Task> opt = taskRepository.findByUuid(taskExternalId.toString());
    if (opt.isEmpty()) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    final Task task = opt.get();
    if (!Objects.equals(tenantId, task.getTenantId())) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    // Final-Review P1-1: logisch deaktivierte Aufgaben neutral wie fremder Tenant maskieren (404).
    if (task.getEntityState() != io.keel.framework.api.model.EntityState.ACTIVE) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    return task;
  }
}
