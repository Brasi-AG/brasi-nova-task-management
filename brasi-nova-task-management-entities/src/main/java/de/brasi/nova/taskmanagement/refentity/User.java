package de.brasi.nova.taskmanagement.refentity;

import de.brasi.nova.taskmanagement.refentity.query.QUser;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaId;
import io.keel.framework.api.annotation.db.NovaQClass;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.enums.IdGenerationStrategy;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.model.descriptors.DBEntityDescriptor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Read-only-Mapping der gemeinsamen Referenztabelle PROD_BENUTZER (Systembenutzer). ID kommt vom
 * Core-Service (MANUAL). Wird fuer Teilnehmer-Suche und Assignee-UUID-Validierung verwendet. Kein
 * Scaffold; keine Schreibpfade.
 */
@NovaTable(name = "PROD_BENUTZER")
@NovaQClass(QUser.class)
@Getter
@Setter
@NoArgsConstructor
public class User extends NovaEntity<User> {

  private static final long serialVersionUID = 1L;

  /** Interne ID, kommt vom Core-Service (MANUAL). */
  @NovaId(strategy = IdGenerationStrategy.MANUAL)
  @NovaColumn(name = DBEntityDescriptor.ID_FIELD, nullable = false)
  protected Long id;

  /** Vollstaendiger Anzeigename (PROD_BENUTZER.VOLLER_NAME). */
  @NovaColumn(name = "VOLLER_NAME")
  private String fullName;

  /** Login-Name/Kennung (PROD_BENUTZER.LOGIN_NAME). */
  @NovaColumn(name = "LOGIN_NAME")
  private String loginName;
}
