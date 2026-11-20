package de.brasi.nova.taskmanagement.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Prueft die Konfiguration des Vorschau-Pools (Intake #152, Codex-Review b43db8d) ohne
 * Spring-Kontext: Vorgaben aus {@code application.properties} per {@link Binder} binden, ungueltige
 * Werte ablehnen; der Generator ist kein {@link Executor} (sonst wuerde er wegen {@code
 * EnableAsync} den {@code applicationTaskExecutor} verdraengen).
 */
class AttachmentThumbnailPoolPropertiesTest {

  private static final String PREFIX = "task-management.attachments.thumbnail";

  private Properties raw;

  @BeforeEach
  void setUp() throws IOException {
    raw = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
  }

  @Test
  void i152_thumbnail_pool_defaults_are_bound() {
    final AttachmentThumbnailPoolProperties bound =
        new Binder(new MapConfigurationPropertySource(raw))
            .bind(PREFIX, AttachmentThumbnailPoolProperties.class)
            .get();

    assertThat(bound.poolSize()).isEqualTo(2);
    assertThat(bound.queueCapacity()).isEqualTo(8);
    assertThat(bound.timeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(bound.threadNamePrefix()).isEqualTo("attachment-thumbnail-");
  }

  @Test
  void i152_thumbnail_settings_still_bind_next_to_pool_keys() {
    final AttachmentProperties.Thumbnail thumbnail =
        new Binder(new MapConfigurationPropertySource(raw))
            .bind(PREFIX, AttachmentProperties.Thumbnail.class)
            .get();

    assertThat(thumbnail.width()).isEqualTo(600);
    assertThat(thumbnail.maxSourcePixels()).isEqualTo(50_000_000L);
  }

  @Test
  void i152_generator_is_not_an_executor() {
    assertThat(Executor.class.isAssignableFrom(AttachmentThumbnailGenerator.class)).isFalse();
  }

  @Test
  void i152_invalid_values_are_rejected() {
    final Duration limit = Duration.ofSeconds(10);
    assertThatThrownBy(() -> new AttachmentThumbnailPoolProperties(0, 8, limit, "x-"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentThumbnailPoolProperties(2, -1, limit, "x-"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentThumbnailPoolProperties(2, 8, Duration.ZERO, "x-"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentThumbnailPoolProperties(2, 8, limit.negated(), "x-"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentThumbnailPoolProperties(2, 8, null, "x-"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttachmentThumbnailPoolProperties(2, 8, limit, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
