package de.brasi.nova.taskmanagement.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.brasi.nova.taskmanagement.participant.MarkerService;
import de.brasi.nova.taskmanagement.participant.UserStateController;
import de.brasi.nova.taskmanagement.tag.TagCatalogService;
import de.brasi.nova.taskmanagement.tag.TagController;
import io.keel.framework.web.controller.GlobalApiExceptionHandler;
import io.keel.framework.web.exception.NovaExceptionHandlingProperties;
import io.keel.framework.web.exception.NovaExceptionMetrics;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP-Test der echten Sperrkonflikt-Uebersetzung (Codex-Review #167): echte Controller mit
 * gemocktem Service, der {@link CannotAcquireLockException} (Springs Uebersetzung von SQL-Fehler
 * 1205) wirft, davor der {@link LockConflictTranslationAspect} (Proxy wie im Kontext) und die
 * KSF-Fehlerbehandlung ({@link GlobalApiExceptionHandler}) in einem eigenstaendigen MockMvc. Ohne
 * Datenbank und ohne Spring-Kontext.
 *
 * <p>Erwartet: 409, {@code errorKey} {@code NOVA_BUSINESS_RULE_VIOLATION} und als {@code message}
 * der Text der Markierung des Controllers (Karte {@code ksf/error-format}: bei einer {@code
 * BusinessRuleException} ist {@code message} der Text der Ausnahme).
 */
class LockConflictHttpTest {

  private static final UUID ID = UUID.fromString("3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b");

  private static <T> T withAspect(final T controller) {
    final AspectJProxyFactory factory = new AspectJProxyFactory(controller);
    factory.setProxyTargetClass(true);
    factory.addAspect(new LockConflictTranslationAspect());
    return factory.getProxy();
  }

  private static MockMvc mvc(final Object controller) {
    return MockMvcBuilders.standaloneSetup(withAspect(controller))
        .setControllerAdvice(
            new GlobalApiExceptionHandler(
                new NovaExceptionHandlingProperties(), NovaExceptionMetrics.noop()))
        .build();
  }

  private static CannotAcquireLockException deadlock() {
    return new CannotAcquireLockException(
        "Transaction was deadlocked on lock resources and has been chosen as the deadlock victim",
        new SQLException("deadlock victim", "40001", 1205));
  }

  @Test
  @DisplayName(
      "i167_http_aufgabe: PUT /tasks/{id}/pin mit Deadlock-Opfer im Service -> 409,"
          + " NOVA_BUSINESS_RULE_VIOLATION, Aufgaben-Text")
  void task_endpoint_answers_409_with_task_text() throws Exception {
    final MarkerService markerService = mock(MarkerService.class);
    doThrow(deadlock()).when(markerService).setPinned(any(), anyBoolean());

    mvc(new UserStateController(markerService))
        .perform(
            put("/api/v1/tasks/{id}/pin", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errorKey").value("NOVA_BUSINESS_RULE_VIOLATION"))
        .andExpect(jsonPath("$.message").value(TranslatesLockConflicts.TASK));
  }

  @Test
  @DisplayName(
      "i167_http_tag: POST /tags/{id}/deactivate mit Deadlock-Opfer im Service -> 409,"
          + " NOVA_BUSINESS_RULE_VIOLATION, Tag-Text (Text je Sache)")
  void tag_endpoint_answers_409_with_tag_text() throws Exception {
    final TagCatalogService catalogService = mock(TagCatalogService.class);
    doThrow(deadlock()).when(catalogService).deactivate(any());

    mvc(new TagController(catalogService))
        .perform(post("/api/v1/tags/{id}/deactivate", ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errorKey").value("NOVA_BUSINESS_RULE_VIOLATION"))
        .andExpect(jsonPath("$.message").value(TranslatesLockConflicts.TAG));
  }
}
