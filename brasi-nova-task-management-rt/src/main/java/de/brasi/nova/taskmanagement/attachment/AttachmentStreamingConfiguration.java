package de.brasi.nova.taskmanagement.attachment;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registriert einen eigenen, begrenzten Executor fuer die asynchrone Verarbeitung in Spring MVC
 * (Intake #152). Der {@code StreamingResponseBody} der Anhang-Auslieferung laeuft darauf; ohne
 * diese Konfiguration liefe er auf dem geteilten {@code applicationTaskExecutor} (acht
 * Kern-Threads, unbegrenzte Warteschlange), den auch {@code @Async}-Aufgaben der Anwendung nutzen.
 *
 * <p>Der Executor ist bewusst <b>keine Bean</b>: ein {@code Executor}-Bean wuerde die
 * Auto-Konfiguration von {@code applicationTaskExecutor} abschalten und damit {@code @Async}
 * umlenken. Er gehoert dieser Klasse und wird mit ihr beendet.
 *
 * <p>Die Reihenfolge {@link Ordered#LOWEST_PRECEDENCE} stellt sicher, dass diese Konfiguration nach
 * dem Adapter von Spring Boot ({@code @Order(0)}) laeuft: Boot setzt dort {@code
 * applicationTaskExecutor} und die Vorgabe-Frist aus {@code spring.mvc.async.request-timeout}; hier
 * wird nur der Executor ersetzt, die Frist bleibt unberuehrt.
 */
@Configuration
@Order(Ordered.LOWEST_PRECEDENCE)
@EnableConfigurationProperties(AttachmentStreamingProperties.class)
public class AttachmentStreamingConfiguration implements WebMvcConfigurer, DisposableBean {

  private final ThreadPoolTaskExecutor executor;

  /**
   * Erzeugt und startet den Executor aus der Konfiguration.
   *
   * @param properties Groessen von Pool und Warteschlange sowie Namensanfang der Threads
   */
  public AttachmentStreamingConfiguration(final AttachmentStreamingProperties properties) {
    this.executor = createExecutor(properties);
  }

  /** Baut den begrenzten Pool; Threads entstehen erst bei Bedarf. */
  private static ThreadPoolTaskExecutor createExecutor(
      final AttachmentStreamingProperties properties) {
    final ThreadPoolTaskExecutor created = new ThreadPoolTaskExecutor();
    created.setCorePoolSize(properties.coreSize());
    created.setMaxPoolSize(properties.maxSize());
    created.setQueueCapacity(properties.queueCapacity());
    created.setThreadNamePrefix(properties.threadNamePrefix());
    created.initialize();
    return created;
  }

  @Override
  public void configureAsyncSupport(final AsyncSupportConfigurer configurer) {
    configurer.setTaskExecutor(executor);
  }

  /** Beendet den Pool mit dem Kontext; laufende Auslieferungen werden unterbrochen. */
  @Override
  public void destroy() {
    executor.shutdown();
  }
}
