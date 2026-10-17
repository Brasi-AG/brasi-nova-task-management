package de.brasi.nova.taskmanagement.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handler-Sentinel-IT (arch-v2 §7): beweist strukturell, dass das Scaffold-Plugin KEINE generischen
 * CRUD-Routen fuer die Domain-Entities erzeugt hat.
 *
 * <p>Vorgehen: Classpath-Scan der generierten Scaffold-Ausgabepakete (controller, assembler, link)
 * via Springs {@link ClassPathScanningCandidateComponentProvider} + Pruefung, dass keine einzige
 * Klasse mit {@link RestController} annotiert ist. Da das pom.xml-Scaffold nur die Targets
 * Repository / Model / QClass / QueryProvider / Service / ServiceProviders / Summary / Validator
 * erzeugt (kein Controller / Hateoas / Assembler / LinkSpec), sind diese Pakete schlicht nicht
 * generiert — der Scan findet null Treffer.
 *
 * <p>Abgrenzung: Das Scan-Muster schliessen nur die vom Scaffold bewirtschafteten Pakete ein;
 * manuelle IT-Probe-Controller (z.B. {@code security.it.WhoAmIProbeController}) liegen
 * ausserhalb dieses Scan-Bereichs und werden bewusst nicht geprueft.
 *
 * <p>Kein Spring-Context, kein DB-Zugang benoetigt.
 */
class HandlerSentinelIT {

  /**
   * Pakete, in denen Scaffold normalerweise Controller / Assembler / Links ablegt.
   * Keines dieser Pakete darf eine {@link RestController}-annotierte Klasse enthalten.
   */
  private static final List<String> SCAFFOLD_CONTROLLER_PACKAGES =
      List.of(
          "de.brasi.nova.taskmanagement.controller",
          "de.brasi.nova.taskmanagement.assembler",
          "de.brasi.nova.taskmanagement.link");

  @Test
  @DisplayName("scaffold_kein_restcontroller: kein @RestController in generierten Scaffold-Paketen")
  void scaffold_kein_restcontroller() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
            // Alle Typen akzeptieren (abstrakt, Interface, konkret)
            return true;
          }
        };
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class, true, true));

    List<String> found = new ArrayList<>();
    for (String pkg : SCAFFOLD_CONTROLLER_PACKAGES) {
      scanner.findCandidateComponents(pkg).forEach(bd -> found.add(bd.getBeanClassName()));
    }

    assertThat(found)
        .as(
            "Scaffold darf KEINE @RestController-Klassen in %s erzeugen (arch-v2 §7 "
                + "Scaffold-Policy: nur Repository/Model/QClass/QueryProvider/Service/"
                + "ServiceProviders/Summary/Validator). Gefundene Klassen: %s",
            SCAFFOLD_CONTROLLER_PACKAGES,
            found)
        .isEmpty();
  }
}
