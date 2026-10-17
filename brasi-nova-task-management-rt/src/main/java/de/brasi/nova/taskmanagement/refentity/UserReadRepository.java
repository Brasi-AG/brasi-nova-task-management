package de.brasi.nova.taskmanagement.refentity;

import io.keel.framework.api.annotation.db.NovaQuery;
import io.keel.framework.api.repository.NovaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

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

  /**
   * Erste Seite der Teilnehmer-Suche (kein Keyset-Praedikat).
   *
   * <p>Gibt maximal {@code limit} Eintraege zurueck. LIKE-Pattern wird nur angewendet, wenn
   * {@code like} nicht null ist. {@code :tenantId} wird automatisch gebunden.
   *
   * @param like LIKE-Pattern (z.B. "%test%"; null = kein Namensfilter)
   * @param limit maximale Anzahl Eintraege (limit+1 uebergeben fuer hasMore-Pruefung)
   * @return Liste der Benutzer (geordnet nach EXTERNALID ASC)
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) * FROM PROD_BENUTZER "
              + "WHERE TENANT_ID = :tenantId "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "AND (:like IS NULL OR LOGIN_NAME LIKE :like OR VOLLER_NAME LIKE :like) "
              + "ORDER BY EXTERNALID ASC",
      mssql =
          "SELECT TOP (:limit) * FROM [dbo].[PROD_BENUTZER] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "AND (:like IS NULL OR LOGIN_NAME LIKE :like OR VOLLER_NAME LIKE :like) "
              + "ORDER BY EXTERNALID ASC")
  List<User> findFirstPage(@Param("like") String like, @Param("limit") int limit);

  /**
   * Folgeseite der Teilnehmer-Suche (Keyset-Praedikat: EXTERNALID > afterExternalId).
   *
   * <p>Gibt maximal {@code limit} Eintraege zurueck, die nach {@code afterExternalId} liegen
   * (MSSQL {@code uniqueidentifier}-Ordnung ist konsistent). {@code :tenantId} automatisch gebunden.
   *
   * @param like            LIKE-Pattern (null = kein Namensfilter)
   * @param afterExternalId Keyset-Position (letzte EXTERNALID der vorherigen Seite)
   * @param limit           maximale Anzahl Eintraege
   * @return Liste der Benutzer (geordnet nach EXTERNALID ASC)
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) * FROM PROD_BENUTZER "
              + "WHERE TENANT_ID = :tenantId "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "AND EXTERNALID > :afterExternalId "
              + "AND (:like IS NULL OR LOGIN_NAME LIKE :like OR VOLLER_NAME LIKE :like) "
              + "ORDER BY EXTERNALID ASC",
      mssql =
          "SELECT TOP (:limit) * FROM [dbo].[PROD_BENUTZER] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "AND EXTERNALID > :afterExternalId "
              + "AND (:like IS NULL OR LOGIN_NAME LIKE :like OR VOLLER_NAME LIKE :like) "
              + "ORDER BY EXTERNALID ASC")
  List<User> findPage(
      @Param("like") String like,
      @Param("afterExternalId") UUID afterExternalId,
      @Param("limit") int limit);
}
