package de.brasi.nova.taskmanagement.push.model;

import java.time.Instant;

/**
 * WebSocket-Envelope fuer das {@code snapshot-invalidated}-Signal (arch-v2 §3/§6).
 *
 * <p>Eigener Envelope-Typ, NICHT eine Notification: der Client verwirft bei Empfang alle bisher
 * geladenen Snapshot-Seiten und re-bootstrapt (neues {@code asOf}) - so bleibt die Keyset-
 * Paginierung skip-frei, ohne {@code sequence}/Delta.
 *
 * @param type      fester Diskriminator, IMMER {@code "snapshot-invalidated"}
 * @param version   Envelope-Version (immer {@code "1"})
 * @param timestamp Zeitpunkt des Signals (UTC)
 */
public record SnapshotInvalidatedEnvelope(String type, String version, Instant timestamp) {

  /** Fester Envelope-Diskriminator fuer das Snapshot-Invalidate-Signal. */
  public static final String TYPE_SNAPSHOT_INVALIDATED = "snapshot-invalidated";

  /** Aktuelle Envelope-Version. */
  public static final String VERSION = "1";

  /**
   * Factory mit festem {@code type} und {@code version}.
   *
   * @param timestamp Zeitpunkt des Signals (UTC)
   * @return der Envelope
   */
  public static SnapshotInvalidatedEnvelope at(final Instant timestamp) {
    return new SnapshotInvalidatedEnvelope(TYPE_SNAPSHOT_INVALIDATED, VERSION, timestamp);
  }
}
