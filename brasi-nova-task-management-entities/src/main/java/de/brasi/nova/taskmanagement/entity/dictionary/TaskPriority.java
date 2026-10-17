package de.brasi.nova.taskmanagement.entity.dictionary;

import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaDictionaryEntry;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Aufgaben-Prioritaet (Dictionary). Stabiler Resolver-Key: {@code code}. */
@NovaTable(name = "DIC_TASK_PRIORITY")
@Getter
@Setter
@NoArgsConstructor
public class TaskPriority extends NovaDictionaryEntry<TaskPriority> {

  private static final long serialVersionUID = 1L;

  /** Technischer Resolver-Key (unveraenderlich). Wertemenge: LOW, NORMAL, HIGH, URGENT. */
  @NovaColumn(name = "CODE", nullable = false, length = 50)
  private String code;

  /** Anzeigereihenfolge. */
  @NovaColumn(name = "SORT_ORDER")
  private Integer sortOrder;
}
