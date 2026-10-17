package de.brasi.nova.taskmanagement.resolver;

/**
 * Unchecked Exception: CODE kann nicht eindeutig auf eine Dictionary-Id aufgeloest werden.
 * Wird vom {@link DictionaryCodeResolver} fail-loud ausgeloest bei 0 oder mehr als 1 Treffern.
 */
public class DictionaryResolverException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Erstellt eine neue DictionaryResolverException.
   *
   * @param message Fehlermeldung mit Dictionary-Klasse, CODE und Trefferzahl
   */
  public DictionaryResolverException(final String message) {
    super(message);
  }
}
