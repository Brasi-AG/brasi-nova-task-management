package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loest @handle-Mentions aus einem Kommentartext zu UUID-Listen auf.
 *
 * <p>Parst alle {@code @handle}-Token aus dem Text per Regex
 * {@code @([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)},
 * wobei Handles mit einem alphanumerischen Zeichen beginnen und enden muessen
 * (fuehrende/abschliessende Punkte/Bindestriche/Unterstriche werden abgeschnitten),
 * loest jeden eindeutigen Handle per {@link UserReadRepository#findByLoginName(String)} auf
 * (Mandant automatisch durch den Tenant-Kontext gebunden) und gibt die externe UUID
 * (EXTERNALID = {@code getUuid()}) der aufgeloesten Benutzer zurueck.
 * Nicht aufloesbare Handles werden still ignoriert (kein Fehler, keine Benachrichtigung).
 */
@Component
public class MentionResolver {

  private static final Logger log = LoggerFactory.getLogger(MentionResolver.class);

  /** Regex-Muster fuer @handle-Tokens im Kommentartext. */
  private static final Pattern MENTION_PATTERN =
      Pattern.compile("@([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)");

  private final UserReadRepository userReadRepository;

  /** Konstruktor-Injektion des {@link UserReadRepository}. */
  public MentionResolver(final UserReadRepository userReadRepository) {
    this.userReadRepository = userReadRepository;
  }

  /**
   * Loest alle @handle-Mentions aus dem Text auf.
   *
   * <p>Ablauf:
   * <ol>
   *   <li>Alle {@code @handle}-Token aus dem Text extrahieren (Regex).
   *   <li>Duplikate eliminieren (erster Treffer gewinnt).
   *   <li>Jeden Handle per {@link UserReadRepository#findByLoginName(String)} aufloesen.
   *   <li>Nicht aufloesbare Handles still ignorieren.
   * </ol>
   *
   * @param text Kommentartext mit optionalen @handle-Mentions
   * @return deduplizierte Liste der externen UUIDs ({@code getUuid()}) aller aufgeloesten Benutzer
   */
  public List<UUID> resolve(final String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }

    final Set<String> handles = new LinkedHashSet<>();
    final Matcher matcher = MENTION_PATTERN.matcher(text);
    while (matcher.find()) {
      handles.add(matcher.group(1));
    }

    // UUID-Dedup (nicht nur Handle-String-Dedup): Bei case-insensitiver Collation loesen
    // verschiedene Schreibweisen (@alice, @Alice) zum selben Benutzer auf. Ohne Dedup der
    // aufgeloesten UUIDs erhielte ein Empfaenger mehrere MENTION-Benachrichtigungen.
    // LinkedHashSet bewahrt die Reihenfolge des ersten Vorkommens.
    final Set<UUID> result = new LinkedHashSet<>();
    for (final String handle : handles) {
      userReadRepository.findByLoginName(handle).ifPresentOrElse(
          user -> {
            result.add(user.getUuid());
            log.debug("MentionResolver.resolve: Handle '{}' aufgeloest zu {}.", handle, user.getUuid());
          },
          () -> log.debug(
              "MentionResolver.resolve: Handle '{}' nicht aufgeloest (kein aktiver Benutzer).",
              handle));
    }

    return List.copyOf(result);
  }
}
