package de.brasi.nova.taskmanagement.refentity;

import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.NovaRepository;
import java.util.Optional;

/**
 * Read-only-Repository fuer {@link User} (PROD_BENUTZER). Tenant-scoped: {@code :tenantId} wird
 * vom KSF-Framework automatisch gebunden (kein tenantId-Parameter in der Methode). Kein Scaffold;
 * keine Schreibpfade.
 */
public interface UserReadRepository extends NovaRepository<User> {

  /**
   * Sucht einen Benutzer anhand seines Login-Namens im aktuellen Mandanten-Kontext. {@code
   * :tenantId} wird automatisch vom Framework gebunden.
   *
   * @param loginName der gesuchte Login-Name
   * @return den gefundenen Benutzer oder leer
   */
  @NovaQuery(
      value =
          "SELECT * FROM PROD_BENUTZER WHERE LOGIN_NAME = :loginName AND TENANT_ID = :tenantId"
              + " AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "SELECT * FROM [dbo].[PROD_BENUTZER] WHERE LOGIN_NAME = :loginName"
              + " AND TENANT_ID = :tenantId AND ENTITY_STATUS = 'ACTIVE'")
  Optional<User> findByLoginName(String loginName);
}
