package de.brasi.nova.taskmanagement.security.it;

import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only Probe-Controller fuer den Security-Bootstrap-IT.
 *
 * <p>/it/whoami: Gibt Tenant-ID (interne Long-ID aus TenantContextHolder) und User-UUID
 * (AuthUser.id() aus SecurityContext) als JSON zurueck, damit der IT beweisen kann, dass die
 * KSF-Filterchain Tenant und Benutzer korrekt aufgeloest hat.
 *
 * <p>/it/findUser: Sucht einen Benutzer per loginName ueber {@link UserReadRepository} (tenant-
 * scoped: :tenantId wird automatisch an den aktuellen Mandanten-Kontext gebunden). Beweist, dass
 * ein Benutzer aus einem fremden Mandanten im aktuellen Tenant-Kontext NICHT gefunden wird.
 */
@RestController
@RequestMapping("/it")
public class WhoAmIProbeController {

  private final SecurityContextUserAccessor userAccessor;
  private final UserReadRepository userReadRepository;

  public WhoAmIProbeController(
      final SecurityContextUserAccessor userAccessor,
      final UserReadRepository userReadRepository) {
    this.userAccessor = userAccessor;
    this.userReadRepository = userReadRepository;
  }

  /**
   * Gibt den aufgeloesten Akteur zurueck: Tenant-interne-ID und User-EXTERNALID als String.
   *
   * @return Map mit tenantId, tenantUuid, userId, loginName
   */
  @GetMapping("/whoami")
  public Map<String, Object> whoAmI() {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    return Map.of(
        "tenantId", actor.tenant().id(),
        "tenantUuid", actor.tenant().uuid().toString(),
        "userId", actor.user().id().toString(),
        "loginName", actor.user().loginname());
  }

  /**
   * Sucht einen Benutzer per loginName im aktuellen Mandanten-Kontext. Benutzt
   * UserReadRepository.findByLoginName (tenant-scoped: :tenantId auto-gebunden an aktuellen
   * TenantContextHolder). Gibt 200 + loginName zurueck wenn gefunden, 404 wenn nicht.
   *
   * @param loginName der zu suchende Login-Name
   * @return 200 {"found":true,"loginName":"..."} oder 404 {"found":false}
   */
  @GetMapping("/findUser")
  public ResponseEntity<Map<String, Object>> findUser(
      @RequestParam("loginName") final String loginName) {
    return userReadRepository
        .findByLoginName(loginName)
        .map(
            u ->
                ResponseEntity.ok(
                    Map.<String, Object>of("found", true, "loginName", u.getLoginName())))
        .orElseGet(
            () ->
                ResponseEntity.status(404)
                    .body(Map.<String, Object>of("found", false)));
  }
}
