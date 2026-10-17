package de.brasi.nova.taskmanagement.entity.dictionary;

import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaDictionaryEntry;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Benachrichtigungs-Schweregrad (Dictionary). Stabiler Resolver-Key: {@code code}. */
@NovaTable(name = "DIC_NOTIFICATION_SEVERITY")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSeverity extends NovaDictionaryEntry<NotificationSeverity> {

  private static final long serialVersionUID = 1L;

  /** Technischer Resolver-Key (unveraenderlich). Wertemenge: INFO, SUCCESS, WARN, ERROR. */
  @NovaColumn(name = "CODE", nullable = false, length = 50)
  private String code;
}
