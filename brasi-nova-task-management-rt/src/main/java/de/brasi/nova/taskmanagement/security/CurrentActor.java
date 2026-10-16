package de.brasi.nova.taskmanagement.security;

import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import java.util.Objects;

/**
 * Buendelt den aktuellen Mandanten-Kontext und den authentifizierten Benutzer fuer fachliche
 * Operationen. Ermittelt den Tenant aus dem {@link TenantContextHolder} und den Benutzer aus dem
 * {@link UserAccessor}.
 *
 * @param tenant der aktuelle Mandant (nie null)
 * @param user der authentifizierte Benutzer (nie null)
 */
public record CurrentActor(TenantInfo tenant, AuthUser user) {

  public CurrentActor {
    Objects.requireNonNull(tenant, "tenant darf nicht null sein");
    Objects.requireNonNull(user, "user darf nicht null sein");
  }

  /**
   * Ermittelt den aktuellen Akteur aus dem Kontext. Wirft eine {@link BusinessRuleException}, wenn
   * kein Mandant oder kein Benutzer im Kontext vorhanden ist.
   *
   * @param userAccessor der UserAccessor zum Lesen des aktuellen Benutzers
   * @return den aktuellen Akteur
   * @throws BusinessRuleException wenn Mandant oder Benutzer fehlt
   */
  public static CurrentActor require(final UserAccessor userAccessor) {
    final TenantInfo tenant =
        TenantContextHolder.getTenant()
            .orElseThrow(
                () ->
                    new BusinessRuleException(
                        "Kein Mandanten-Kontext im aktuellen Thread vorhanden."));
    final AuthUser user = userAccessor.requireCurrentUser();
    return new CurrentActor(tenant, user);
  }
}
