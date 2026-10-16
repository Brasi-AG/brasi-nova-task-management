package de.brasi.nova.taskmanagement.security.it;

import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only Probe-Controller fuer den Security-Bootstrap-IT. Gibt Tenant-ID (interne Long-ID aus
 * TenantContextHolder) und User-UUID (AuthUser.id() aus SecurityContext) als JSON zurueck, damit
 * der IT beweisen kann, dass die KSF-Filterchain Tenant und Benutzer korrekt aufgeloest hat.
 */
@RestController
@RequestMapping("/it/whoami")
public class WhoAmIProbeController {

  private final SecurityContextUserAccessor userAccessor;

  public WhoAmIProbeController(final SecurityContextUserAccessor userAccessor) {
    this.userAccessor = userAccessor;
  }

  /**
   * Gibt den aufgeloesten Akteur zurueck: Tenant-interne-ID und User-EXTERNALID als String.
   *
   * @return Map mit "tenantId" (Long) und "userId" (UUID als String)
   */
  @GetMapping
  public Map<String, Object> whoAmI() {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return Map.of(
        "tenantId", actor.tenant().id(),
        "tenantUuid", actor.tenant().uuid().toString(),
        "userId", actor.user().id().toString(),
        "loginName", actor.user().loginname());
  }
}
