package de.brasi.nova.taskmanagement.affordanceprobe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import de.brasi.nova.taskmanagement.entity.Task;
import io.keel.framework.api.hateoas.annotation.EnableNovaHateoas;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Test-only Fixture (Codex-Re-Gate R6 / AC7): ein ZWEITER Task-Regel-Owner, dessen {@link
 * EnableNovaHateoas} (nebst {@code @Controller}/{@code @RequestMapping}) auf einem INTERFACE liegt;
 * die konkrete Impl traegt keine eigenen Annotationen. Die KSF-Regelregistrierung liest den Owner
 * hierarchisch (AnnotatedElementUtils, inkl. Interfaces). AC7 muss diesen zweiten Owner je {@code
 * entityClass} mitzaehlen, damit ein Laufzeit-Doppel-Regel-Owner erkannt wird.
 *
 * <p>Liegt im vom Produktions-Scan ausgeschlossenen {@code affordanceprobe}-Paket; der Test {@code
 * AffordanceSentinelsTest#ac7_interface_rule_owner_wird_als_duplikat_erkannt()} speist sie gezielt
 * ein.
 */
public final class InterfaceTaskRuleOwnerFixture {

  private InterfaceTaskRuleOwnerFixture() {}

  /** {@code @EnableNovaHateoas(Task)} auf dem INTERFACE - zweiter, via Interface deklarierter Owner. */
  @Controller
  @ConditionalOnProperty(name = "task-management.test.affordance-probe.enabled", havingValue = "true")
  @RequestMapping("/probe/second-task-owner")
  @EnableNovaHateoas(entityClass = Task.class)
  public interface SecondTaskOwnerApi {

    /** Schreib-Endpunkt (Mapping auf der Interface-Methode). */
    @PostMapping("/write")
    @ResponseBody
    String write();
  }

  /** Konkrete Impl OHNE eigene Annotationen - Owner-Eigenschaft nur ueber das Interface. */
  public static final class SecondTaskOwnerImpl implements SecondTaskOwnerApi {
    @Override
    public String write() {
      return "ok";
    }
  }
}
