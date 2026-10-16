package de.brasi.nova.taskmanagement.refentity.query;

import io.keel.framework.api.query.path.StringProperty;
import io.keel.framework.persistence.query.path.NovaPropertyFactory;
import io.keel.framework.persistence.query.path.QNovaEntity;

/**
 * Statisches Metamodell fuer die Referenz-Entity {@link de.brasi.nova.taskmanagement.refentity.User}.
 * Wird vom KSF-Framework fuer Query-Pfad-Aufloesung benoetigt.
 */
public final class QUser extends QNovaEntity {

  /** Vollstaendiger Name. */
  public final StringProperty fullName = NovaPropertyFactory.createString("fullName");

  /** Login-Name (E-Mail-Adresse). */
  public final StringProperty loginName = NovaPropertyFactory.createString("loginName");

  /** Singleton-Instanz. */
  public static final QUser user = new QUser();
}
