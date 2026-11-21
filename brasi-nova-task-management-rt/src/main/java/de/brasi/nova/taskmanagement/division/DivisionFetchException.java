package de.brasi.nova.taskmanagement.division;

/**
 * Technischer Fehler beim Abruf der Abteilungsmitglieder oder des S2S-Tokens (HTTP 401, 403, 5xx,
 * Zeitueberschreitung, I/O, unpassende Antwort). Die Meldung nennt den Status, nie Token oder
 * Secret. Der Aufrufer wiederholt ueber die Redelivery des Brokers.
 */
public class DivisionFetchException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Fehler ohne Ursache.
   *
   * @param message deutsche Meldung ohne Token oder Secret
   */
  public DivisionFetchException(final String message) {
    super(message);
  }

  /**
   * Fehler mit Ursache.
   *
   * @param message deutsche Meldung ohne Token oder Secret
   * @param cause ausloesende Ausnahme
   */
  public DivisionFetchException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
