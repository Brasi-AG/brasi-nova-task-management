package de.brasi.nova.taskmanagement.repository;

import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * KSF-Repository der Mitglieder der Abteilungs-Projektion ({@code TASK_ABTEILUNG_MITGLIED}, Intake
 * #190). Alle Queries sind ueber {@code :tenantId} an den Kontext-Mandanten gebunden.
 */
public interface TaskDivisionMemberQueryRepository extends BaseTaskDivisionMemberRepository {

  /**
   * Loescht die Mitglieder einer Abteilung physisch. Die Zeilen sind abgeleitete Daten ohne
   * fachliche Historie und werden bei jedem neuen Stand ersetzt (V15).
   *
   * @param divisionId interne ID des Abteilungskopfs ({@code TASK_ABTEILUNG.ID})
   * @return Zahl der geloeschten Zeilen
   */
  @NovaQuery(
      modifying = true,
      value =
          "DELETE FROM TASK_ABTEILUNG_MITGLIED WHERE TENANT_ID = :tenantId"
              + " AND ABTEILUNG_ID = :divisionId",
      mssql =
          "DELETE FROM [dbo].[TASK_ABTEILUNG_MITGLIED] WHERE TENANT_ID = :tenantId"
              + " AND ABTEILUNG_ID = :divisionId")
  int deleteByDivisionId(@Param("divisionId") Long divisionId);

  /**
   * Hoechstens {@code limit} verschiedene Personen ({@code MITARBEITER_REF}) der angegebenen
   * Abteilungen (Aufloesung der Abteilungserwaehnung). Ob die Person einen aktiven Benutzer hat,
   * prueft der Aufrufer danach.
   *
   * <p>Die Obergrenze verhindert, dass bei sehr grossen Abteilungen alle Mitglieder geladen werden,
   * bevor die Personengrenze der Erwaehnung greift: der Aufrufer fragt mit Grenze plus eins und
   * erkennt am Ergebnis, ob die Grenze ueberschritten ist.
   *
   * <p><b>Pflicht des Aufrufers:</b> {@code divisionIds} nie leer uebergeben (MSSQL {@code IN ()}
   * ist ein Syntaxfehler); die Menge ist durch das Eingabe-DTO auf 10 begrenzt.
   *
   * @param divisionIds interne IDs der Abteilungskoepfe ({@code TASK_ABTEILUNG.ID}, nicht leer)
   * @param limit hoechste Zahl der gelieferten Personen (mindestens 1)
   * @return hoechstens {@code limit} verschiedene Personen-UUIDs, ohne feste Reihenfolge (bei mehr
   *     Personen eine beliebige Auswahl)
   */
  @NovaQuery(
      value =
          "SELECT DISTINCT TOP (:limit) MITARBEITER_REF FROM TASK_ABTEILUNG_MITGLIED"
              + " WHERE TENANT_ID = :tenantId AND ENTITY_STATUS = 'ACTIVE'"
              + " AND ABTEILUNG_ID IN (:divisionIds)",
      mssql =
          "SELECT DISTINCT TOP (:limit) MITARBEITER_REF FROM [dbo].[TASK_ABTEILUNG_MITGLIED]"
              + " WHERE TENANT_ID = :tenantId AND ENTITY_STATUS = 'ACTIVE'"
              + " AND ABTEILUNG_ID IN (:divisionIds)")
  List<UUID> findDistinctEmployeeRefsByDivisionIds(
      @Param("divisionIds") Collection<Long> divisionIds, @Param("limit") int limit);
}
