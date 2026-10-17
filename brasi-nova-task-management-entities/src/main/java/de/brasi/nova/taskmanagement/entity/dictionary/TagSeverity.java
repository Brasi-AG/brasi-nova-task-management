package de.brasi.nova.taskmanagement.entity.dictionary;

import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaDictionaryEntry;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Tag-Schweregrad (Dictionary). Stabiler Resolver-Key: {@code code}. */
@NovaTable(name = "DIC_TAG_SEVERITY")
@Getter
@Setter
@NoArgsConstructor
public class TagSeverity extends NovaDictionaryEntry<TagSeverity> {

  private static final long serialVersionUID = 1L;

  /**
   * Technischer Resolver-Key (unveraenderlich). Wertemenge: PRIMARY, SUCCESS, WARN, DANGER,
   * SECONDARY.
   */
  @NovaColumn(name = "CODE", nullable = false, length = 50)
  private String code;
}
