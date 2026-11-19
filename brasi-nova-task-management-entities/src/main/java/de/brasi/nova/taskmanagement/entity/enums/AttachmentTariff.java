package de.brasi.nova.taskmanagement.entity.enums;

/**
 * Tarif eines Mandanten fuer die Dateigroessen-Grenze von Anhaengen. Von KSF nativ als String
 * persistiert ({@code TASK_ANHANG_GRENZE.TARIF}); die Spannen je Tarif liegen in der
 * Konfiguration, nicht im Enum.
 */
public enum AttachmentTariff {
  /** Vorgabe ohne Eintrag. */
  STANDARD,
  /** Erweiterte Grenze. */
  EXPERT,
  /** Hoechste Grenze. */
  ENTERPRISE
}
