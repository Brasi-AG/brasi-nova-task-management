package de.brasi.nova.taskmanagement.affordanceprobe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Test-only Probe-Controller fuer {@code MultipartErrorAdviceTest} (Intake #152, Task A7): jeder
 * Handler wirft die Ausnahme, die Spring beim Lesen eines Multipart-Requests meldet.
 *
 * <p>Warum annotiert und in diesem Paket: Seit Spring 6 registriert {@code
 * MockMvcBuilders.standaloneSetup} die uebergebenen Controller als Beans und laesst {@code
 * RequestMappingHandlerMapping#isHandler} entscheiden; ein Typ ohne {@code @Controller} bzw.
 * Typ-{@code @RequestMapping} bekommt keine Mappings (404). Das Paket {@code affordanceprobe} ist
 * von den Sentinels ausgenommen ({@code AffordanceSentinelsTest#EXCLUDED_PACKAGE_PREFIXES}, {@code
 * HandlerSentinelIT#AFFORDANCE_PROBE_PACKAGE}); {@code LockConflictTranslationAspectTest}
 * ueberspringt Test-Klassen.
 *
 * <p>{@link ConditionalOnProperty} auf eine eigene, nie gesetzte Property: der Waechter {@code
 * TestControllerGuardTest} verlangt die Bedingung fuer jeden Test-Controller, und so registriert
 * kein Komponenten-Scan den Probe (auch nicht {@code ProbeWebContext}, dessen ITs die Property der
 * uebrigen Fixtures setzen). Das eigenstaendige MockMvc des Tests registriert die Instanz direkt.
 */
@RestController
@ConditionalOnProperty(name = "task-management.test.multipart-probe.enabled", havingValue = "true")
public class MultipartErrorProbeController {

  /** Groesse in der Ausnahme (nur Inhalt der Ausnahme, die Antwort nennt die Konfiguration). */
  private static final long MAX_UPLOAD_BYTES = DataSize.ofMegabytes(15).toBytes();

  /** Wirft die Ausnahme einer zu grossen Datei. */
  @PostMapping("/probe/too-large")
  public void tooLarge() {
    throw new MaxUploadSizeExceededException(MAX_UPLOAD_BYTES);
  }

  /**
   * Wirft die Ausnahme eines fehlenden Teils {@code files}.
   *
   * @throws MissingServletRequestPartException immer
   */
  @PostMapping("/probe/missing-part")
  public void missingPart() throws MissingServletRequestPartException {
    throw new MissingServletRequestPartException("files");
  }

  /** Wirft die Ausnahme eines unlesbaren Multipart-Requests. */
  @PostMapping("/probe/broken")
  public void broken() {
    throw new MultipartException("Multipart-Stream unvollstaendig");
  }
}
