package de.brasi.nova.taskmanagement.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

/**
 * Prueft die Konfiguration der asynchronen Auslieferung (Intake #152, Fix-Runde nach A8) ohne
 * Spring-Kontext: Vorgaben aus {@code application.properties} per {@link Binder} binden, den
 * Executor aus {@link AttachmentStreamingConfiguration} bauen und am {@link AsyncSupportConfigurer}
 * pruefen.
 *
 * <p>Ziel: Der Body einer Auslieferung laeuft auf einem eigenen, begrenzten Pool statt auf dem
 * geteilten {@code applicationTaskExecutor}, und die Frist fuer asynchrone Anfragen ist laenger als
 * die Servlet-Vorgabe von 30 Sekunden.
 */
class AttachmentStreamingPropertiesTest {

  private static final String PREFIX = "task-management.attachments.streaming";
  private static final String TIMEOUT_KEY = "spring.mvc.async.request-timeout";

  private Properties raw;
  private AttachmentStreamingProperties bound;

  @BeforeEach
  void setUp() throws IOException {
    raw = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
    bound =
        new Binder(new MapConfigurationPropertySource(raw))
            .bind(PREFIX, AttachmentStreamingProperties.class)
            .get();
  }

  @Test
  void i152_streaming_defaults_are_bound_and_bounded() {
    assertThat(bound.coreSize()).isEqualTo(16);
    assertThat(bound.maxSize()).isEqualTo(32);
    assertThat(bound.queueCapacity()).isEqualTo(50);
    assertThat(bound.threadNamePrefix()).isEqualTo("attachment-stream-");
    assertThat(bound.retryAfter()).isEqualTo(Duration.ofSeconds(5));
    assertThat(bound.maxSize()).isGreaterThanOrEqualTo(bound.coreSize());
  }

  @Test
  void i152_async_request_timeout_exceeds_servlet_default() {
    final Duration timeout =
        new Binder(new MapConfigurationPropertySource(raw)).bind(TIMEOUT_KEY, Duration.class).get();

    assertThat(timeout).isEqualTo(Duration.ofMinutes(5));
    assertThat(timeout).isGreaterThan(Duration.ofSeconds(30));
  }

  @Test
  void i152_configuration_registers_own_bounded_executor() {
    final AttachmentStreamingConfiguration configuration =
        new AttachmentStreamingConfiguration(bound);
    final ExecutorProbe configurer = new ExecutorProbe();

    try {
      configuration.configureAsyncSupport(configurer);

      assertThat(configurer.executor()).isInstanceOf(ThreadPoolTaskExecutor.class);
      final ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configurer.executor();
      assertThat(executor.getCorePoolSize()).isEqualTo(bound.coreSize());
      assertThat(executor.getMaxPoolSize()).isEqualTo(bound.maxSize());
      assertThat(executor.getQueueCapacity()).isEqualTo(bound.queueCapacity());
      assertThat(executor.getThreadNamePrefix()).isEqualTo(bound.threadNamePrefix());
    } finally {
      configuration.destroy();
    }
  }

  @Test
  void i152_configuration_stops_executor_on_destroy() {
    final AttachmentStreamingConfiguration configuration =
        new AttachmentStreamingConfiguration(bound);
    final ExecutorProbe configurer = new ExecutorProbe();
    configuration.configureAsyncSupport(configurer);
    final ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configurer.executor();

    configuration.destroy();

    assertThat(executor.getThreadPoolExecutor().isShutdown()).isTrue();
  }

  @Test
  void i152_executor_is_not_a_bean_and_runs_after_boot_adapter() {
    // Ein Executor-Bean schaltet applicationTaskExecutor ab und lenkt @Async um.
    assertThat(Arrays.stream(AttachmentStreamingConfiguration.class.getDeclaredMethods()))
        .noneMatch(method -> method.isAnnotationPresent(Bean.class));
    // Boots Adapter (@Order(0)) setzt applicationTaskExecutor; diese Konfiguration muss danach
    // laufen, sonst wuerde der geteilte Executor sie ueberschreiben.
    final Order order = AttachmentStreamingConfiguration.class.getAnnotation(Order.class);
    assertThat(order).isNotNull();
    assertThat(order.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }

  @Test
  void i152_invalid_values_are_rejected() {
    final Duration wait = Duration.ofSeconds(5);
    assertThatThrownBy(() -> new AttachmentStreamingProperties(0, 4, 10, "x-", wait))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentStreamingProperties(8, 4, 10, "x-", wait))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentStreamingProperties(4, 8, -1, "x-", wait))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentStreamingProperties(4, 8, 10, " ", wait))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentStreamingProperties(4, 8, 10, "x-", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentStreamingProperties(4, 8, 10, "x-", wait.negated()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentStreamingProperties(4, 8, 10, "x-", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Macht den von der Konfiguration gesetzten Executor lesbar ({@code getTaskExecutor} ist
   * geschuetzt).
   */
  private static final class ExecutorProbe extends AsyncSupportConfigurer {

    Object executor() {
      return getTaskExecutor();
    }
  }
}
