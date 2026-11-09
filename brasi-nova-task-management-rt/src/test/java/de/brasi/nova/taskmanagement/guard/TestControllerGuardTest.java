package de.brasi.nova.taskmanagement.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;

/**
 * Waechter: Test-only Controller unter {@code src/test} im Produktions-Basispaket duerfen bei einem
 * vollen ComponentScan nicht still registriert werden. Jede {@code @Controller}/{@code
 * @RestController}-Klasse aus den Test-Klassen muss {@code @ConditionalOnProperty} tragen oder in
 * der begruendeten Allowlist stehen.
 */
class TestControllerGuardTest {

  private static final String BASE_PACKAGE = "de.brasi.nova.taskmanagement";

  /**
   * Begruendete Allowlist: {@code WhoAmIProbeController} wird von den Security-ITs explizit per
   * {@code @Import} geladen und ist in den Produktions-Scans der ITs per Regex-Filter ausgeschlossen.
   */
  private static final Set<String> ALLOWLIST =
      Set.of("de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController");

  @Test
  @DisplayName("Alle Test-Controller tragen @ConditionalOnProperty (oder stehen in der Allowlist)")
  void testControllersAreConditional() throws Exception {
    final ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(final AnnotatedBeanDefinition bd) {
            return true; // auch interfaces / non-static inner
          }

          @Override
          protected boolean isCandidateComponent(
              final org.springframework.core.type.classreading.MetadataReader reader)
              throws java.io.IOException {
            // nur der Typ-Filter, KEINE @Conditional-Auswertung (sonst faelt der Fall weg)
            return new AnnotationTypeFilter(Controller.class)
                .match(reader, getMetadataReaderFactory());
          }
        };
    scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
    final String testClasses =
        TestControllerGuardTest.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toURI()
            .getPath();

    final List<String> violations = new ArrayList<>();
    final List<String> found = new ArrayList<>();
    for (final BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
      final String name = bd.getBeanClassName();
      final Class<?> type = ClassUtils.forName(name, getClass().getClassLoader());
      final String location = type.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
      if (!location.equals(testClasses)) {
        continue; // Produktionsklasse
      }
      found.add(name);
      if (!ALLOWLIST.contains(name) && !type.isAnnotationPresent(ConditionalOnProperty.class)) {
        violations.add(name);
      }
    }
    // Nicht-blind-Beweis ueber bekannte, dauerhaft vorhandene Test-Controller statt einer Zaehlschwelle
    // (die Slice-0-Probes sind separat entfernbar und duerfen den Waechter nicht tragen).
    assertThat(found)
        .as("Scan findet bekannte Test-Controller (Waechter nicht blind)")
        .contains(
            "de.brasi.nova.taskmanagement.affordanceprobe.UngatedPlainMutationController",
            "de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController");
    assertThat(violations)
        .as("Test-Controller ohne @ConditionalOnProperty (und nicht in der Allowlist)")
        .isEmpty();
  }
}
