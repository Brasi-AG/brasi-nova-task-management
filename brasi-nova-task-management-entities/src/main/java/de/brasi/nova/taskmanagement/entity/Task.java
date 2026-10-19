package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.entity.dictionary.TaskDirection;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskPriority;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskStatus;
import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaFkScope;
import io.keel.framework.api.annotation.db.NovaForeignKey;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Aufgabe (zentrales Aggregat des Task-Management-Moduls). */
@NovaTable(name = "TASK")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class Task extends NovaEntity<Task> {

  private static final long serialVersionUID = 1L;

  /** Aufgabentitel. */
  @NovaColumn(name = "TITLE", nullable = false, length = 500)
  private String title;

  /**
   * Optionale Beschreibung (DB: {@code nvarchar(max)} laut V1). {@code length = 4000} statt des
   * 255-Defaults, damit die implizite KSF-HasMaxLengthRule normale, laengere Texte nicht abweist.
   * 4000 ist die groesste DDL-valide numerische NVARCHAR-Laenge (SQL Server lehnt {@code NVARCHAR(n)}
   * mit n&gt;4000 ab; {@code NVARCHAR(MAX)} kann der KSF-Schema-Generator nicht ausdruecken, daher
   * KEIN {@code Integer.MAX_VALUE}). Die DB-Spalte bleibt {@code nvarchar(max)}; die App-Validierung
   * deckelt pragmatisch bei 4000.
   */
  @NovaColumn(name = "DESCRIPTION", nullable = true, length = 4000)
  private String description;

  /** FK auf {@code DIC_TASK_STATUS} (global). */
  @NovaColumn(
      name = "TASK_STATUS_ID",
      nullable = false,
      foreignKey = @NovaForeignKey(referencedEntity = TaskStatus.class))
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long taskStatusId;

  /** FK auf {@code DIC_TASK_PRIORITY} (global). */
  @NovaColumn(
      name = "PRIORITY_ID",
      nullable = false,
      foreignKey = @NovaForeignKey(referencedEntity = TaskPriority.class))
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long priorityId;

  /** FK auf {@code DIC_TASK_DIRECTION} (global). */
  @NovaColumn(
      name = "DIRECTION_ID",
      nullable = false,
      foreignKey = @NovaForeignKey(referencedEntity = TaskDirection.class))
  @NovaFkScope(mode = NovaFkScope.Mode.GLOBAL)
  private Long directionId;

  /** Mandant, der die Aufgabe urspruenglich erstellt hat. */
  @NovaColumn(name = "ORIGIN_TENANT_ID", nullable = false)
  private Long originTenantId;

  /** Optionales Startdatum. */
  @NovaColumn(name = "START_DATE", nullable = true)
  private LocalDate startDate;

  /** Optionales Faelligkeitsdatum. */
  @NovaColumn(name = "DUE_DATE", nullable = true)
  private LocalDate dueDate;

  /** Zeitpunkt der letzten Aktivitaet (Kommentar, Statuswechsel etc.). */
  @NovaColumn(name = "LAST_ACTIVITY_AT", nullable = false)
  private LocalDateTime lastActivityAt;

  /** Anzahl der Kommentare (denormalisiert, Default 0). */
  @NovaColumn(name = "COMMENT_COUNT", nullable = false)
  private int commentCount = 0;

  /** Quellmodul (z.B. "production"), das die Aufgabe ausgeloest hat. */
  @NovaColumn(name = "SOURCE_MODULE", nullable = true, length = 100)
  private String sourceModule;

  /** Entity-Typ im Quellmodul. */
  @NovaColumn(name = "SOURCE_ENTITY_TYPE", nullable = true, length = 100)
  private String sourceEntityType;

  /** UUID der Quell-Entity. */
  @NovaColumn(name = "SOURCE_ENTITY_REF", nullable = true)
  private UUID sourceEntityRef;
}
