package de.brasi.nova.taskmanagement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reflection-Test: prueft, dass BrasiNovaApplication eine main-Methode hat UND mit
 * {@code @EnableScheduling} annotiert ist. Kein {@code @SpringBootTest} — kein DB-/Context-Start
 * in Task 1.
 */
class SkeletonContextTest {

  @Test
  @DisplayName("BrasiNovaApplication hat main-Methode und @EnableScheduling")
  void main_und_enableScheduling() throws NoSuchMethodException {
    // main-Methode vorhanden
    Method main = BrasiNovaApplication.class.getDeclaredMethod("main", String[].class);
    assertNotNull(main, "main(String[]) muss deklariert sein");

    // @EnableScheduling muss auf der Klasse liegen
    EnableScheduling annotation = BrasiNovaApplication.class.getAnnotation(EnableScheduling.class);
    assertTrue(
        annotation != null,
        "BrasiNovaApplication muss mit @EnableScheduling annotiert sein");
  }
}
