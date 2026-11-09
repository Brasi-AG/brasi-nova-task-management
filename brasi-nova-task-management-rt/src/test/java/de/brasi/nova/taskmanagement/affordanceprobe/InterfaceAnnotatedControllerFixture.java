package de.brasi.nova.taskmanagement.affordanceprobe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.security.permission.RequiresPermission;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Test-only Fixture (Codex-Re-Gate-BLOCKER R5): die Controller-/Mapping-Annotationen liegen auf
 * einem INTERFACE; die konkrete Impl-Klasse traegt KEINE eigenen Annotationen und wuerde
 * typischerweise per {@code @Bean} registriert. Spring MVC erkennt die Impl ueber die
 * Interface-Annotation als Handler ({@code AnnotatedElementUtils.hasAnnotation} durchsucht
 * Interfaces + Superklassen). Der AC4-Scan/Discovery muss daher die KONKRETE Impl erfassen (nicht
 * nur das annotierte Interface, das als nicht-konkret verworfen wird).
 *
 * <p>Liegt im vom Produktions-Scan ausgeschlossenen {@code affordanceprobe}-Paket; der Test {@code
 * AffordanceSentinelsTest#ac4_discovery_erfasst_interface_annotierten_controller()} speist sie
 * gezielt ein.
 */
public final class InterfaceAnnotatedControllerFixture {

  private InterfaceAnnotatedControllerFixture() {}

  /**
   * Controller-/Mapping-Annotationen auf dem INTERFACE. Die Schreib-Methode traegt hier auch
   * {@code @RequiresPermission} AUF DER INTERFACE-METHODE (Codex-Re-Gate R7): KSF enforced das unter
   * CGLIB-Proxys NICHT (es liest die konkrete Methode), daher MUSS der Sentinel diesen Endpunkt als
   * ungegatet (uncategorized) werten - ein {@code @RequiresPermission} auf der Interface-Methode
   * zaehlt NICHT als Gatung.
   */
  @Controller
  @ConditionalOnProperty(name = "task-management.test.affordance-probe.enabled", havingValue = "true")
  @RequestMapping("/probe/iface-ungated")
  public interface AnnotatedControllerApi {

    /** Schreib-Endpunkt mit @RequiresPermission NUR auf der Interface-Methode (KSF enforced es nicht). */
    @PostMapping("/write")
    @RequiresPermission(TaskManagementPermissions.TASK_WRITE)
    @ResponseBody
    String write();
  }

  /** Konkrete Impl OHNE eigene Annotationen - Handler-Eigenschaft nur ueber das Interface. */
  public static final class AnnotatedInterfaceControllerImpl implements AnnotatedControllerApi {
    @Override
    public String write() {
      return "ok";
    }
  }
}
