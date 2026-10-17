package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import de.brasi.nova.taskmanagement.participant.model.ParticipantPage;
import de.brasi.nova.taskmanagement.participant.model.ParticipantSummary;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Suche nach aktiven Benutzern als Teilnehmer-Kandidaten (R-PART-KEYSET).
 *
 * <p>Tenant-scoped: laeuft im Request-Tenant-Kontext (kein {@code runWithTenant} noetig).
 * Paginierung: Keyset-Cursor ueber {@code EXTERNALID ASC} (stabil, non-null, unique).
 * Limit: Default 50, geclampt auf [1, 100] (R-PART-KEYSET).
 * hasMore: Logik ueber limit+1-Abfrage.
 */
@Service
public class ParticipantSearchService {

  private static final Logger log = LoggerFactory.getLogger(ParticipantSearchService.class);

  /** Default-Limit (R-PART-KEYSET). */
  private static final int DEFAULT_LIMIT = 50;

  /** Maximales Limit (R-PART-KEYSET). */
  private static final int MAX_LIMIT = 100;

  /** Minimales Limit (R-PART-KEYSET). */
  private static final int MIN_LIMIT = 1;

  private final UserReadRepository userReadRepository;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public ParticipantSearchService(final UserReadRepository userReadRepository) {
    this.userReadRepository = userReadRepository;
  }

  /**
   * Sucht nach aktiven Benutzern des angegebenen Mandanten.
   *
   * <p>q blank/null = kein LIKE-Filter; q vorhanden = LIKE-Pattern {@code "%" + escape(q) + "%"}
   * (escape fuer {@code %}, {@code _}, {@code [}). Limit-Clamp: [1, 100] (Default 50).
   * First-Page vs. Keyset-Folgeseite anhand des {@code cursor}-Parameters.
   *
   * @param tenantId interne Mandanten-ID (aus Security-Context)
   * @param q        Suchbegriff (null/blank = kein Filter)
   * @param cursor   opaker Cursor (null/blank = erste Seite)
   * @param limit    Seitengroesse (wird geclampt)
   * @return Seite der Teilnehmer-Zusammenfassungen
   */
  public ParticipantPage search(
      final long tenantId,
      final String q,
      final String cursor,
      final int limit) {

    // Limit clampen (R-PART-KEYSET): geclampt auf [1, 100]
    final int effectiveLimit = Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));

    // LIKE-Pattern aufbauen (null = kein Filter)
    final String likePattern = (q == null || q.isBlank()) ? null : "%" + escapeLike(q) + "%";

    final List<User> raw;

    if (cursor == null || cursor.isBlank()) {
      // Erste Seite (ohne Keyset-Praedikat)
      raw = userReadRepository.findFirstPage(likePattern, effectiveLimit + 1);
    } else {
      // Folgeseite: Cursor dekodieren (1 Token: EXTERNALID)
      final List<String> tokens = KeysetCursor.decode(cursor, 1);
      final UUID afterExternalId;
      try {
        afterExternalId = UUID.fromString(tokens.get(0));
      } catch (IllegalArgumentException e) {
        throw new EntityValidationException("Ungueltiger cursor.");
      }
      raw = userReadRepository.findPage(likePattern, afterExternalId, effectiveLimit + 1);
    }

    // hasMore-Logik
    final boolean hasMore = raw.size() > effectiveLimit;
    final List<User> page = hasMore ? raw.subList(0, effectiveLimit) : raw;

    // nextCursor aus dem letzten Element (EXTERNALID)
    final String nextCursor;
    if (hasMore) {
      final User last = page.get(page.size() - 1);
      nextCursor = KeysetCursor.encode(List.of(last.getUuid().toString()));
    } else {
      nextCursor = null;
    }

    // Entities mappen
    final List<ParticipantSummary> items = new ArrayList<>();
    for (final User u : page) {
      items.add(new ParticipantSummary(u.getUuid(), u.getLoginName(), u.getFullName()));
    }

    return new ParticipantPage(items, nextCursor, hasMore);
  }

  /**
   * Escaped Sonderzeichen fuer MSSQL-LIKE-Queries.
   *
   * <p>Escaped {@code %}, {@code _} und {@code [} durch Einklammern in eckige Klammern,
   * um unbeabsichtigte Wildcard-Interpretationen zu vermeiden.
   *
   * @param value der zu escapende Suchbegriff
   * @return der escapte Suchbegriff
   */
  static String escapeLike(final String value) {
    return value
        .replace("[", "[[]")
        .replace("%", "[%]")
        .replace("_", "[_]");
  }
}
