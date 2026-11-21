package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mitglied einer projizierten Abteilung mit den Anzeigedaten. Die Mitglieder einer Abteilung werden
 * bei jedem neuen Stand physisch ersetzt (abgeleitete Daten ohne fachliche Historie).
 */
@NovaTable(name = "TASK_ABTEILUNG_MITGLIED")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskDivisionMember extends NovaEntity<TaskDivisionMember> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf den Kopf der Projektion ({@code TASK_ABTEILUNG.ID}). */
  @NovaColumn(name = "ABTEILUNG_ID", nullable = false)
  private Long divisionId;

  /** Benutzer-UUID des Mitglieds (employeeId aus application). */
  @NovaColumn(name = "MITARBEITER_REF", nullable = false)
  private UUID employeeRef;

  /** Anzeigename des Mitglieds. */
  @NovaColumn(name = "ANZEIGENAME", nullable = true, length = 255)
  private String displayName;

  /** Vorname des Mitglieds. */
  @NovaColumn(name = "VORNAME", nullable = true, length = 255)
  private String firstName;

  /** Nachname des Mitglieds. */
  @NovaColumn(name = "NACHNAME", nullable = true, length = 255)
  private String lastName;

  /** Anmeldename des Mitglieds. */
  @NovaColumn(name = "LOGIN_NAME", nullable = true, length = 255)
  private String loginName;

  /** Kennzeichen Kernmitglied (coreMembership aus application). */
  @NovaColumn(name = "KERNMITGLIED", nullable = false)
  private boolean coreMember;
}
