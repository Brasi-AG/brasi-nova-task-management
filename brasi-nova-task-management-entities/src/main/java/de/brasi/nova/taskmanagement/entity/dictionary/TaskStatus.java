package de.brasi.nova.taskmanagement.entity.dictionary;

import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaDictionaryEntry;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Aufgaben-Status (Dictionary). Stabiler Resolver-Key: {@code code}. */
@NovaTable(name = "DIC_TASK_STATUS")
@Getter
@Setter
@NoArgsConstructor
public class TaskStatus extends NovaDictionaryEntry<TaskStatus> {

  private static final long serialVersionUID = 1L;

  /** Technischer Resolver-Key (unveraenderlich). Wertemenge: OPEN, DONE, CANCELLED. */
  @NovaColumn(name = "CODE", nullable = false, length = 50)
  private String code;
}
