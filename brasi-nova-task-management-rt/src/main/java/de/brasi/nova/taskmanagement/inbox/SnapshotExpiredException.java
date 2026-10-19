package de.brasi.nova.taskmanagement.inbox;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Signalisiert, dass ein Inbox-Snapshot abgelaufen ist und der Client neu bootstrappen muss
 * (Finding 2 / Option B). Zwei Ausloeser:
 *
 * <ul>
 *   <li><b>Folgeseite (Cursor):</b> Die Membership-Generation des Anfragers hat sich seit dem
 *       First-Page-Load geaendert (destruktive Reassign-/Exit-Mutation) -> der alte Cursor koennte
 *       inkonsistente Seiten liefern.
 *   <li><b>First-Page/Bootstrap:</b> Unter anhaltender Membership-Aenderung konnte binnen
 *       {@code MAX_SNAPSHOT_ATTEMPTS} kein konsistenter Snapshot erzeugt werden (seltene, sichere
 *       Degradation statt gemischter Antwort).
 * </ul>
 *
 * <p><b>Bewusst {@link ResponseStatusException}-Subklasse</b> (nicht {@code @ResponseStatus}): Der
 * KSF-{@code GlobalApiExceptionHandler} besitzt einen Catch-all {@code @ExceptionHandler(Exception.class)}
 * mit hoher Prioritaet, der eine rein mit {@code @ResponseStatus(GONE)} annotierte Exception VOR dem
 * {@code ResponseStatusExceptionResolver} abfaengt und zu HTTP 500 abbilden wuerde. Nur eine
 * {@link ResponseStatusException} wird vom dedizierten KSF-Handler statusgetreu als
 * <b>HTTP 410 Gone</b> ausgeliefert.
 */
public class SnapshotExpiredException extends ResponseStatusException {

  private static final long serialVersionUID = 1L;

  /** Erzeugt die Exception mit HTTP 410 Gone und neutraler Begruendung. */
  public SnapshotExpiredException() {
    super(
        HttpStatus.GONE,
        "Inbox-Snapshot abgelaufen oder nicht konsistent erzeugbar; bitte neu laden.");
  }
}
