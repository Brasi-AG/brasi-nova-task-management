package de.brasi.nova.taskmanagement.affordanceprobe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Test-only Negativ-Fixture fuer den AC4-Invertierbarkeits-Beweis (PLAN #141, IMPL-Gate-Auflage 1).
 *
 * <p>Ein PLAIN Spring-MVC {@link Controller} (NICHT {@code @RestController}) mit einem ungegateten
 * {@link PostMapping}-Schreib-Endpunkt OHNE {@code @RequiresPermission}, Guard-Delegation oder
 * Allowlist. Spring registriert einen solchen Handler regulaer (ein {@code @Controller} IST ein
 * Handler), der fruehere AC4-Sentinel-Scan ({@code @RestController}-only) uebersah ihn jedoch ->
 * der Sentinel blieb trotz ungegatetem Schreib-Endpunkt gruen (Codex-Re-Gate-BLOCKER: Discovery
 * umgehbar).
 *
 * <p>Wird der gehaertete Sentinel-Discovery-Kern mit dieser Klasse gefuettert, MUSS der Handler als
 * {@code uncategorized} (ungegatet) erkannt werden -> empirischer Beweis der Invertierbarkeit.
 *
 * <p>Liegt bewusst im test-only, vom Produktions-Scan AUSGESCHLOSSenen Paket {@code
 * affordanceprobe} (siehe {@code AffordanceSentinelsTest#EXCLUDED_PACKAGE_PREFIXES}), damit der
 * reale AC4-Lauf diese Negativ-Fixture NICHT sieht (sonst waere der Sentinel dauerhaft rot). Der
 * Invertierbarkeits-Test uebergibt sie der Discovery EXPLIZIT.
 *
 * <p>Zusaetzlich traegt sie ein {@link ConditionalOnProperty} mit {@code havingValue="true"} auf
 * eine Property, die im Test NICHT gesetzt ist ({@code matchIfMissing=false} = Default): sie ist
 * damit eine CONDITIONAL-registrierte Controller-Klasse, deren Bedingung im Testlauf UNERFUELLT
 * ist. Der AC4-Scan muss sie trotzdem erfassen (sonst koennte ein per Property aktivierter,
 * ungegateter Controller in der laufenden App unsichtbar bleiben - Codex-Re-Gate-BLOCKER-2). Das
 * belegt der Test {@code AffordanceSentinelsTest#ac4_scan_ignoriert_conditional_registrierung()}.
 */
@Controller
@ConditionalOnProperty(name = "task-management.test.affordance-probe.enabled", havingValue = "true")
@RequestMapping("/probe/ungated")
public class UngatedPlainMutationController {

  /** Ungegateter Schreib-Endpunkt (bewusst OHNE Autorisierung) - Negativ-Fall fuer den Sentinel. */
  @PostMapping("/write")
  @ResponseBody
  public String write() {
    return "ok";
  }
}
