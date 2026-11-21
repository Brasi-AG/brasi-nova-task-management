package de.brasi.nova.taskmanagement.entity.enums;

/**
 * Ergebnis der Verarbeitung eines eingegangenen Ereignisses in der Inbox {@code
 * TASK_EREIGNIS_QUITTUNG}. Von KSF nativ als String persistiert ({@code STATUS}).
 */
public enum EventReceiptStatus {
  /** Ereignis verarbeitet. */
  PROCESSED,
  /** Ereignis endgueltig verworfen; der Grund steht in der Fehlermeldung. */
  DEAD
}
