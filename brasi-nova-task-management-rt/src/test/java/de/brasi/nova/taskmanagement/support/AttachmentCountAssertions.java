package de.brasi.nova.taskmanagement.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pruefhilfe der ITs zum denormalisierten Anhang-Zaehler {@code TASK.ANHANG_ANZAHL} (Intake #194).
 *
 * <p>Vergleicht den Zaehler einer Aufgabe mit der Zahl der Zeilen, die nach derselben Regel zaehlen
 * wie der Backfill in {@code V17__task_attachment_count.sql}: aktive Zeilen in {@code TASK_ANHANG},
 * Kommentar-Anhaenge nur an aktiven Kommentaren. Die ITs rufen {@link #assertCountMatchesRows} nach
 * jedem Schritt auf, der den Zaehler aendern koennte.
 */
public final class AttachmentCountAssertions {

  /** Zaehlregel des V17-Backfills, eingeschraenkt auf eine Aufgabe (externe UUID). */
  private static final String COUNTED_ROWS_SQL =
      "SELECT COUNT(*) FROM [dbo].[TASK_ANHANG] a"
          + " JOIN [dbo].[TASK] t ON t.[ID] = a.[TASK_ID]"
          + " LEFT JOIN [dbo].[TASK_COMMENT] c ON c.[ID] = a.[KOMMENTAR_ID]"
          + " WHERE t.[EXTERNALID] = ? AND a.[ENTITY_STATUS] = 'ACTIVE'"
          + " AND (a.[KOMMENTAR_ID] IS NULL OR c.[ENTITY_STATUS] = 'ACTIVE')";

  private AttachmentCountAssertions() {}

  /**
   * Gespeicherter Zaehler {@code ANHANG_ANZAHL} der Aufgabe.
   *
   * @param jdbc JdbcTemplate der Wegwerf-DB
   * @param taskUuid externe UUID der Aufgabe
   * @return Wert der Spalte
   */
  public static int attachmentCount(final JdbcTemplate jdbc, final UUID taskUuid) {
    return jdbc.queryForObject(
        "SELECT [ANHANG_ANZAHL] FROM [dbo].[TASK] WHERE [EXTERNALID] = ?", Integer.class, taskUuid);
  }

  /**
   * Zahl der Anhangzeilen der Aufgabe, die nach der Zaehlregel zaehlen.
   *
   * @param jdbc JdbcTemplate der Wegwerf-DB
   * @param taskUuid externe UUID der Aufgabe
   * @return aktive Aufgaben-Anhaenge plus aktive Anhaenge an aktiven Kommentaren
   */
  public static int countedRows(final JdbcTemplate jdbc, final UUID taskUuid) {
    return jdbc.queryForObject(COUNTED_ROWS_SQL, Integer.class, taskUuid);
  }

  /**
   * Prueft, dass {@code ANHANG_ANZAHL} der Aufgabe gleich der Zahl der zaehlenden Anhangzeilen ist.
   *
   * @param jdbc JdbcTemplate der Wegwerf-DB
   * @param taskUuid externe UUID der Aufgabe
   */
  public static void assertCountMatchesRows(final JdbcTemplate jdbc, final UUID taskUuid) {
    assertThat(attachmentCount(jdbc, taskUuid))
        .as("ANHANG_ANZAHL der Aufgabe %s gleich der Zahl der aktiven Anhangzeilen", taskUuid)
        .isEqualTo(countedRows(jdbc, taskUuid));
  }
}
