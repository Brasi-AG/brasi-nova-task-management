package de.brasi.nova.taskmanagement.affordanceprobe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Test-only Enclosing fuer eine NON-STATIC inner {@link Controller}-Fixture (Codex-Re-Gate-BLOCKER
 * R4). Ein non-static inner Controller-Typ hat {@code isIndependent()==false} und wird vom
 * Spring-Standard-{@code isCandidateComponent(AnnotatedBeanDefinition)} verworfen - kann aber per
 * {@code @Bean} als MVC-Handler registriert werden. Der AC4-Scan muss ihn dennoch erfassen.
 *
 * <p>Liegt im vom Produktions-Scan ausgeschlossenen {@code affordanceprobe}-Paket; der Test {@code
 * AffordanceSentinelsTest#ac4_scan_erfasst_non_static_inner_controller()} scannt sie gezielt.
 */
public class NonStaticInnerControllerFixture {

  /** Bewusst NON-STATIC inner (isIndependent()==false), ungegateter Schreib-Endpunkt. */
  @Controller
  @ConditionalOnProperty(
      name = "task-management.test.affordance-probe.enabled",
      havingValue = "true")
  @RequestMapping("/probe/inner-ungated")
  public class InnerUngatedController {

    /** Ungegateter Schreib-Endpunkt (bewusst ohne Autorisierung). */
    @PostMapping("/write")
    @ResponseBody
    public String write() {
      return "ok";
    }
  }
}
