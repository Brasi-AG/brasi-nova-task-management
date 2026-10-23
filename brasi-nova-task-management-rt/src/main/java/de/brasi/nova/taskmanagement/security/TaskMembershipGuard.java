package de.brasi.nova.taskmanagement.security;

import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Objekt-level Membership-Guard fuer die member-scoped Endpunkte des Task-Managements
 * (Standard-Selfservice). Ersetzt das grobe permission-basierte Gate durch eine Pruefung, ob der
 * Akteur aktives Mitglied ({@code TASK_PARTICIPANT}) der konkreten Aufgabe ist.
 *
 * <p>Eine Query-Quelle: zwei {@code EXISTS}-Praedikate auf {@link TaskParticipantQueryRepository}
 * (ein {@code EXISTS} je Request, kein N+1). Der Mandanten-Schluessel {@code :tenantId} wird vom
 * KSF-{@code NovaQueryTenantValidator} automatisch aus dem {@code TenantContextHolder} gebunden; da
 * Aufgabe und Teilnehmer strikt mandanten-isoliert sind, ist der aktive Kontext-Mandant der
 * Task-Mandant. Der {@code tenant}-Parameter bleibt Teil des Guard-Kontrakts (Nachvollziehbarkeit,
 * Defense-in-Depth), wird aber nicht als Query-Parameter gebunden.
 *
 * <p><b>Fail-closed Tenant-Vertrag (Defense-in-Depth):</b> jede Guard-Methode prueft VOR der Query,
 * dass der uebergebene {@code tenant} mit dem aktuell im {@link TenantContextHolder} gebundenen
 * Mandanten uebereinstimmt. Weicht er ab (oder fehlt der Kontext), wird die Membership NICHT im
 * Kontext-Mandanten ausgewertet: {@code requireRead/WriteMember} werfen {@link
 * EntityNotFoundException} (404, neutral, kein Leak), {@code isWriteMember} liefert {@code false}.
 * So kann ein abweichender {@code tenant}-Wert nie zu einer Membership-Bestaetigung im falschen
 * Mandanten fuehren.
 *
 * <p>Membership-Praedikate (SPEC, verifiziert gegen {@code TaskParticipant} + V1-DDL):
 *
 * <ul>
 *   <li><b>WRITE-Member:</b> aktive PERSON-Zeile mit {@code EXIT_KIND IS NULL}, beide Tenant-Spalten
 *       == Task-Mandant. Darf oeffnen, bedienen (PATCH/Marker/Comment/exit).
 *   <li><b>READ-Member:</b> wie WRITE, aber ausgetretene Mitglieder ({@code EXIT_KIND} gesetzt,
 *       Zeile bleibt {@code ENTITY_STATUS='ACTIVE'}) duerfen weiterhin ihr Archiv-Detail lesen.
 * </ul>
 *
 * <p>Fehlschlag -> {@link EntityNotFoundException} (HTTP 404, neutral; identisch zu Fremd-Tenant/
 * Nicht-gefunden, kein Existenz-Leak).
 */
@Component
public class TaskMembershipGuard {

  private final TaskParticipantQueryRepository participantQueryRepository;

  /**
   * Konstruktor-Injektion.
   *
   * @param participantQueryRepository Repository mit den Membership-{@code EXISTS}-Praedikaten
   */
  public TaskMembershipGuard(
      final TaskParticipantQueryRepository participantQueryRepository) {
    this.participantQueryRepository = participantQueryRepository;
  }

  /**
   * Stellt sicher, dass {@code actorRef} READ-Member der Aufgabe ist (aktive PERSON-Zeile im
   * Task-Mandanten; ausgetretene Mitglieder eingeschlossen). Andernfalls neutrale 404.
   *
   * @param taskId   interne Aufgaben-ID
   * @param actorRef UUID des Akteurs (aus dem Security-Context, nie aus Client-Daten)
   * @param tenant   Mandanten-Schluessel der Aufgabe (Kontrakt/Defense; Query bindet :tenantId auto)
   * @throws EntityNotFoundException wenn kein READ-Member (HTTP 404)
   */
  public void requireReadMember(final Long taskId, final UUID actorRef, final long tenant) {
    if (!matchesContextTenant(tenant)
        || !participantQueryRepository.existsReadMember(taskId, actorRef)) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
  }

  /**
   * Stellt sicher, dass {@code actorRef} WRITE-Member der Aufgabe ist (aktive, nicht ausgetretene
   * PERSON-Zeile im Task-Mandanten). Andernfalls neutrale 404.
   *
   * @param taskId   interne Aufgaben-ID
   * @param actorRef UUID des Akteurs (aus dem Security-Context, nie aus Client-Daten)
   * @param tenant   Mandanten-Schluessel der Aufgabe (Kontrakt/Defense; Query bindet :tenantId auto)
   * @throws EntityNotFoundException wenn kein WRITE-Member (HTTP 404)
   */
  public void requireWriteMember(final Long taskId, final UUID actorRef, final long tenant) {
    if (!matchesContextTenant(tenant)
        || !participantQueryRepository.existsWriteMember(taskId, actorRef)) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
  }

  /**
   * Prueft (ohne Exception), ob {@code ref} WRITE-Member der Aufgabe ist. Fuer die bedingte
   * Handover-Zielpruefung (Slice 4): ein Handover-Ziel ist nur dann grant-frei zulaessig, wenn es
   * bereits WRITE-Member ist.
   *
   * @param taskId interne Aufgaben-ID
   * @param ref    UUID des zu pruefenden Teilnehmers
   * @param tenant Mandanten-Schluessel der Aufgabe (Kontrakt/Defense; Query bindet :tenantId auto)
   * @return {@code true}, wenn {@code ref} aktiver WRITE-Member der Aufgabe ist
   */
  public boolean isWriteMember(final Long taskId, final UUID ref, final long tenant) {
    return matchesContextTenant(tenant) && participantQueryRepository.existsWriteMember(taskId, ref);
  }

  /**
   * Prueft fail-closed, ob der uebergebene {@code tenant} mit dem aktuell im {@link
   * TenantContextHolder} gebundenen Mandanten uebereinstimmt. Da die Membership-Query {@code
   * :tenantId} ausschliesslich aus dem Kontext bindet, waere eine Auswertung bei abweichendem
   * {@code tenant} eine Pruefung im FALSCHEN Mandanten. Fehlt der Kontext (kein {@code
   * TenantScoped}), gilt die Pruefung ebenfalls als nicht erfuellt.
   *
   * @param tenant der vom Aufrufer uebergebene Mandanten-Schluessel der Aufgabe
   * @return {@code true}, wenn ein Kontext-Mandant gebunden ist und seine interne Id {@code tenant}
   *     entspricht; sonst {@code false}
   */
  private boolean matchesContextTenant(final long tenant) {
    return TenantContextHolder.getTenant()
        .map(TenantInfo::id)
        .map(contextTenant -> contextTenant == tenant)
        .orElse(false);
  }
}
