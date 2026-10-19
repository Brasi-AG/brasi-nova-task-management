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
 * Monotoner Generationszaehler je (Mandant, Benutzer) fuer die Inbox-Snapshot-Rejection
 * (Finding 2 / Option B).
 *
 * <p>Zweck: Destruktive Membership-Mutationen (UNSUBSCRIBED_AT/EXIT_KIND/EXIT_AT ueberschreiben)
 * brechen die asOf-Snapshot-Invariante der Keyset-Paginierung. Ein alter Cursor traegt die
 * {@code GENERATION}, unter der sein Snapshot erzeugt wurde; weicht die aktuelle Generation ab, wird
 * der Folge-Read abgelehnt (HTTP 410), statt still inkonsistente Seiten zu liefern. Granularitaet
 * bewusst pro (TENANT_ID, USER_REF) — die Inbox ist user-zentriert.
 */
@NovaTable(name = "TASK_INBOX_GENERATION")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskInboxGeneration extends NovaEntity<TaskInboxGeneration> {

  private static final long serialVersionUID = 1L;

  /** UUID des Benutzers (PROD_BENUTZER.EXTERNALID). */
  @NovaColumn(name = "USER_REF", nullable = false)
  private UUID userRef;

  /** Monotoner Zaehler; +1 je destruktiver Membership-Mutation dieses Benutzers (Start 0). */
  @NovaColumn(name = "GENERATION", nullable = false)
  private long generation;
}
