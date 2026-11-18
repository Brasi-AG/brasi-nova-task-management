package de.brasi.nova.taskmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.keel.framework.api.exception.BusinessRuleException;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unit-Tests fuer {@link LockConflictTranslationAspect} (Intake #167): ein Sperrfehler der
 * Datenbank an einem schreibenden Endpunkt wird 409 ({@link BusinessRuleException} mit festem Text
 * und Ursache), alles andere bleibt unveraendert. Dazu ein Wachposten: jeder schreibende Endpunkt
 * eines markierten Controllers dokumentiert die 409, und jeder handgeschriebene Controller mit
 * schreibenden Endpunkten ist markiert (bewusste Ausnahme in {@link #UNMARKED_WRITE_CONTROLLERS}).
 */
class LockConflictTranslationAspectTest {

  /** Basis-Paket der Anwendung. */
  private static final String APP_BASE_PACKAGE = "de.brasi.nova.taskmanagement";

  /**
   * Handgeschriebene Controller mit schreibenden Endpunkten ohne Markierung: der Ticket-Controller
   * schreibt nur in einen Speicher im Prozess, nie in die Datenbank.
   */
  private static final Set<String> UNMARKED_WRITE_CONTROLLERS =
      Set.of("de.brasi.nova.taskmanagement.push.WebSocketTicketController");

  /** Beispiel-Controller fuer den Proxy: wirft die uebergebene Ausnahme. */
  @TranslatesLockConflicts
  static class SampleController {

    @PutMapping("/sample")
    public void write(final RuntimeException toThrow) {
      throw toThrow;
    }

    @GetMapping("/sample")
    public void read(final RuntimeException toThrow) {
      throw toThrow;
    }
  }

  private static SampleController proxy() {
    final AspectJProxyFactory factory = new AspectJProxyFactory(new SampleController());
    factory.setProxyTargetClass(true);
    factory.addAspect(new LockConflictTranslationAspect());
    return factory.getProxy();
  }

  @Test
  @DisplayName(
      "i167_deadlock_opfer_wird_409: CannotAcquireLockException (Spring-Uebersetzung von 1205) an"
          + " einem schreibenden Endpunkt -> BusinessRuleException mit festem Text und Ursache")
  void lock_failure_becomes_business_rule_exception() {
    final CannotAcquireLockException deadlock =
        new CannotAcquireLockException(
            "deadlock victim", new SQLException("deadlock", "40001", 1205));

    assertThatThrownBy(() -> proxy().write(deadlock))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessage(LockConflictTranslationAspect.MESSAGE)
        .hasCause(deadlock);
  }

  @Test
  @DisplayName(
      "i167_sql_fehler_1205_in_der_kette_wird_409: auch ohne Spring-Sperrtyp genuegt der"
          + " SQL-Fehlercode 1205 in der Ursachenkette")
  void sql_error_1205_in_chain_becomes_business_rule_exception() {
    final UncategorizedSQLException wrapped =
        new UncategorizedSQLException(
            "update", "UPDATE X", new SQLException("deadlock", "40001", 1205));

    assertThatThrownBy(() -> proxy().write(wrapped))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessage(LockConflictTranslationAspect.MESSAGE)
        .hasCause(wrapped);
  }

  @Test
  @DisplayName(
      "i167_andere_fehler_bleiben: andere DataAccessException, anderer SQL-Fehlercode und eine"
          + " vorhandene BusinessRuleException werden nicht uebersetzt")
  void other_failures_pass_through_unchanged() {
    final DataIntegrityViolationException integrity =
        new DataIntegrityViolationException("unique", new SQLException("dup", "23000", 2627));
    final UncategorizedSQLException otherCode =
        new UncategorizedSQLException("x", "SELECT 1", new SQLException("timeout", "HYT00", 1222));
    final BusinessRuleException conflict = new BusinessRuleException("Versionskonflikt");

    assertThatThrownBy(() -> proxy().write(integrity)).isSameAs(integrity);
    assertThatThrownBy(() -> proxy().write(otherCode)).isSameAs(otherCode);
    assertThatThrownBy(() -> proxy().write(conflict)).isSameAs(conflict);
  }

  @Test
  @DisplayName(
      "i167_lesende_endpunkte_bleiben: ein Sperrfehler an einem GET wird nicht uebersetzt (nur"
          + " POST, PUT, PATCH und DELETE)")
  void read_endpoints_are_not_translated() {
    final CannotAcquireLockException deadlock = new CannotAcquireLockException("deadlock victim");

    assertThatThrownBy(() -> proxy().read(deadlock)).isSameAs(deadlock);
  }

  @Test
  @DisplayName(
      "i167_wachposten_409_dokumentiert: jeder handgeschriebene Controller mit schreibenden"
          + " Endpunkten ist markiert (Ausnahme Ticket-Controller), und jeder schreibende Endpunkt"
          + " eines markierten Controllers dokumentiert die 409")
  void every_write_endpoint_of_marked_controllers_documents_409() throws ClassNotFoundException {
    final ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    final List<String> unmarked = new ArrayList<>();
    final List<String> undocumented = new ArrayList<>();
    int markedControllers = 0;
    for (final BeanDefinition candidate : scanner.findCandidateComponents(APP_BASE_PACKAGE)) {
      final Class<?> type = Class.forName(candidate.getBeanClassName());
      if (isTestClass(type)) {
        continue;
      }
      final List<Method> writes =
          Arrays.stream(type.getDeclaredMethods())
              .filter(LockConflictTranslationAspectTest::isWriteMapping)
              .toList();
      if (writes.isEmpty()) {
        continue;
      }
      if (!type.isAnnotationPresent(TranslatesLockConflicts.class)) {
        if (!UNMARKED_WRITE_CONTROLLERS.contains(type.getName())) {
          unmarked.add(type.getName());
        }
        continue;
      }
      markedControllers++;
      for (final Method write : writes) {
        if (!documents409(write)) {
          undocumented.add(type.getSimpleName() + "#" + write.getName());
        }
      }
    }
    assertThat(markedControllers).as("markierte Controller gefunden").isGreaterThanOrEqualTo(6);
    assertThat(unmarked).as("schreibende Controller ohne @TranslatesLockConflicts").isEmpty();
    assertThat(undocumented).as("schreibende Endpunkte ohne dokumentierte 409").isEmpty();
  }

  private static boolean isTestClass(final Class<?> type) {
    final var source = type.getProtectionDomain().getCodeSource();
    return source != null && source.getLocation().toString().contains("test-classes");
  }

  private static boolean isWriteMapping(final Method method) {
    return method.isAnnotationPresent(PostMapping.class)
        || method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class);
  }

  private static boolean documents409(final Method method) {
    final List<ApiResponse> responses = new ArrayList<>();
    final ApiResponses container = method.getAnnotation(ApiResponses.class);
    if (container != null) {
      responses.addAll(Arrays.asList(container.value()));
    }
    responses.addAll(Arrays.asList(method.getAnnotationsByType(ApiResponse.class)));
    return responses.stream().anyMatch(response -> "409".equals(response.responseCode()));
  }
}
