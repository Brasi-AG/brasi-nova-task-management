package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.division.model.DivisionCandidateModel;
import de.brasi.nova.taskmanagement.division.model.DivisionCandidatePage;
import de.brasi.nova.taskmanagement.repository.DivisionCandidateRow;
import de.brasi.nova.taskmanagement.repository.TaskDivisionQueryRepository;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Vorschlaege fuer die Abteilungserwaehnung (Intake #190, {@code GET /api/v1/tasks/divisions}).
 *
 * <p>Liest nur die lokale Projektion des Kontext-Mandanten (die Queries binden {@code :tenantId}).
 * Mit {@code ids} liefert der Service die Vorschau bekannter Abteilungen (unbekannte, inaktive und
 * mandantenfremde fehlen; {@code q} und {@code limit} wirken dann nicht), sonst die Suche ueber den
 * Namen. Der Suchbegriff wird getrimmt, klein geschrieben und woertlich als Teilstring gesucht: die
 * LIKE-Sonderzeichen {@code %}, {@code _}, {@code [} und das Escape-Zeichen {@code !} bekommen ein
 * vorangestelltes {@code !} (Muster der Archivsuche, Lesson {@code ksf-novaquery-pitfalls}). Nach
 * dem Trimmen sind hoechstens {@value #MAX_QUERY_LENGTH} Zeichen erlaubt, damit das LIKE-Muster die
 * 8.000-Byte-Grenze von SQL Server nie erreicht.
 */
@Service
public class DivisionCandidateService {

  /** Kleinste erlaubte Zahl der Vorschlaege. */
  static final int MIN_LIMIT = 1;

  /** Groesste erlaubte Zahl der Vorschlaege. */
  static final int MAX_LIMIT = 100;

  /** Hoechstzahl der UUIDs fuer die Vorschau (begrenzt die {@code IN}-Liste). */
  static final int MAX_IDS = 50;

  /** Laengste erlaubte Laenge des Suchbegriffs {@code q} in Zeichen, nach dem Trimmen. */
  static final int MAX_QUERY_LENGTH = 200;

  /** Escape-Zeichen des LIKE-Musters (muss zum {@code ESCAPE '!'} der Query passen). */
  private static final char ESCAPE = '!';

  private final TaskDivisionQueryRepository divisionRepository;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public DivisionCandidateService(final TaskDivisionQueryRepository divisionRepository) {
    this.divisionRepository = divisionRepository;
  }

  /**
   * Liefert die Vorschlaege des Kontext-Mandanten.
   *
   * @param q Suchbegriff ueber den Namen (null oder leer = kein Filter; bei gesetzten {@code ids}
   *     ohne Wirkung), nach dem Trimmen hoechstens {@value #MAX_QUERY_LENGTH} Zeichen (auch bei
   *     {@code ids} geprueft)
   * @param ids UUIDs fuer die Vorschau ({@code null} = Suche; eine leere Liste, etwa bei {@code
   *     ?ids=}, ist ein 400), hoechstens {@value #MAX_IDS}
   * @param limit hoechste Zahl der Vorschlaege der Suche ({@value #MIN_LIMIT} bis {@value
   *     #MAX_LIMIT}); wird auch bei {@code ids} geprueft, wirkt dort aber nicht
   * @return die Vorschlaege, sortiert nach Name (unbenannte zuletzt); bei gleichem Namen stabil
   *     nach UUID in GUID-Sortierung (fachlich bedeutungslos, nicht alphabetisch)
   * @throws EntityValidationException wenn {@code limit} ausserhalb des Bereichs liegt, {@code q}
   *     nach dem Trimmen laenger als {@value #MAX_QUERY_LENGTH} Zeichen ist, mehr als {@value
   *     #MAX_IDS} {@code ids} uebergeben wurden oder {@code ids} leer ist oder einen leeren
   *     ({@code null}) Eintrag enthaelt (HTTP 400)
   */
  public DivisionCandidatePage find(final String q, final List<UUID> ids, final int limit) {
    if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
      throw new EntityValidationException(
          "Der Parameter 'limit' muss zwischen "
              + MIN_LIMIT
              + " und "
              + MAX_LIMIT
              + " liegen, ist aber "
              + limit
              + ".");
    }
    final String term = q == null ? "" : q.strip();
    if (term.codePointCount(0, term.length()) > MAX_QUERY_LENGTH) {
      throw new EntityValidationException(
          "Der Parameter 'q' darf hoechstens " + MAX_QUERY_LENGTH + " Zeichen lang sein.");
    }
    if (ids != null && ids.size() > MAX_IDS) {
      throw new EntityValidationException(
          "Der Parameter 'ids' darf hoechstens " + MAX_IDS + " Eintraege enthalten.");
    }
    // Ein fehlender Parameter ist null (Suche). Ein einzelnes ?ids= bindet Spring dagegen zur
    // leeren Liste, ?ids=, und ?ids=&ids= zu Listen aus null: beides ist ein leerer Eintrag. Ein
    // null in der IN-Liste der Query wuerde als untypisierter Null-Parameter gebunden (Lesson
    // ksf-novaquery-pitfalls). stream statt contains: List.of(...) wirft bei contains(null).
    if (ids != null && (ids.isEmpty() || ids.stream().anyMatch(Objects::isNull))) {
      throw new EntityValidationException(
          "Der Parameter 'ids' darf keine leeren Eintraege enthalten.");
    }
    final List<DivisionCandidateRow> rows;
    if (ids != null) {
      rows = divisionRepository.findCandidatesByRefs(ids);
    } else {
      rows = divisionRepository.findCandidates(likePattern(term), limit);
    }
    return new DivisionCandidatePage(
        rows.stream()
            .map(
                row -> new DivisionCandidateModel(row.divisionRef(), row.name(), row.memberCount()))
            .toList());
  }

  /**
   * Baut das LIKE-Muster des Suchbegriffs. Die Laenge prueft der Aufrufer ({@link #find}).
   *
   * @param q Suchbegriff (wird getrimmt)
   * @return {@code %<escaped, klein>%}, oder {@code null} bei fehlendem oder leerem Begriff
   */
  static String likePattern(final String q) {
    final String trimmed = q == null ? "" : q.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    final String lower = trimmed.toLowerCase(Locale.ROOT);
    final StringBuilder escaped = new StringBuilder(lower.length() + 8);
    escaped.append('%');
    for (int i = 0; i < lower.length(); i++) {
      final char c = lower.charAt(i);
      if (c == ESCAPE || c == '%' || c == '_' || c == '[') {
        escaped.append(ESCAPE);
      }
      escaped.append(c);
    }
    escaped.append('%');
    return escaped.toString();
  }
}
