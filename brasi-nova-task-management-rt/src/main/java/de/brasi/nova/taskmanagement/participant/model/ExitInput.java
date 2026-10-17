package de.brasi.nova.taskmanagement.participant.model;

import de.brasi.nova.taskmanagement.entity.enums.ExitKind;

/**
 * Eingabe-DTO fuer den Austritt des aktuellen Akteurs aus einer Aufgabe.
 *
 * <p>Der Akteur setzt auf seinem {@link de.brasi.nova.taskmanagement.entity.TaskParticipant}
 * den {@code EXIT_KIND} und {@code EXIT_AT}. Bei {@link ExitKind#HANDOVER} wird zudem eine
 * HANDOVER-Notification emittiert.
 *
 * @param exitKind Art des Austritts (HANDOVER | UNSUBSCRIBED), darf nicht null sein
 */
public record ExitInput(ExitKind exitKind) {

  /** Kompakter Konstruktor: validiert, dass exitKind nicht null ist. */
  public ExitInput {
    if (exitKind == null) {
      throw new IllegalArgumentException("exitKind darf nicht null sein.");
    }
  }
}
