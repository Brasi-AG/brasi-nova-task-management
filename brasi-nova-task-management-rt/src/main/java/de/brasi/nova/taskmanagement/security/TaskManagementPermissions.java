package de.brasi.nova.taskmanagement.security;

import java.util.List;

/**
 * Task-Management-Berechtigungen als KSF-native Permission-Authorities.
 *
 * <p><strong>Zwei-Ebenen-Vertrag (identisch zu KassenbuchSecurityRoles-Muster):</strong> kpl-identity
 * speichert den Action-Key BARE; der Enforcement-Constant ist {@code MODULE_NAME + ":" +
 * ACTION_KEY}. Der KSF-{@code PermissionEnforcementAspect} vergleicht die Annotation EXAKT gegen
 * die JWT-Authority.
 *
 * <ul>
 *   <li><b>Bare Action-Keys</b> ({@link #TASK_READ_ACTION} usw.) - Registrierungs-/Config-Vertrag
 *       ({@code ksf.module-registration.actions[*].action-key}).
 *   <li><b>Volle (prefixed) Authorities</b> ({@link #TASK_READ} usw.) - Enforcement-/Token-Vertrag:
 *       immer mit dem vollen Praefix {@link #MODULE_NAME}{@code :} verwenden.
 * </ul>
 */
public final class TaskManagementPermissions {

  /**
   * Technischer Modul-Name (Permission-Praefix) - identisch mit {@code
   * ksf.module-registration.module-name}. Der Identity-Server stellt die volle Authority als
   * {@code MODULE_NAME + ":" + <action-key>} aus.
   */
  public static final String MODULE_NAME = "task-management";

  /** Barer Action-Key fuer lesenden Zugriff auf Aufgaben. */
  public static final String TASK_READ_ACTION = "TASK_READ";

  /** Barer Action-Key fuer schreibenden Zugriff auf Aufgaben. */
  public static final String TASK_WRITE_ACTION = "TASK_WRITE";

  /** Barer Action-Key fuer lesenden Zugriff auf Benachrichtigungen. */
  public static final String NOTIFICATION_READ_ACTION = "NOTIFICATION_READ";

  /** Barer Action-Key fuer schreibenden Zugriff auf Benachrichtigungen. */
  public static final String NOTIFICATION_WRITE_ACTION = "NOTIFICATION_WRITE";

  /** Barer Action-Key fuer Tag-Verwaltung (Admin). */
  public static final String TAG_ADMIN_ACTION = "TAG_ADMIN";

  /**
   * Volle Authority fuer lesenden Zugriff auf Aufgaben (Enforcement/Token) - {@link
   * #MODULE_NAME}{@code :TASK_READ}.
   */
  public static final String TASK_READ = MODULE_NAME + ":" + TASK_READ_ACTION;

  /** Volle Authority fuer schreibenden Zugriff auf Aufgaben (Enforcement/Token). */
  public static final String TASK_WRITE = MODULE_NAME + ":" + TASK_WRITE_ACTION;

  /** Volle Authority fuer lesenden Zugriff auf Benachrichtigungen (Enforcement/Token). */
  public static final String NOTIFICATION_READ = MODULE_NAME + ":" + NOTIFICATION_READ_ACTION;

  /** Volle Authority fuer schreibenden Zugriff auf Benachrichtigungen (Enforcement/Token). */
  public static final String NOTIFICATION_WRITE = MODULE_NAME + ":" + NOTIFICATION_WRITE_ACTION;

  /** Volle Authority fuer Tag-Verwaltung (Enforcement/Token). */
  public static final String TAG_ADMIN = MODULE_NAME + ":" + TAG_ADMIN_ACTION;

  /** Katalog aller BAREN Action-Keys (Registrierungs-Vertrag). */
  public static final List<String> ALL_ACTION_KEYS =
      List.of(
          TASK_READ_ACTION,
          TASK_WRITE_ACTION,
          NOTIFICATION_READ_ACTION,
          NOTIFICATION_WRITE_ACTION,
          TAG_ADMIN_ACTION);

  /** Katalog aller VOLLEN (prefixed) Authorities (Enforcement-Quelle). */
  public static final List<String> ALL_AUTHORITIES =
      List.of(TASK_READ, TASK_WRITE, NOTIFICATION_READ, NOTIFICATION_WRITE, TAG_ADMIN);

  private TaskManagementPermissions() {}
}
