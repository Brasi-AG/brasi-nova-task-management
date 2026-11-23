package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.entity.TaskDivision;
import de.brasi.nova.taskmanagement.repository.TaskDivisionMemberQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskDivisionQueryRepository;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Loest erwaehnte Abteilungen auf die Personen der lokalen Projektion auf (Intake #190).
 *
 * <p>Nur die Projektion des Kontext-Mandanten wird gelesen ({@code :tenantId} in beiden Queries),
 * nie application. Eine erwaehnte Abteilung muss im Mandanten aktiv sein; unbekannt, Grabstein
 * ({@code DEACTIVE}) und mandantenfremd ergeben denselben Fehler, damit kein Existenz-Leck
 * entsteht. Die Personen aller erwaehnten Abteilungen werden vereinigt und dedupliziert; ihre Zahl
 * ist durch {@link DivisionMentionProperties#maxPersons()} begrenzt. Gezaehlt werden die
 * verschiedenen {@code MITARBEITER_REF} vor dem Filter auf aktive Benutzer, den der Aufrufer danach
 * anwendet.
 *
 * <p>Laeuft in der Transaktion des Aufrufers und dort vor jeder Schreibung (Lock-Reihenfolge von
 * {@code CommentService#createComment}): beide Queries sind reine Reads.
 */
@Service
@EnableConfigurationProperties(DivisionMentionProperties.class)
public class DivisionMentionResolver {

  /** Einheitlicher Text fuer unbekannte, inaktive und mandantenfremde Abteilungen. */
  static final String UNKNOWN_DIVISION_MESSAGE = "Unbekannte oder nicht aktive Abteilung.";

  private static final Logger log = LoggerFactory.getLogger(DivisionMentionResolver.class);

  private final TaskDivisionQueryRepository divisionRepository;
  private final TaskDivisionMemberQueryRepository memberRepository;
  private final DivisionMentionProperties properties;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public DivisionMentionResolver(
      final TaskDivisionQueryRepository divisionRepository,
      final TaskDivisionMemberQueryRepository memberRepository,
      final DivisionMentionProperties properties) {
    this.divisionRepository = divisionRepository;
    this.memberRepository = memberRepository;
    this.properties = properties;
  }

  /**
   * Liefert die verschiedenen Personen ({@code MITARBEITER_REF}) der erwaehnten Abteilungen.
   *
   * @param divisionRefs UUIDs der erwaehnten Abteilungen (hoechstens 10, vom Eingabe-DTO begrenzt;
   *     Duplikate erlaubt)
   * @return die Personen in stabiler Reihenfolge, leer bei leerer Eingabe oder leeren Abteilungen
   * @throws EntityValidationException wenn eine Abteilung im Mandanten nicht aktiv ist oder die
   *     Abteilungen zusammen mehr als {@code maxPersons} Personen umfassen (HTTP 400)
   */
  public Set<UUID> resolve(final Collection<UUID> divisionRefs) {
    if (divisionRefs == null || divisionRefs.isEmpty()) {
      return Set.of();
    }
    final Set<UUID> requested = new LinkedHashSet<>(divisionRefs);
    final List<TaskDivision> divisions = divisionRepository.findActiveByRefs(requested);
    final Set<UUID> found = new LinkedHashSet<>();
    final Set<Long> divisionIds = new LinkedHashSet<>();
    for (final TaskDivision division : divisions) {
      found.add(division.getDivisionRef());
      divisionIds.add(division.getId());
    }
    if (!found.containsAll(requested)) {
      log.info(
          "DivisionMentionResolver: {} von {} erwaehnten Abteilungen sind im Mandanten nicht aktiv.",
          requested.size() - found.size(),
          requested.size());
      throw new EntityValidationException(UNKNOWN_DIVISION_MESSAGE);
    }
    final Set<UUID> persons =
        new LinkedHashSet<>(memberRepository.findDistinctEmployeeRefsByDivisionIds(divisionIds));
    final int max = properties.maxPersons();
    if (persons.size() > max) {
      log.info(
          "DivisionMentionResolver: {} erwaehnte Abteilungen umfassen {} Personen, Grenze {}.",
          requested.size(),
          persons.size(),
          max);
      throw new EntityValidationException(
          "Die erwaehnten Abteilungen umfassen mehr als " + max + " Personen.");
    }
    log.debug(
        "DivisionMentionResolver: {} Abteilung(en) auf {} Person(en) aufgeloest.",
        requested.size(),
        persons.size());
    return persons;
  }
}
