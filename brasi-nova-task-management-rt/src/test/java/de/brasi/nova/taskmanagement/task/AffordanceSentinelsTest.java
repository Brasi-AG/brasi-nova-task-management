package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;

import de.brasi.nova.taskmanagement.BrasiNovaApplication;
import de.brasi.nova.taskmanagement.affordanceprobe.UngatedPlainMutationController;
import io.keel.framework.api.hateoas.annotation.EnableNovaHateoas;
import io.keel.framework.api.security.permission.RequiresPermission;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AbstractTypeHierarchyTraversingFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Stehende Affordance-Invarianten (PLAN #141, maschinell erzwungen) ueber die REALE Controller-
 * Flaeche des {@code brasi-nova-task-management-rt}. Beide Sentinels sind leichtgewichtig (kein
 * voller Spring-Context, keine DB) und werden von spaeteren Slices NUR erweitert, nie aufgeweicht.
 *
 * <ul>
 *   <li><b>AC7 (Regel-Owner-Eindeutigkeit):</b> hoechstens EIN {@link EnableNovaHateoas}-Owner je
 *       {@code entityClass} (Classpath-Scan). Ein zweiter Task-Regel-Owner laesst den Test rot
 *       werden.
 *   <li><b>AC4 (Mutation-Autorisierung, Vollstaendigkeits-Beweis):</b> JEDER Mutations-Endpunkt
 *       (POST/PUT/PATCH/DELETE) faellt in GENAU eine Kategorie: (a) der Handler traegt {@link
 *       RequiresPermission}, (b) der Handler steht in der gepflegten Membership-Guard-Delegationsmap
 *       {@link #MEMBERSHIP_GUARD_DELEGATED}, oder (c) er steht in der expliziten gate-freien
 *       Allowlist {@link #GATE_FREE_ALLOWLIST}. Uncategorisiert -> rot.
 * </ul>
 *
 * <h2>AC4: Discovery deckt die GESAMTE registrierbare Handler-Flaeche ab</h2>
 *
 * <p>Die Mutations-Endpunkte werden strukturell ueber Springs Laufzeit-{@link
 * RequestMappingHandlerMapping} entdeckt (ueber die realen Controller-Bean-Typen aufgebaut, ohne
 * Instanziierung/DB), NICHT ueber Annotations-Namens-Strings. Dadurch wird auch {@code
 * @RequestMapping(method = RequestMethod.POST/PUT/PATCH/DELETE)} erfasst, nicht nur die vier
 * Kurzform-Annotationen ({@code @PostMapping}/...).
 *
 * <p><b>Scope der Invariante (explizit):</b> abgedeckt ist die <b>App-eigene Controller-Flaeche</b>
 * - jeder {@link Controller}-/type-level-{@link RequestMapping}-Typ unterhalb der App-Wurzel {@code
 * de.brasi.nova} (REFLEKTIV aus {@link BrasiNovaApplication}'s {@code @SpringBootApplication(
 * scanBasePackages = ...)} abgeleitet, Fallback Application-Paket; der Sentinel kann so nicht enger
 * scannen als die Anwendung). Framework-/Drittanbieter-Controller AUSSERHALB von {@code
 * de.brasi.nova} (z.B. springdoc {@code /v3/api-docs}, actuator, KSF-interne Endpunkte) liegen
 * bewusst AUSSERHALB dieser App-Autorisierungs-Invariante: sie werden durch die globale KSF-/
 * Security-Filterchain gegatet, nicht per {@code @RequiresPermission} der App.
 *
 * <p><b>Warum der Classpath-Scan vollstaendig fuer diesen Scope ist (Codex-Re-Gate):</b>
 *
 * <ul>
 *   <li>Der Scan arbeitet auf dem CLASSPATH (annotierte {@code .class}-TYPEN unter der App-Wurzel),
 *       NICHT auf der Spring-Bean-Registrierung. Ein App-Controller unter {@code de.brasi.nova.*}
 *       wird daher gefunden, UNABHAENGIG davon, ob er per {@code @Component}/{@code @RestController}
 *       component-scanned ODER per {@code @Bean} in einer Konfiguration registriert wird - sein
 *       Klassen-{@code .class} liegt in jedem Fall unter der gescannten App-Wurzel.
 *   <li>Handler-Erkennung ueber Include-Filter auf {@link Controller} UND type-level {@link
 *       RequestMapping} - deckungsgleich mit Springs {@code RequestMappingHandlerMapping#isHandler}.
 *       Da {@code @RestController} mit {@code @Controller} meta-annotiert ist, erfasst der {@code
 *       @Controller}-Filter auch ihn; ein PLAIN {@code @Controller}-Schreib-Endpunkt (fruehere
 *       Luecke) wird jetzt mitgeprueft.
 *   <li>Test-only Probe-Pakete werden ueber eine PRAEZISE Paketgrenze ausgeschlossen ({@link
 *       #isInExcludedProbePackage} nutzt {@code prefix + "."}, kein roher {@code startsWith} - ein
 *       Nachbarpaket wie {@code ...affordanceprobe2} faellt NICHT faelschlich aus dem Scan).
 * </ul>
 *
 * <p>Der {@link
 * #ac4_scan_erfasst_app_controller_und_schliesst_probe_pakete_praezise_aus() Scope-Test} exerziert
 * {@code productionControllers()} direkt (reale App-Controller enthalten; Probe-Fixture praezise
 * ausgeschlossen).
 *
 * <p>Der {@link
 * #ac4_invertierbar_plain_controller_ungegateter_schreibendpunkt_wird_erkannt() Invertierbarkeits-
 * Test} belegt empirisch, dass ein PLAIN {@code @Controller} mit ungegatetem {@code @PostMapping}
 * vom Discovery-Kern als {@code uncategorized} erkannt wird (Negativ-Fixture {@link
 * UngatedPlainMutationController} im test-only, vom Produktions-Scan ausgeschlossenen Paket {@code
 * affordanceprobe}).
 *
 * <h2>AC4: Beweis-Teilung Vollstaendigkeit vs. Laufzeit-Delegation</h2>
 *
 * <p>Dieser Sentinel beweist die <b>Vollstaendigkeit/Abdeckung</b> (jeder Mutations-Endpunkt ist
 * genau einer Autorisierungsklasse zugeordnet, und jede Map-/Allowlist-Zeile zeigt auf eine real
 * existierende Handler-Methode -> ein Rename bricht den Sentinel).
 *
 * <p>Der <b>Laufzeit-Beweis</b>, dass ein guard-delegierter Handler den {@code
 * TaskMembershipGuard.require(Read|Write)Member} TATSAECHLICH aufruft (401/403/404-Verhalten fuer
 * Anon/Non-/Ex-/Fremd-Member), liegt bewusst NICHT in diesem statischen Sentinel, sondern in den
 * bestehenden Membership-Integrationstests gegen den echten Security-Kontext (KSF 2.3.24):
 *
 * <ul>
 *   <li>{@code TaskFacadeControllerIT.patch_membership_guard_wire_und_keine_verweigerten_writes}
 *       und {@code TaskFacadeControllerIT.patch_nicht_existent_und_fremd_liefert_404} ->
 *       {@code TaskFacadeController#patch} (TaskCommandService.requireWriteMember)
 *   <li>{@code UserStateControllerIT.anonym_401_falsche_permission_403_fremder_tenant_404} ->
 *       {@code UserStateController#pin/flag/mute} (MarkerService -> requireWriteMember)
 *   <li>{@code CommentControllerIT.comment_membership_guard_wire_und_keine_verweigerten_writes} ->
 *       {@code CommentController#createComment} (CommentService.requireWriteMember)
 *   <li>{@code AssigneeControllerIT.s4_exit_non_member_404} und
 *       {@code AssigneeControllerIT.exit_non_member_malformter_body_400} ->
 *       {@code AssigneeController#exit} (AssigneeService.requireWriteMember)
 * </ul>
 *
 * <p>Zusaetzlich haertet der Sentinel jede Guard-Map-Zeile billig ab (ohne Bytecode-Analyse): der
 * delegierte Controller muss ein Feld vom erwarteten {@code @Service}-Typ tragen, und - sofern die
 * Service-Quelldatei im Build-Baum auffindbar ist (best-effort) - deren Quelltext muss einen {@code
 * require(Read|Write)Member}-Aufruf enthalten.
 *
 * <h2>Bekannte, bewusst akzeptierte Grenzen (nach adversarialem Gate-Durchlauf)</h2>
 *
 * <p>Dieser statische Sentinel spiegelt die TATSAECHLICHEN KSF-2.3.24-Leser (Discovery =
 * {@code RequestMappingHandlerMapping#isHandler}; {@code @EnableNovaHateoas} via {@code
 * findMergedAnnotation}; {@code @RequiresPermission} via {@code method.getAnnotation} +
 * Klassenhierarchie) und faengt die REALISTISCHE Regression - einen neuen flachen {@code
 * @RestController} mit ungegateter Mutation - zuverlaessig. Zwei EXOTISCHE, von task-management NICHT
 * verwendete Muster bleiben statisch bewusst ausserhalb und sind durch Code-Review, den
 * menuegetriebenen Live-GET-Smoke, die realen 401/403/404-Membership-ITs und das owner-gated
 * Gesamt-IMPL-Gate vor dem Merge abgedeckt:
 *
 * <ol>
 *   <li><b>Geerbte Mapping-Methode aus UNANNOTIERTER Basisklasse + Klassen-{@code @RequiresPermission}
 *       auf der konkreten Subklasse:</b> der KSF-Enforcement-Pointcut ist {@code @annotation ||
 *       @within} (KEIN {@code @target}); bei einer aus der unannotierten Basis geerbten Methode
 *       matcht {@code @within} die (unannotierte) deklarierende Basisklasse, KSF enforced dann ggf.
 *       nicht - {@link #ksfReadsRequiresPermission} wertet das Klassen-{@code @RequiresPermission}
 *       der Subklasse hingegen als Gatung. task-management nutzt ausschliesslich flache Controller
 *       mit DIREKT annotierten Mutations-Methoden (kein geerbtes Mapping aus unannotierter Basis).
 *   <li><b>{@code @Component @EnableNovaHateoas} OHNE {@code @Controller}:</b> die KSF-Registry
 *       verarbeitet {@code context.getBeansWithAnnotation(EnableNovaHateoas.class)} (ALLE Beans);
 *       der handler-basierte {@link #productionControllers()}-Scan der AC7-Zaehlung erfasst nur
 *       MVC-Handler-Typen. {@code @EnableNovaHateoas} wird in task-management ausschliesslich auf
 *       Controllern verwendet (Rule-Owner sind immer der ausliefernde Controller).
 * </ol>
 */
class AffordanceSentinelsTest {

  private static final String BASE_PACKAGE = "de.brasi.nova.taskmanagement";

  /**
   * Scan-Basispakete deckungsgleich zum Produktions-Boot: reflektiv aus {@link
   * BrasiNovaApplication} {@code @SpringBootApplication(scanBasePackages = ...)} abgeleitet
   * (Fallback: das Paket der Application-Klasse). Verhindert, dass der Sentinel enger scannt als
   * die Anwendung registriert.
   */
  private static final String[] SCAN_BASE_PACKAGES = resolveScanBasePackages();

  /** Test-only Probe-Controller-Pakete, die NICHT zur Produktions-Flaeche gehoeren. */
  private static final Set<String> EXCLUDED_PACKAGE_PREFIXES =
      Set.of(BASE_PACKAGE + ".security.it", BASE_PACKAGE + ".affordanceprobe");

  /**
   * Handler-Erkennung deckungsgleich mit Springs {@code RequestMappingHandlerMapping#isHandler}
   * (das {@code AnnotatedElementUtils.hasAnnotation} nutzt und damit die GESAMTE Typhierarchie
   * durchsucht): ein hierarchie-durchlaufender Filter ({@code considerInheritance=true}, {@code
   * considerInterfaces=true}), der auf JEDER Ebene (Klasse, Superklassen, Interfaces) {@link
   * Controller} (direkt oder meta -> erfasst auch {@code @RestController}) ODER type-level {@link
   * RequestMapping} matcht. Damit wird auch ein KONKRETER Controller erfasst, dessen Controller-/
   * Mapping-Annotation auf einem Interface oder einer Superklasse liegt (Codex-Re-Gate-BLOCKER R5).
   */
  private static final TypeFilter HANDLER_TYPE_FILTER =
      new AbstractTypeHierarchyTraversingFilter(true, true) {
        @Override
        protected boolean matchSelf(final MetadataReader metadataReader) {
          final AnnotationMetadata meta = metadataReader.getAnnotationMetadata();
          return meta.hasAnnotation(Controller.class.getName())
              || meta.hasMetaAnnotation(Controller.class.getName())
              || meta.hasAnnotation(RequestMapping.class.getName())
              || meta.hasMetaAnnotation(RequestMapping.class.getName());
        }
      };

  /**
   * Gepflegte Delegationsmap: Mutations-Handler OHNE {@code @RequiresPermission}, deren
   * Autorisierung objekt-level ueber den {@code TaskMembershipGuard} im jeweiligen Service erfolgt
   * (neutrale 404 fuer Non-/Ex-Member). Schluessel {@code <SimpleControllerName>#<method>} -> Wert
   * = der Name des Controller-Felds (Service), dessen Quelle den Guard-Aufruf traegt. Jeder Eintrag
   * ist zusaetzlich durch die in der Klassen-Javadoc namentlich genannten Membership-ITs belegt.
   */
  private static final Map<String, String> MEMBERSHIP_GUARD_DELEGATED =
      Map.of(
          "TaskFacadeController#patch", "commandService", // TaskCommandService.requireWriteMember
          "UserStateController#pin", "markerService", //     MarkerService -> requireWriteMember
          "UserStateController#flag", "markerService",
          "UserStateController#mute", "markerService",
          "CommentController#createComment", "commentService", // CommentService.requireWriteMember
          "AssigneeController#exit", "assigneeService"); //      AssigneeService.requireWriteMember

  /**
   * Explizite Allowlist: bewusst gate-freie Mutations-Handler (authenticated-only). {@code POST
   * /ws-tickets} stellt ein kurzlebiges, akteur-/mandanten-gebundenes Push-Ticket aus; das
   * Push-Routing gatet darueber (kein fachliches Schreiben am Datenmodell).
   */
  private static final Set<String> GATE_FREE_ALLOWLIST =
      Set.of("WebSocketTicketController#issueTicket");

  @Test
  @DisplayName(
      "AC7: hoechstens EIN @EnableNovaHateoas-Regel-Owner je entityClass im -rt (kein zweiter"
          + " Task-Owner)")
  void ac7_genau_ein_rule_owner_je_entity_class() {
    final Map<String, List<String>> ownersByEntity = ownersByEntity(productionControllers());

    final Map<String, List<String>> duplicates = new LinkedHashMap<>();
    ownersByEntity.forEach(
        (entity, owners) -> {
          if (owners.size() > 1) {
            duplicates.put(entity, owners);
          }
        });
    assertThat(duplicates)
        .as("Mehrere @EnableNovaHateoas-Regel-Owner je entityClass: " + duplicates)
        .isEmpty();

    final List<String> taskOwners =
        ownersByEntity.getOrDefault("de.brasi.nova.taskmanagement.entity.Task", List.of());
    assertThat(taskOwners)
        .as("genau ein Task-Regel-Owner erwartet")
        .containsExactly(TaskFacadeController.class.getName());

    // Slice 2: genau EIN TaskTag-Regel-Owner, und das ist der TagController (ausliefernder
    // Controller ist Rule-Owner; ein zweiter @EnableNovaHateoas(TaskTag)-Owner laesst den Test
    // rot).
    final List<String> tagOwners =
        ownersByEntity.getOrDefault("de.brasi.nova.taskmanagement.entity.TaskTag", List.of());
    assertThat(tagOwners)
        .as("genau ein TaskTag-Regel-Owner erwartet (= TagController)")
        .containsExactly(de.brasi.nova.taskmanagement.tag.TagController.class.getName());

    // Slice 5: genau EIN TaskNotification-Regel-Owner, und das ist der NotificationController
    // (ausliefernder Controller ist Rule-Owner; mark-read/mark-all-read sind
    // NOTIFICATION_WRITE-gegatet).
    final List<String> notificationOwners =
        ownersByEntity.getOrDefault(
            "de.brasi.nova.taskmanagement.entity.TaskNotification", List.of());
    assertThat(notificationOwners)
        .as("genau ein TaskNotification-Regel-Owner erwartet (= NotificationController)")
        .containsExactly(
            de.brasi.nova.taskmanagement.notification.NotificationController.class.getName());
  }

  @Test
  @DisplayName(
      "AC7-Interface: ein zweiter Task-Regel-Owner, dessen @EnableNovaHateoas auf einem Interface"
          + " liegt, wird als Duplikat erkannt (hierarchische Annotationserkennung; Codex-Re-Gate R6)")
  void ac7_interface_rule_owner_wird_als_duplikat_erkannt() {
    final Map<String, List<String>> owners =
        ownersByEntity(
            List.of(
                TaskFacadeController.class,
                de.brasi.nova.taskmanagement.affordanceprobe.InterfaceTaskRuleOwnerFixture
                    .SecondTaskOwnerImpl.class));

    final List<String> taskOwners =
        owners.getOrDefault("de.brasi.nova.taskmanagement.entity.Task", List.of());
    assertThat(taskOwners)
        .as(
            "ein via Interface deklarierter zweiter @EnableNovaHateoas(Task)-Owner MUSS mitgezaehlt"
                + " werden (sonst bliebe ein Laufzeit-Doppel-Owner unentdeckt): %s",
            taskOwners)
        .hasSize(2);
  }

  /**
   * Zaehlt je {@code entityClass} die {@code @EnableNovaHateoas}-Regel-Owner. Liest die Annotation
   * HIERARCHISCH ({@link AnnotatedElementUtils#findMergedAnnotation} - Interfaces + Superklassen +
   * meta), deckungsgleich mit der KSF-Regelregistrierung; ein Owner, dessen
   * {@code @EnableNovaHateoas} auf einem Interface/einer Superklasse liegt, wird damit ebenfalls
   * erfasst (Codex-Re-Gate R6).
   */
  private static Map<String, List<String>> ownersByEntity(final Collection<Class<?>> controllers) {
    final Map<String, List<String>> ownersByEntity = new TreeMap<>();
    for (final Class<?> controller : controllers) {
      final EnableNovaHateoas ann =
          AnnotatedElementUtils.findMergedAnnotation(controller, EnableNovaHateoas.class);
      if (ann != null) {
        ownersByEntity
            .computeIfAbsent(ann.entityClass().getName(), k -> new ArrayList<>())
            .add(controller.getName());
      }
    }
    return ownersByEntity;
  }

  @Test
  @DisplayName(
      "AC4: jeder Mutations-Endpunkt (via RequestMappingHandlerMapping, inkl. @RequestMapping"
          + "(method=...) und PLAIN @Controller) ist @RequiresPermission ODER guard-delegiert ODER"
          + " explizit allowlisted")
  void ac4_jeder_mutations_endpunkt_ist_autorisiert() {
    final List<MutationHandler> mutationHandlers = discoverMutationHandlers();

    assertThat(mutationHandlers)
        .as("Sanity: es wurden Mutations-Endpunkte via RequestMappingHandlerMapping gefunden")
        .isNotEmpty();

    final CategoryProblems problems = categorize(mutationHandlers);

    assertThat(problems.ambiguousKeys())
        .as(
            "Mehrdeutige Mutations-Handler-Schluessel (ueberladene Handler teilen"
                + " <Controller#method>): %s. Schluessel disambiguieren - sonst kann ein ungegateter"
                + " ueberladener Handler an einem guard-delegierten/allowlisteten Eintrag gruen"
                + " bleiben (Stale-Key-Pruefung wuerde das nicht erkennen).",
            problems.ambiguousKeys())
        .isEmpty();
    assertThat(problems.uncategorized())
        .as(
            "Ungegatete Mutations-Endpunkte ohne @RequiresPermission, Guard-Delegation oder"
                + " Allowlist-Eintrag: %s. Ein neuer Schreib-Endpunkt braucht ENTWEDER"
                + " @RequiresPermission ODER einen Eintrag in MEMBERSHIP_GUARD_DELEGATED (mit"
                + " Service-Feldname + Membership-IT-Beweis) ODER in GATE_FREE_ALLOWLIST.",
            problems.uncategorized())
        .isEmpty();
    assertThat(problems.multiCategorized())
        .as(
            "Mutations-Endpunkte in MEHR als einer Autorisierungsklasse (genau eine zugesagt): %s."
                + " Doppelzuordnung aufloesen (Handler mit @RequiresPermission NICHT zusaetzlich in"
                + " MEMBERSHIP_GUARD_DELEGATED/Allowlist fuehren).",
            problems.multiCategorized())
        .isEmpty();
    assertThat(problems.staleGuardKeys())
        .as(
            "Verwaiste MEMBERSHIP_GUARD_DELEGATED-Zeilen (Handler existiert nicht mehr / wurde"
                + " umbenannt): %s. Map an die reale Mutations-Flaeche anpassen.",
            problems.staleGuardKeys())
        .isEmpty();
    assertThat(problems.staleAllowlist())
        .as(
            "Verwaiste GATE_FREE_ALLOWLIST-Zeilen (Handler existiert nicht mehr / wurde"
                + " umbenannt): %s.",
            problems.staleAllowlist())
        .isEmpty();
    assertThat(problems.guardProofProblems())
        .as(
            "Guard-Delegations-Haertung verletzt (Service-Feld fehlt / kein @Service / Quelle ohne"
                + " require*Member-Aufruf): %s. Die 401/403/404-Laufzeit beweisen die namentlich in"
                + " der Klassen-Javadoc genannten Membership-ITs.",
            problems.guardProofProblems())
        .isEmpty();
  }

  @Test
  @DisplayName(
      "AC4-Invertierbarkeit: ein PLAIN @Controller mit ungegatetem @PostMapping wird als"
          + " uncategorized erkannt (Discovery nicht umgehbar)")
  void ac4_invertierbar_plain_controller_ungegateter_schreibendpunkt_wird_erkannt() {
    // Die Negativ-Fixture liegt im vom Produktions-Scan AUSGESCHLOSSenen affordanceprobe-Paket und
    // wird dem Discovery-Kern hier EXPLIZIT uebergeben (sonst waere der reale AC4-Lauf dauerhaft
    // rot). Sie ist ein PLAIN @Controller (nicht @RestController) mit ungegatetem @PostMapping.
    final List<MutationHandler> handlers =
        discoverMutationHandlers(List.of(UngatedPlainMutationController.class));

    assertThat(handlers)
        .as(
            "ein PLAIN @Controller @PostMapping MUSS entdeckt werden"
                + " (Meta-Annotation-Luecke geschlossen)")
        .isNotEmpty();

    final CategoryProblems problems = categorize(handlers);

    assertThat(problems.uncategorized())
        .as(
            "ein ungegateter PLAIN-@Controller-Schreibhandler MUSS als uncategorized rot schlagen"
                + " (Invertierbarkeit des Sentinels, Codex-Re-Gate-BLOCKER): %s",
            problems.uncategorized())
        .anyMatch(entry -> entry.startsWith("UngatedPlainMutationController#write"));
  }

  @Test
  @DisplayName(
      "AC4-Scope: productionControllers() erfasst die realen App-Controller und schliesst test-only"
          + " Probe-Pakete PRAEZISE aus (kein startsWith-Praefix-Leak)")
  void ac4_scan_erfasst_app_controller_und_schliesst_probe_pakete_praezise_aus() {
    final List<Class<?>> scanned = productionControllers();

    // reale App-Controller (component-scanned @RestController) sind erfasst
    assertThat(scanned)
        .as("reale App-Controller muessen im Produktions-Scan liegen")
        .contains(TaskFacadeController.class, de.brasi.nova.taskmanagement.tag.TagController.class);

    // die test-only Negativ-Fixture (affordanceprobe) ist PRAEZISE ausgeschlossen - und der
    // Scan-Umfang kommt aus den App-abgeleiteten Basispaketen (de.brasi.nova*), nicht aus einer
    // handgepflegten Paketliste, die driften koennte.
    assertThat(scanned)
        .as("test-only Probe-Fixture darf NICHT im Produktions-Scan liegen")
        .doesNotContain(UngatedPlainMutationController.class);

    // Praezise Paketgrenze: das echte Probe-Paket ist ausgeschlossen, ein (hypothetisches)
    // Nachbarpaket mit gleichem Praefix aber NICHT (kein startsWith-Leak).
    assertThat(isInExcludedProbePackage(BASE_PACKAGE + ".affordanceprobe.Foo"))
        .as("echtes Probe-Paket ausgeschlossen")
        .isTrue();
    assertThat(isInExcludedProbePackage(BASE_PACKAGE + ".affordanceprobe2.Foo"))
        .as("Nachbarpaket mit gleichem Praefix darf NICHT ausgeschlossen werden")
        .isFalse();
  }

  @Test
  @DisplayName(
      "AC4-Condition: der unconditional Scan erfasst einen @ConditionalOnProperty-Controller AUCH"
          + " bei im Test unerfuellter Bedingung (findCandidateComponents-@Conditional-Luecke zu)")
  void ac4_scan_ignoriert_conditional_registrierung() {
    final String probePackage = BASE_PACKAGE + ".affordanceprobe";
    // Die Property 'task-management.test.affordance-probe.enabled' ist im Testlauf NICHT gesetzt ->
    // die
    // @ConditionalOnProperty-Bedingung der Fixture ist UNERFUELLT. Ein Spring-Standard-Scan (mit
    // @Conditional-Auswertung via findCandidateComponents) wuerde den Typ verwerfen; der
    // unconditional Scanner MUSS ihn dennoch finden.
    final boolean found =
        unconditionalControllerScanner().findCandidateComponents(probePackage).stream()
            .map(bd -> bd.getBeanClassName())
            .anyMatch(UngatedPlainMutationController.class.getName()::equals);

    assertThat(found)
        .as(
            "ein @ConditionalOnProperty-Controller mit im Test unerfuellter Bedingung MUSS vom"
                + " unconditional Scan erfasst werden - sonst bliebe ein per Property aktivierter,"
                + " ungegateter Controller in der laufenden App unsichtbar (Codex-Re-Gate-BLOCKER-2)")
        .isTrue();
  }

  @Test
  @DisplayName(
      "AC4-Inner: der Scan erfasst auch eine NON-STATIC inner @Controller-Klasse"
          + " (isIndependent-Filter uebersteuert; Codex-Re-Gate-BLOCKER R4)")
  void ac4_scan_erfasst_non_static_inner_controller() {
    final String innerName =
        de.brasi.nova.taskmanagement.affordanceprobe.NonStaticInnerControllerFixture
            .InnerUngatedController.class
            .getName();
    // Ein non-static inner Controller hat isIndependent()==false und wuerde vom Spring-Standard-
    // isCandidateComponent(AnnotatedBeanDefinition) verworfen; der uebersteuerte Scanner MUSS ihn
    // dennoch finden (sonst bliebe ein @Bean-registrierter innerer MVC-Handler unsichtbar).
    final boolean found =
        unconditionalControllerScanner()
            .findCandidateComponents(BASE_PACKAGE + ".affordanceprobe")
            .stream()
            .map(bd -> bd.getBeanClassName())
            .anyMatch(innerName::equals);

    assertThat(found)
        .as("non-static inner @Controller MUSS vom Scan erfasst werden: %s", innerName)
        .isTrue();
  }

  @Test
  @DisplayName(
      "AC4-Interface: Discovery erfasst einen KONKRETEN Controller mit Interface-Annotationen (="
          + " Springs isHandler, R5); ein @RequiresPermission NUR auf der Interface-Methode zaehlt"
          + " NICHT als Gatung (KSF liest die konkrete Methode, R7) -> uncategorized")
  void ac4_discovery_erfasst_interface_annotierten_controller() {
    final Class<?> impl =
        de.brasi.nova.taskmanagement.affordanceprobe.InterfaceAnnotatedControllerFixture
            .AnnotatedInterfaceControllerImpl.class;

    // (a) der hierarchie-durchlaufende Scanner findet die KONKRETE Impl (Annotation via Interface).
    final boolean scanned =
        unconditionalControllerScanner()
            .findCandidateComponents(BASE_PACKAGE + ".affordanceprobe")
            .stream()
            .map(bd -> bd.getBeanClassName())
            .anyMatch(impl.getName()::equals);
    assertThat(scanned)
        .as("konkrete Interface-annotierte Impl MUSS im Scan liegen: %s", impl.getName())
        .isTrue();

    // (b) end-to-end: discoverMutationHandlers (ueber das reale RequestMappingHandlerMapping)
    // findet
    //     den Interface-gemappten POST der konkreten Impl, und categorize flaggt ihn als ungegatet.
    final List<MutationHandler> handlers = discoverMutationHandlers(List.of(impl));
    assertThat(handlers)
        .as(
            "Interface-gemappter @PostMapping der konkreten Impl wird als Mutations-Handler entdeckt")
        .anyMatch(mh -> mh.key().equals("AnnotatedInterfaceControllerImpl#write"));

    final CategoryProblems problems = categorize(handlers);
    assertThat(problems.uncategorized())
        .as(
            "der via Interface gemappte Schreibhandler MUSS uncategorized rot schlagen - das"
                + " @RequiresPermission auf der INTERFACE-Methode zaehlt NICHT (KSF liest die"
                + " konkrete Methode direkt und enforced es nicht): %s",
            problems.uncategorized())
        .anyMatch(entry -> entry.startsWith("AnnotatedInterfaceControllerImpl#write"));
  }

  /**
   * Kategorisiert eine Menge entdeckter Mutations-Handler in die AC4-Befundlisten. Reine Berechnung
   * (kein Assert), damit sowohl der Vollstaendigkeits- als auch der Invertierbarkeits- Test
   * denselben Kern treffen.
   */
  private static CategoryProblems categorize(final List<MutationHandler> mutationHandlers) {
    // 0) Eindeutigkeit der Handler-Schluessel: ein <Controller#method>-Schluessel darf GENAU EINEN
    //    Mutations-Endpunkt bezeichnen. Ueberladene Mutations-Handler (gleicher Name, andere
    //    Parameter) teilten sonst einen Schluessel und koennten sich ungegatet an einem
    //    guard-delegierten/allowlisteten Eintrag vorbeimogeln. Kollision -> rot (disambiguieren).
    final Map<String, Integer> keyCounts = new LinkedHashMap<>();
    for (final MutationHandler mh : mutationHandlers) {
      keyCounts.merge(mh.key(), 1, Integer::sum);
    }
    final List<String> ambiguousKeys = new ArrayList<>();
    keyCounts.forEach(
        (k, c) -> {
          if (c > 1) {
            ambiguousKeys.add(k + " x" + c);
          }
        });

    // 1) Jeder entdeckte Mutations-Endpunkt faellt in GENAU EINE Autorisierungsklasse (nicht nur
    //    mindestens eine): count==0 -> ungegatet; count>1 -> widerspruechliche Doppelzuordnung.
    final List<String> uncategorized = new ArrayList<>();
    final List<String> multiCategorized = new ArrayList<>();
    for (final MutationHandler mh : mutationHandlers) {
      // @RequiresPermission GENAU so lesen wie KSF 2.3.24 es ENFORCED (PermissionEnforcementAspect
      // .resolveRequiredPermission): method.getAnnotation (direkt, Vorrang) + Klassenhierarchie
      // (Klasse + Basisklassen), KEINE Interface-Vererbung. Wuerde der Sentinel hier hierarchisch
      // (AnnotatedElementUtils) lesen, zaehlte er ein @RequiresPermission auf einer
      // INTERFACE-Methode
      // als Gatung - KSF liest es unter CGLIB-Proxys aber NICHT von der konkreten Methode und
      // enforced es NICHT -> False-Negative (Sentinel gruen, Laufzeit ungegatet).
      final boolean annotated = ksfReadsRequiresPermission(mh.method(), mh.controller());
      final boolean guardDelegated = MEMBERSHIP_GUARD_DELEGATED.containsKey(mh.key());
      final boolean allowlisted = GATE_FREE_ALLOWLIST.contains(mh.key());
      final int categories = (annotated ? 1 : 0) + (guardDelegated ? 1 : 0) + (allowlisted ? 1 : 0);
      if (categories == 0) {
        uncategorized.add(mh.key() + " [" + mh.httpMethods() + "]");
      } else if (categories > 1) {
        multiCategorized.add(
            mh.key()
                + " {annotated="
                + annotated
                + ",guard="
                + guardDelegated
                + ",allowlist="
                + allowlisted
                + "}");
      }
    }

    // 2) Rename-Bruch: jede Map-/Allowlist-Zeile MUSS auf eine real existierende Handler-Methode
    //    zeigen. Benennt eine Slice einen delegierten/allowlisteten Handler um, faellt der Sentinel
    //    (statt lautlos eine tote Zeile zu behalten, die den umbenannten Handler ungegatet laesst).
    final Set<String> discoveredKeys = new TreeSet<>();
    mutationHandlers.forEach(mh -> discoveredKeys.add(mh.key()));

    final List<String> staleGuardKeys =
        MEMBERSHIP_GUARD_DELEGATED.keySet().stream()
            .filter(k -> !discoveredKeys.contains(k))
            .sorted()
            .toList();
    final List<String> staleAllowlist =
        GATE_FREE_ALLOWLIST.stream().filter(k -> !discoveredKeys.contains(k)).sorted().toList();

    // 3) Billige Haertung je Guard-Map-Zeile: der delegierte Controller traegt ein Feld vom
    //    erwarteten @Service-Typ, und (best-effort) dessen Quelle enthaelt einen Guard-Aufruf.
    final List<String> guardProofProblems = new ArrayList<>();
    for (final MutationHandler mh : mutationHandlers) {
      final String serviceFieldName = MEMBERSHIP_GUARD_DELEGATED.get(mh.key());
      if (serviceFieldName == null) {
        continue;
      }
      verifyGuardDelegation(mh, serviceFieldName, guardProofProblems);
    }

    return new CategoryProblems(
        ambiguousKeys,
        uncategorized,
        multiCategorized,
        staleGuardKeys,
        staleAllowlist,
        guardProofProblems);
  }

  /**
   * Liest {@code @RequiresPermission} GENAU so, wie KSF 2.3.24 es ENFORCED ({@code
   * PermissionEnforcementAspect.resolveRequiredPermission}): (1) {@code method.getAnnotation}
   * DIREKT auf der konkretesten Ziel-Methode (Vorrang), (2) sonst {@code @RequiresPermission} auf
   * der Klassenhierarchie (Klasse + Basisklassen, je {@code getAnnotation}). Bewusst OHNE
   * Interface-Vererbung - unter den klassenbasierten Spring-AOP-Proxys traegt die konkrete Methode
   * eine nur auf der Interface-Methode gesetzte Annotation NICHT, und KSF enforced sie dann nicht.
   * Der Sentinel muss diese Enforcement-Realitaet exakt spiegeln (sonst False-Negative).
   */
  private static boolean ksfReadsRequiresPermission(
      final Method method, final Class<?> controller) {
    final Method specific = ClassUtils.getMostSpecificMethod(method, controller);
    if (specific.getAnnotation(RequiresPermission.class) != null) {
      return true;
    }
    for (Class<?> c = controller; c != null && c != Object.class; c = c.getSuperclass()) {
      if (c.getAnnotation(RequiresPermission.class) != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * Prueft fuer einen guard-delegierten Handler, dass der Controller ein Feld {@code
   * serviceFieldName} vom {@code @Service}-Typ traegt und (fail-closed) dessen Quelltext einen
   * {@code requireWriteMember}-Aufruf enthaelt (Mutation).
   */
  private static void verifyGuardDelegation(
      final MutationHandler mh, final String serviceFieldName, final List<String> problems) {
    final Field field;
    try {
      field = mh.controller().getDeclaredField(serviceFieldName);
    } catch (final NoSuchFieldException e) {
      problems.add(mh.key() + ": erwartetes Service-Feld '" + serviceFieldName + "' fehlt");
      return;
    }
    final Class<?> serviceType = field.getType();
    if (!serviceType.isAnnotationPresent(Service.class)) {
      problems.add(
          mh.key()
              + ": Feld '"
              + serviceFieldName
              + "' Typ "
              + serviceType.getSimpleName()
              + " ist kein @Service");
      return;
    }
    // Fail-closed Quelltext-Pruefung: beweist, dass der delegierte Service den Guard real ruft.
    // Ist die Quelle im Build-Baum nicht auffindbar oder nicht lesbar, wird der Test ROT (kein
    // stilles Bestehen); die Tests laufen mit Modul-Basisverzeichnis als Arbeitsverzeichnis.
    final Path src = serviceSourcePath(serviceType);
    if (src == null) {
      problems.add(
          mh.key()
              + ": Quelle von "
              + serviceType.getSimpleName()
              + " nicht unter src/main/java auffindbar (Guard-Nachweis nicht fuehrbar)");
      return;
    }
    try {
      final String code = Files.readString(src);
      // Alle Eintraege der Guard-Map sind MUTATIONS-Handler (POST/PUT/PATCH/DELETE): ein
      // requireReadMember-Aufruf genuegt dort nicht - verlangt wird requireWriteMember.
      if (!code.contains("requireWriteMember")) {
        problems.add(
            mh.key()
                + ": "
                + serviceType.getSimpleName()
                + " enthaelt keinen requireWriteMember-Aufruf (Mutation braucht Write-Member)");
      }
    } catch (final java.io.IOException e) {
      problems.add(mh.key() + ": Quelle nicht lesbar (" + src + "): " + e.getMessage());
    }
  }

  /** Versucht, die {@code .java}-Quelle eines Service im {@code src/main/java}-Baum zu finden. */
  private static Path serviceSourcePath(final Class<?> serviceType) {
    final Path candidate =
        Path.of("src", "main", "java", (serviceType.getName().replace('.', '/') + ".java"));
    return Files.exists(candidate) ? candidate : null;
  }

  /** No-arg Discovery ueber die reale Produktions-Controller-Flaeche. */
  private static List<MutationHandler> discoverMutationHandlers() {
    return discoverMutationHandlers(productionControllers());
  }

  /**
   * Entdeckt ALLE Mutations-Endpunkte (POST/PUT/PATCH/DELETE) der uebergebenen Controller-Typen
   * strukturell ueber ein Laufzeit-{@link RequestMappingHandlerMapping}, das ueber die Bean-TYPEN
   * aufgebaut wird (keine Bean-Instanziierung, kein DB-Zugang). So wird auch
   * {@code @RequestMapping(method=...)} erfasst, nicht nur die Kurzform-Annotationen. Die
   * uebergebene Menge ist bereits die zu pruefende Flaeche (keine weitere Paket-Filterung hier) -
   * so kann der Invertierbarkeits-Test eine ausgeschlossene Negativ-Fixture explizit einspeisen.
   */
  private static List<MutationHandler> discoverMutationHandlers(
      final Collection<Class<?>> controllers) {
    // Minimaler Web-Context: registriert die Controller nur als Bean-DEFINITIONEN (Typ-Metadaten).
    // detectHandlerMethods(beanName) liest ausschliesslich die Methoden-Annotationen des Bean-Typs
    // und instanziiert den Controller NICHT -> keine Service-/Repo-/DB-Abhaengigkeit noetig.
    final org.springframework.web.context.support.StaticWebApplicationContext ctx =
        new org.springframework.web.context.support.StaticWebApplicationContext();
    ctx.setServletContext(new org.springframework.mock.web.MockServletContext());
    for (final Class<?> controller : controllers) {
      ctx.registerBeanDefinition(
          controller.getName(),
          new org.springframework.beans.factory.support.RootBeanDefinition(controller));
    }

    final RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
    mapping.setApplicationContext(ctx);
    mapping.afterPropertiesSet();

    final Set<RequestMethod> mutationMethods =
        Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    final List<MutationHandler> result = new ArrayList<>();
    mapping
        .getHandlerMethods()
        .forEach(
            (info, handlerMethod) -> {
              final Class<?> controller = handlerMethod.getBeanType();
              if (controller.getName() == null) {
                return;
              }
              final Set<RequestMethod> methods = httpMethods(info);
              // Ein Mapping ohne explizite Methode (method=[]) matcht ALLE HTTP-Methoden -> es
              // umfasst dann auch Mutationen und muss gegatet sein (fail-closed behandeln).
              final boolean isMutation =
                  methods.isEmpty() || methods.stream().anyMatch(mutationMethods::contains);
              if (!isMutation) {
                return;
              }
              result.add(
                  new MutationHandler(
                      controller,
                      handlerMethod.getMethod(),
                      controller.getSimpleName() + "#" + handlerMethod.getMethod().getName(),
                      methods.isEmpty() ? "ALL" : methods.toString()));
            });
    ctx.close();
    return result;
  }

  private static Set<RequestMethod> httpMethods(final RequestMappingInfo info) {
    return info.getMethodsCondition().getMethods();
  }

  /**
   * Alle Produktions-Handler-Controller unterhalb der aus {@link BrasiNovaApplication} abgeleiteten
   * {@link #SCAN_BASE_PACKAGES} (ohne Test-only Probe-Pakete), via {@link
   * #unconditionalControllerScanner()} (Classpath-Scan OHNE {@code @Conditional}-Auswertung -> auch
   * conditional-registrierte Controller werden erfasst). Erkennung ueber {@link
   * #HANDLER_TYPE_FILTER} (hierarchie-durchlaufend: {@link Controller} inkl.
   * {@code @RestController} via Meta-Annotation UND type-level {@link RequestMapping}, auf
   * Klasse/Superklassen/Interfaces) - deckungsgleich mit {@code
   * RequestMappingHandlerMapping#isHandler}.
   */
  private static List<Class<?>> productionControllers() {
    final ClassPathScanningCandidateComponentProvider provider = unconditionalControllerScanner();

    final Set<String> seen = new LinkedHashSet<>();
    final List<Class<?>> controllers = new ArrayList<>();
    for (final String basePackage : SCAN_BASE_PACKAGES) {
      provider
          .findCandidateComponents(basePackage)
          .forEach(
              bd -> {
                final String className = bd.getBeanClassName();
                if (className == null || !seen.add(className)) {
                  return;
                }
                if (isInExcludedProbePackage(className)) {
                  return;
                }
                try {
                  controllers.add(Class.forName(className));
                } catch (final ClassNotFoundException e) {
                  throw new IllegalStateException("Controller nicht ladbar: " + className, e);
                }
              });
    }
    assertThat(controllers).as("Produktions-Controller im Scan gefunden").isNotEmpty();
    return controllers;
  }

  /**
   * Classpath-Scanner, der Controller-TYPEN unabhaengig von {@code @Conditional}-Bedingungen findet
   * (Codex-Re-Gate-BLOCKER-2): Spring-Standard-{@code findCandidateComponents} wertet
   * {@code @Conditional}/{@code @ConditionalOnProperty} aus und VERWIRFT einen Controller, dessen
   * Bedingung im Testlauf gerade nicht erfuellt ist - obwohl ihn das Produktionsprofil registrieren
   * wuerde. Durch Ueberschreiben von {@link
   * ClassPathScanningCandidateComponentProvider#isCandidateComponent(MetadataReader)} entfaellt die
   * Bedingungs-Auswertung: der Scan erfasst JEDEN Controller-{@code .class}-Typ unter der
   * App-Wurzel, unabhaengig von Registrierungs-Bedingungen (stehende Invariante ueber ALLE
   * potentiell registrierbaren Controller, nicht nur die im Test aktiven).
   *
   * <p>Zusaetzlich wird {@link
   * ClassPathScanningCandidateComponentProvider#isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition)}
   * uebersteuert: der Spring-Standard verwirft NICHT-UNABHAENGIGE Typen ({@code
   * isIndependent()==false}, z.B. non-static inner Controller), obwohl ein solcher Typ per
   * {@code @Bean} als MVC-Handler registriert werden kann (Codex-Re-Gate-BLOCKER R4). Hier werden
   * daher ALLE KONKRETEN Controller-Typen akzeptiert (inkl. non-static inner); nur
   * nicht-instanziierbare (abstrakt/ Interface) fallen raus. Die verbleibende, statisch
   * grundsaetzlich unerreichbare Grenze sind zur LAUFZEIT synthetisierte Controller ohne {@code
   * .class} auf dem Classpath (kein App-Muster; zusaetzlich durch den menuegetriebenen
   * Live-GET-Smoke + Membership-ITs + Code-Review abgedeckt).
   */
  private static ClassPathScanningCandidateComponentProvider unconditionalControllerScanner() {
    return new ClassPathScanningCandidateComponentProvider(false) {
      @Override
      protected boolean isCandidateComponent(final MetadataReader metadataReader)
          throws java.io.IOException {
        return HANDLER_TYPE_FILTER.match(metadataReader, getMetadataReaderFactory());
      }

      @Override
      protected boolean isCandidateComponent(
          final org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
              beanDefinition) {
        // KONKRET reicht (kein isIndependent()-Filter): auch non-static inner Controller-Typen
        // werden erfasst. Abstrakte/Interface-Typen sind nicht instanziierbar -> raus.
        return beanDefinition.getMetadata().isConcrete();
      }
    };
  }

  /**
   * True, wenn die Klasse DIREKT in einem test-only Probe-Paket liegt. Praezise Paketgrenze ({@code
   * prefix + "."}) statt roher {@code startsWith}-Praefix, damit ein Nachbarpaket wie {@code
   * ...affordanceprobe2} NICHT faelschlich als ausgeschlossen gilt (sonst koennte ein dort
   * ergaenzter ungegateter Controller lautlos aus dem Scan fallen).
   */
  private static boolean isInExcludedProbePackage(final String className) {
    return EXCLUDED_PACKAGE_PREFIXES.stream()
        .anyMatch(prefix -> className.startsWith(prefix + "."));
  }

  /**
   * Leitet die Scan-Basispakete reflektiv aus {@link BrasiNovaApplication}
   * {@code @SpringBootApplication(scanBasePackages = ...)} ab (Fallback: Paket der
   * Application-Klasse).
   */
  private static String[] resolveScanBasePackages() {
    final SpringBootApplication ann =
        BrasiNovaApplication.class.getAnnotation(SpringBootApplication.class);
    final String[] declared = ann == null ? new String[0] : ann.scanBasePackages();
    if (declared.length == 0) {
      return new String[] {BrasiNovaApplication.class.getPackageName()};
    }
    return declared;
  }

  /**
   * Ein entdeckter Mutations-Endpunkt: Controller-Klasse, Handler-Methode, Schluessel, HTTP-Set.
   */
  private record MutationHandler(
      Class<?> controller, Method method, String key, String httpMethods) {}

  /** Gebuendelte AC4-Befundlisten aus {@link #categorize(List)}. */
  private record CategoryProblems(
      List<String> ambiguousKeys,
      List<String> uncategorized,
      List<String> multiCategorized,
      List<String> staleGuardKeys,
      List<String> staleAllowlist,
      List<String> guardProofProblems) {}
}
