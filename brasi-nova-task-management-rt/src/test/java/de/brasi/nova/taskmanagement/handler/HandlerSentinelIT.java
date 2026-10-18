package de.brasi.nova.taskmanagement.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handler-Sentinel-IT (arch-v2 §7): beweist zwei Eigenschaften des Scaffold-Ergebnisses:
 *
 * <p><b>(1) POSITIV — Scaffold lief:</b> Mindestens ein generiertes Persistenz-Artefakt existiert
 * auf dem Classpath. Geprüft via {@code Class.forName("de.brasi.nova.taskmanagement.repository.
 * BaseTaskParticipantRepository")} — diese Klasse wird ausschließlich vom Scaffold erzeugt und
 * muss nach einer korrekten Regen-Phase im {@code brasi-nova-task-management-api}-Modul vorhanden
 * sein. Wenn sie fehlt, ist der -api-Stand veraltet oder der Scaffold-Lauf wurde übersprungen.
 *
 * <p><b>(2) NEGATIV / Routing — keine generischen CRUD-Routen:</b> Im gesamten Anwendungspaket
 * {@code de.brasi.nova.taskmanagement} darf kein vom Scaffold generierter
 * {@link RestController} existieren. Erlaubt sind ausschliesslich handgeschriebene Facade-
 * Controller (in der Allowlist) und der IT-Probe-Controller. Geprüft via Springs
 * {@link ClassPathScanningCandidateComponentProvider} gegen das Basis-Paket.
 *
 * <p>Da das Scaffold-Plugin gemäß arch-v2 §7 ausschließlich die Targets
 * Repository / Model / QClass / QueryProvider / ServiceProviders / Summary / Validator erzeugt
 * (kein Controller / Hateoas / Assembler / LinkSpec), erwarten wir KEINEN generierten
 * {@link RestController}. Die gesamte REST-Oberfläche sind handgeschriebene Facade-Controller.
 *
 * <p>Kein Spring-Context, kein DB-Zugang benötigt.
 */
class HandlerSentinelIT {

  /** IT-Probe-Controller (nur fuer Tests). */
  private static final String WHOAMI_PROBE_CONTROLLER =
      "de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController";

  /**
   * Handgeschriebene Facade-Controller (arch-v2 §7): explizit erlaubt, werden NICHT vom Scaffold
   * generiert. Jeder neue Facade-Controller muss hier eingetragen werden.
   */
  private static final java.util.Set<String> ALLOWED_FACADE_CONTROLLERS =
      java.util.Set.of(
          WHOAMI_PROBE_CONTROLLER,
          "de.brasi.nova.taskmanagement.task.TaskFacadeController",
          "de.brasi.nova.taskmanagement.participant.AssigneeController",
          "de.brasi.nova.taskmanagement.participant.UserStateController",
          "de.brasi.nova.taskmanagement.participant.ParticipantSearchController",
          "de.brasi.nova.taskmanagement.comment.CommentController",
          "de.brasi.nova.taskmanagement.notification.NotificationController",
          "de.brasi.nova.taskmanagement.dictionary.DictionaryCatalogController",
          "de.brasi.nova.taskmanagement.tag.TagController",
          "de.brasi.nova.taskmanagement.inbox.InboxSnapshotController",
          "de.brasi.nova.taskmanagement.push.WebSocketTicketController");

  /** Basis-Paket der gesamten Anwendung — wird vollständig gescannt. */
  private static final String APP_BASE_PACKAGE = "de.brasi.nova.taskmanagement";

  // ---- (1) POSITIV: Scaffold-Persistenz-Artefakt vorhanden ----------------------------------------

  @Test
  @DisplayName(
      "scaffold_artefakt_vorhanden: generiertes Repository-Interface lädt via Class.forName")
  void scaffold_artefakt_vorhanden() {
    assertThatCode(
            () ->
                Class.forName(
                    "de.brasi.nova.taskmanagement.repository.BaseTaskParticipantRepository"))
        .as(
            "BaseTaskParticipantRepository muss auf dem Classpath sein (generiert durch "
                + "ksf-scaffold-plugin; -api-Modul veraltet oder Scaffold-Lauf fehlt wenn"
                + " ClassNotFoundException)")
        .doesNotThrowAnyException();
  }

  // ---- (2) NEGATIV: kein @RestController im Anwendungspaket (außer bekanntem Probe-Controller) -----

  @Test
  @DisplayName(
      "scaffold_kein_restcontroller: kein @RestController außer WhoAmIProbeController im"
          + " Anwendungspaket")
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
    scanner
        .findCandidateComponents(APP_BASE_PACKAGE)
        .forEach(bd -> found.add(bd.getBeanClassName()));

    // Erlaubt: handgeschriebene Facade-Controller + IT-Probe-Controller (ALLOWED_FACADE_CONTROLLERS)
    // Verboten: alle anderen (= vom Scaffold generierte oder unbekannte Controller)
    List<String> forbidden =
        found.stream()
            .filter(name -> !ALLOWED_FACADE_CONTROLLERS.contains(name))
            .toList();

    assertThat(forbidden)
        .as(
            "Scaffold darf KEINE @RestController-Klassen im Anwendungspaket %s erzeugen "
                + "(arch-v2 §7: nur Repository/Model/QClass/QueryProvider/ServiceProviders/"
                + "Summary/Validator). Erlaubte Controller (handgeschrieben): %s."
                + " Verbotene Treffer: %s",
            APP_BASE_PACKAGE,
            ALLOWED_FACADE_CONTROLLERS,
            forbidden)
        .isEmpty();
  }
}
