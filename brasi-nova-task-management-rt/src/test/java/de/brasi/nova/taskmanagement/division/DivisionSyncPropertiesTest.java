package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Prueft {@link DivisionSyncProperties} (Intake #190, Task 2) ohne Spring-Kontext: die
 * Pflichtpruefung bei {@code enabled=true} und die Vorgaben aus {@code application.properties} per
 * {@link Binder} (Muster {@code AttachmentPropertiesTest}).
 *
 * <p>Basis-URL, Token-URL, Client-ID und Secret stehen nur im Config Server; die Fehlermeldung nennt
 * fehlende Schluessel, nie Werte.
 */
class DivisionSyncPropertiesTest {

  private static final String PREFIX = "task-management.division-sync";

  private static final String BASE_URL = "https://application.example.test";
  private static final String TOKEN_URL = "https://identity.example.test/oauth2/token";
  private static final String CLIENT_ID = "tm-test-client";
  private static final String CLIENT_SECRET = "test-secret-value";

  @Test
  void i190_disabled_needs_no_values() {
    props(false, null, null, null, null).requireCompleteWhenEnabled("");
  }

  @Test
  void i190_enabled_without_values_fails_fast() {
    assertThatThrownBy(() -> props(true, null, null, null, null).requireCompleteWhenEnabled("dev-ui"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("application-base-url")
        .hasMessageContaining("oauth2.token-url")
        .hasMessageContaining("oauth2.client-id")
        .hasMessageContaining("oauth2.client-secret");
  }

  @Test
  void i190_enabled_without_event_profile_fails_fast() {
    final DivisionSyncProperties complete =
        props(true, BASE_URL, TOKEN_URL, CLIENT_ID, CLIENT_SECRET);

    // alle Werte vorhanden: mit Profil kein Fehler
    complete.requireCompleteWhenEnabled("dev-ui");

    assertThatThrownBy(() -> complete.requireCompleteWhenEnabled(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ksf.event.profile")
        .hasMessageNotContaining("application-base-url")
        .hasMessageNotContaining(CLIENT_SECRET)
        .hasMessageNotContaining(CLIENT_ID);
  }

  @Test
  void i190_defaults_from_application_properties() throws IOException {
    final Properties raw =
        PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
    final DivisionSyncProperties bound =
        new Binder(new MapConfigurationPropertySource(raw))
            .bind(PREFIX, DivisionSyncProperties.class)
            .get();

    // URL- und OAuth-Werte nur im Config Server
    assertThat(raw)
        .doesNotContainKey(PREFIX + ".application-base-url")
        .doesNotContainKey(PREFIX + ".oauth2.token-url")
        .doesNotContainKey(PREFIX + ".oauth2.client-id")
        .doesNotContainKey(PREFIX + ".oauth2.client-secret");

    assertThat(bound.enabled()).isFalse();
    assertThat(bound.oauth2().scope()).isEqualTo("masterdata.sync.read");
    assertThat(bound.maxDeliveryAttempts()).isEqualTo(5);
    assertThat(bound.http().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(bound.http().readTimeout()).isEqualTo(Duration.ofSeconds(15));
    assertThat(bound.resync().cron()).isEqualTo("0 30 3 * * *");
    assertThat(bound.receiptRetention()).isEqualTo(Duration.ofDays(30));
    assertThat(bound.subscriptionName()).isEqualTo("task-management.division-membership");
    assertThat(bound.destination())
        .startsWith("jms.topic.brasi-nova.")
        .endsWith(".divisions.DivisionMembershipChanged");
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  /** Baut Properties mit Vorgabewerten; nur Schalter, Basis-URL und OAuth-Werte variieren. */
  private static DivisionSyncProperties props(
      final boolean enabled,
      final String applicationBaseUrl,
      final String tokenUrl,
      final String clientId,
      final String clientSecret) {
    return new DivisionSyncProperties(
        enabled,
        "jms.topic.brasi-nova.dev-ui.divisions.DivisionMembershipChanged",
        "task-management.division-membership",
        applicationBaseUrl,
        new DivisionSyncProperties.OAuth2(tokenUrl, clientId, clientSecret, "masterdata.sync.read"),
        5,
        new DivisionSyncProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(15)),
        new DivisionSyncProperties.Resync("0 30 3 * * *"),
        Duration.ofDays(30));
  }
}
