package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.entity.enums.AttachmentTariff;
import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tarif-Grenze fuer Anhaenge je Mandant. Hoechstens eine aktive Zeile je Mandant (gefilterter
 * Unique-Index in V14); ohne Zeile gilt der Tarif STANDARD.
 */
@NovaTable(name = "TASK_ANHANG_GRENZE")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskAttachmentLimit extends NovaEntity<TaskAttachmentLimit> {

  private static final long serialVersionUID = 1L;

  /** Tarif des Mandanten (STANDARD | EXPERT | ENTERPRISE). Von KSF nativ als String persistiert. */
  @NovaColumn(name = "TARIF", nullable = false)
  private AttachmentTariff tariff;

  /**
   * Optionale eigene Obergrenze je Datei in Bytes; {@code null} = Obergrenze der Tarifspanne.
   * Werte ausserhalb der Spanne klemmt der Dienst auf die Spanne.
   */
  @NovaColumn(name = "MAX_DATEI_BYTES", nullable = true)
  private Long maxFileBytes;
}
