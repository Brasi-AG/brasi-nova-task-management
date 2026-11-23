package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kopf der lokalen Abteilungs-Projektion je Mandant und Abteilung. ACTIVE = Abteilung in
 * application aktiv; DEACTIVE = letzter Abruf 404 (unbekannt, fremd oder inaktiv), dann ohne
 * Mitglieder.
 */
@NovaTable(name = "TASK_ABTEILUNG")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskDivision extends NovaEntity<TaskDivision> {

  private static final long serialVersionUID = 1L;

  /** UUID der Abteilung (Division) in application. */
  @NovaColumn(name = "ABTEILUNG_REF", nullable = false)
  private UUID divisionRef;

  /** Anzeigename, sobald application ihn liefert; bis dahin leer. */
  @NovaColumn(name = "NAME", nullable = true, length = 255)
  private String name;

  /** Revision des uebernommenen Stands (64 Zeichen); leer nach einem Abruf mit 404. */
  @NovaColumn(name = "REVISION", nullable = true, length = 64)
  private String revision;

  /** Startzeit des Abrufs, der den Stand lieferte; ein spaeterer Abruf gewinnt. */
  @NovaColumn(name = "ABGERUFEN_AM", nullable = false)
  private LocalDateTime fetchedAt;
}
