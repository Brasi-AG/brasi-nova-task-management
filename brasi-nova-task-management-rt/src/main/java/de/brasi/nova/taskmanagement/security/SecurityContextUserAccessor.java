package de.brasi.nova.taskmanagement.security;

import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.ProfileDTO;
import io.keel.framework.api.security.user.UserAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Liefert den aktuell authentifizierten Benutzer aus dem Spring-{@link SecurityContextHolder}.
 *
 * <p>Der Principal wird vom ksf-{@link io.keel.framework.security.web.filter.JwtAuthenticationFilter}
 * als {@link ProfileDTO} aus dem {@code profile}-Claim gesetzt. {@code AuthUser.id()} ist die
 * {@code PROD_BENUTZER.EXTERNALID} in diesem JWT/Profile-Claim-Pfad.
 */
@Component
public class SecurityContextUserAccessor implements UserAccessor {

  @Override
  public AuthUser requireCurrentUser() {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new BusinessRuleException("Kein authentifizierter Benutzer im SecurityContext.");
    }

    final Object principal = authentication.getPrincipal();
    if (principal instanceof ProfileDTO profile && profile.getUserId() != null) {
      return new AuthUser(profile.getUserId(), profile.getUsername());
    }

    throw new BusinessRuleException(
        "Aktueller Principal traegt kein ProfileDTO mit userId (principal="
            + (principal == null ? "null" : principal.getClass().getName())
            + ").");
  }
}
