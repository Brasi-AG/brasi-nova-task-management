package de.brasi.nova.taskmanagement.comment;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Ergebnis der Ermittlung der Erwaehnungs-Empfaenger ({@link MentionRecipientService#collect}).
 *
 * @param notifyTargets Personen, die eine MENTION bekommen: erwaehnte WRITE-Mitglieder und die per
 *     Erwaehnung aufgenommenen Personen; ohne Akteur und ohne den ausgenommenen Empfaenger
 * @param admitted Personen, die durch die Erwaehnung Beteiligte wurden (Teilmenge von {@code
 *     notifyTargets}); sie gehoeren zusaetzlich in Generation-Bump und Signal des Aufrufers
 */
public record MentionRecipients(Set<UUID> notifyTargets, Set<UUID> admitted) {

  /** Kompakter Konstruktor: unveraenderliche Kopien, die Reihenfolge bleibt erhalten. */
  public MentionRecipients {
    notifyTargets = Collections.unmodifiableSet(new LinkedHashSet<>(notifyTargets));
    admitted = Collections.unmodifiableSet(new LinkedHashSet<>(admitted));
  }
}
