package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.division.DivisionFetchResult.Members;
import de.brasi.nova.taskmanagement.division.DivisionFetchResult.NotFound;
import de.brasi.nova.taskmanagement.division.model.DivisionMemberPayload;
import de.brasi.nova.taskmanagement.division.model.DivisionMembersPayload;
import de.brasi.nova.taskmanagement.entity.TaskDivision;
import de.brasi.nova.taskmanagement.entity.TaskDivisionMember;
import de.brasi.nova.taskmanagement.repository.TaskDivisionMemberQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskDivisionQueryRepository;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schreibt die lokale Abteilungs-Projektion ({@code TASK_ABTEILUNG}, {@code
 * TASK_ABTEILUNG_MITGLIED}) aus dem Ergebnis eines Abrufs bei application (Intake #190).
 *
 * <p>Regel „spaeterer Abruf gewinnt“: Der Kopf wird gesperrt gelesen; ist sein {@code ABGERUFEN_AM}
 * gleich oder spaeter als der Abrufstart, bleibt alles unveraendert ({@link
 * DivisionApplyOutcome#STALE}). Sonst werden die Mitglieder physisch ersetzt und der Kopf zuletzt
 * gespeichert. Alles laeuft in der Transaktion und unter dem Mandanten des Aufrufers.
 */
@Service
public class DivisionProjectionService {

  private static final Logger log = LoggerFactory.getLogger(DivisionProjectionService.class);

  private final TaskDivisionQueryRepository divisionRepository;
  private final TaskDivisionMemberQueryRepository memberRepository;

  /**
   * Konstruktor-Injektion.
   *
   * @param divisionRepository Repository der Abteilungskoepfe
   * @param memberRepository Repository der Mitglieder
   */
  public DivisionProjectionService(
      final TaskDivisionQueryRepository divisionRepository,
      final TaskDivisionMemberQueryRepository memberRepository) {
    this.divisionRepository = divisionRepository;
    this.memberRepository = memberRepository;
  }

  /**
   * Uebernimmt ein Abrufergebnis in die Projektion.
   *
   * <p>{@link Members}: Kopf anlegen oder aktivieren, {@code REVISION}, {@code NAME} (nur wenn
   * geliefert) und {@code ABGERUFEN_AM} setzen, Mitglieder ersetzen. {@link NotFound}: einen
   * vorhandenen Kopf deaktivieren, {@code REVISION} leeren, Mitglieder loeschen; fuer eine nie
   * gesehene Abteilung einen Grabstein anlegen (Kopf {@code DEACTIVE} ohne Mitglieder, {@code
   * REVISION} und {@code NAME} leer, {@code ABGERUFEN_AM} = Abrufstart). So behaelt auch ein 404
   * sein Abruf-Wasserzeichen: ein frueher gestarteter Abruf mit Stand wird danach als {@link
   * DivisionApplyOutcome#STALE} verworfen, ein spaeter gestarteter reaktiviert den Kopf.
   *
   * <p>Nimmt nur aktuelle Staende ({@code self}) an: Ein Snapshot ist ein historischer Stand und
   * duerfte unter „spaeterer Abruf gewinnt“ einen neueren Stand ueberschreiben.
   *
   * @param divisionRef UUID der Abteilung in application
   * @param result Ergebnis des Abrufs des aktuellen Stands
   * @param fetchStartedAt Zeitpunkt vor dem HTTP-Aufruf
   * @return Ergebnis der Anwendung
   * @throws IllegalArgumentException bei einer Antwort fuer eine andere Abteilung
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public DivisionApplyOutcome apply(
      final UUID divisionRef,
      final DivisionFetchResult result,
      final LocalDateTime fetchStartedAt) {
    Objects.requireNonNull(divisionRef, "divisionRef");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(fetchStartedAt, "fetchStartedAt");
    if (result instanceof Members fetched && !divisionRef.equals(fetched.payload().divisionId())) {
      throw new IllegalArgumentException(
          "Antwort für Abteilung "
              + fetched.payload().divisionId()
              + " passt nicht zu Abteilung "
              + divisionRef
              + ".");
    }

    final Optional<TaskDivision> locked = divisionRepository.findByRefForUpdate(divisionRef);
    if (locked.isPresent() && !locked.get().getFetchedAt().isBefore(fetchStartedAt)) {
      log.debug(
          "Abteilung {}: gespeicherter Stand vom {} ist nicht älter als der Abruf vom {}.",
          divisionRef,
          locked.get().getFetchedAt(),
          fetchStartedAt);
      return DivisionApplyOutcome.STALE;
    }

    if (result instanceof Members members) {
      return applyMembers(divisionRef, locked, members.payload(), fetchStartedAt);
    }
    return applyNotFound(divisionRef, locked, fetchStartedAt);
  }

  /**
   * Revision des uebernommenen Stands einer aktiven Abteilung im Kontext-Mandanten.
   *
   * @param divisionRef UUID der Abteilung in application
   * @return die Revision, leer, wenn die Abteilung unbekannt oder deaktiviert ist
   */
  public Optional<String> currentRevision(final UUID divisionRef) {
    return divisionRepository.findRevision(divisionRef);
  }

  private DivisionApplyOutcome applyMembers(
      final UUID divisionRef,
      final Optional<TaskDivision> locked,
      final DivisionMembersPayload payload,
      final LocalDateTime fetchStartedAt) {
    final long tenantId = contextTenant().id();
    final TaskDivision division;
    if (locked.isPresent()) {
      division = locked.get();
      memberRepository.deleteByDivisionId(division.getId());
    } else {
      // Erstanlage: der Kopf braucht seine ID, bevor Mitglieder auf ihn verweisen koennen.
      division = new TaskDivision();
      division.setTenantId(tenantId);
      division.setDivisionRef(divisionRef);
      applyHead(division, payload, fetchStartedAt);
      divisionRepository.save(division);
    }

    for (final DivisionMemberPayload member : payload.members()) {
      memberRepository.save(newMember(tenantId, division.getId(), member));
    }

    if (locked.isPresent()) {
      applyHead(division, payload, fetchStartedAt);
      divisionRepository.save(division);
    }
    log.debug(
        "Abteilung {}: Stand {} mit {} Mitgliedern übernommen.",
        divisionRef,
        payload.revision(),
        payload.members().size());
    return DivisionApplyOutcome.UPDATED;
  }

  private DivisionApplyOutcome applyNotFound(
      final UUID divisionRef,
      final Optional<TaskDivision> locked,
      final LocalDateTime fetchStartedAt) {
    if (locked.isEmpty()) {
      // Grabstein: Der 404 hinterlaesst das Abruf-Wasserzeichen auch fuer eine nie gesehene
      // Abteilung, damit ein frueher gestarteter 200-Abruf danach als veraltet verworfen wird.
      final TaskDivision tombstone = new TaskDivision();
      tombstone.setTenantId(contextTenant().id());
      tombstone.setDivisionRef(divisionRef);
      tombstone.setEntityState(EntityState.DEACTIVE);
      tombstone.setRevision(null);
      tombstone.setFetchedAt(fetchStartedAt);
      divisionRepository.save(tombstone);
      log.info(
          "Abteilung {}: bei application nicht gefunden und lokal unbekannt, Grabstein angelegt.",
          divisionRef);
      return DivisionApplyOutcome.DEACTIVATED;
    }
    final TaskDivision division = locked.get();
    memberRepository.deleteByDivisionId(division.getId());
    division.setEntityState(EntityState.DEACTIVE);
    division.setRevision(null);
    division.setFetchedAt(fetchStartedAt);
    divisionRepository.save(division);
    log.info("Abteilung {}: bei application nicht gefunden, deaktiviert.", divisionRef);
    return DivisionApplyOutcome.DEACTIVATED;
  }

  private static void applyHead(
      final TaskDivision division,
      final DivisionMembersPayload payload,
      final LocalDateTime fetchStartedAt) {
    division.setEntityState(EntityState.ACTIVE);
    division.setRevision(payload.revision());
    if (payload.divisionName() != null) {
      division.setName(payload.divisionName());
    }
    division.setFetchedAt(fetchStartedAt);
  }

  private static TaskDivisionMember newMember(
      final long tenantId, final Long divisionId, final DivisionMemberPayload payload) {
    final TaskDivisionMember member = new TaskDivisionMember();
    member.setTenantId(tenantId);
    member.setDivisionId(divisionId);
    member.setEmployeeRef(payload.employeeId());
    member.setDisplayName(payload.displayName());
    member.setFirstName(payload.firstName());
    member.setLastName(payload.lastName());
    member.setLoginName(payload.loginName());
    // Der DivisionMembersClient weist ein Mitglied ohne coreMembership ab; hier gilt der geprueft
    // gelieferte Wert.
    member.setCoreMember(Objects.requireNonNull(payload.coreMembership(), "coreMembership"));
    return member;
  }

  private static TenantInfo contextTenant() {
    return TenantContextHolder.getTenant()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "DivisionProjectionService: kein Mandanten-Kontext im aktuellen Thread."));
  }
}
