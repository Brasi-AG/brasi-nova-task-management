package de.brasi.nova.taskmanagement.division;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration des Abteilungs-Abgleichs (Intake #190), gebunden an {@code
 * task-management.division-sync}.
 *
 * <p>Die Vorgaben stehen in {@code application.properties}. Basis-URL von application, Token-URL,
 * Client-ID und Secret stehen bewusst nur im Config Server je Umgebung. Mit {@code enabled=false}
 * (Vorgabe) wird nichts geprueft und nichts nach aussen aufgerufen.
 *
 * @param enabled schaltet Consumer, Token-Pflichtpruefung und Resync ein
 * @param destination JMS-Topic des Ereignisses {@code DivisionMembershipChanged}
 * @param subscriptionName Name der geteilten, dauerhaften Subscription; er ist ein Queue-Name des
 *     ganzen Brokers und traegt deshalb das Profil
 * @param applicationBaseUrl Basis-URL von application fuer die S2S-Abrufe
 * @param oauth2 client_credentials-Zugang gegen KPL Identity
 * @param maxDeliveryAttempts Zustellversuche, bevor eine Nachricht endgueltig verworfen wird
 * @param http Zeitgrenzen der S2S-Aufrufe
 * @param resync Zeitplan der Selbstheilung
 * @param receiptRetention Aufbewahrung der Ereignis-Quittungen
 */
@ConfigurationProperties(DivisionSyncProperties.PREFIX)
public record DivisionSyncProperties(
    boolean enabled,
    String destination,
    String subscriptionName,
    String applicationBaseUrl,
    OAuth2 oauth2,
    int maxDeliveryAttempts,
    Http http,
    Resync resync,
    Duration receiptRetention) {

  /** Praefix der Konfigurationsschluessel. */
  public static final String PREFIX = "task-management.division-sync";

  /**
   * Prueft bei eingeschaltetem Abgleich, dass alle Pflichtwerte gesetzt sind. Die Meldung nennt nur
   * die fehlenden Schluessel, nie Werte.
   *
   * @param eventProfile Wert von {@code ksf.event.profile}
   * @throws IllegalStateException wenn {@code enabled=true} und ein Pflichtwert fehlt
   */
  public void requireCompleteWhenEnabled(final String eventProfile) {
    if (!enabled) {
      return;
    }
    final List<String> missing = new ArrayList<>();
    if (isBlank(destination)) {
      missing.add(PREFIX + ".destination");
    }
    if (isBlank(subscriptionName)) {
      missing.add(PREFIX + ".subscription-name");
    }
    if (isBlank(applicationBaseUrl)) {
      missing.add(PREFIX + ".application-base-url");
    }
    if (oauth2 == null || isBlank(oauth2.tokenUrl())) {
      missing.add(PREFIX + ".oauth2.token-url");
    }
    if (oauth2 == null || isBlank(oauth2.clientId())) {
      missing.add(PREFIX + ".oauth2.client-id");
    }
    if (oauth2 == null || isBlank(oauth2.clientSecret())) {
      missing.add(PREFIX + ".oauth2.client-secret");
    }
    if (oauth2 == null || isBlank(oauth2.scope())) {
      missing.add(PREFIX + ".oauth2.scope");
    }
    if (isBlank(eventProfile)) {
      missing.add("ksf.event.profile");
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Abteilungs-Abgleich ist eingeschaltet ("
              + PREFIX
              + ".enabled=true), aber diese Schlüssel fehlen: "
              + String.join(", ", missing));
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }

  /**
   * client_credentials-Zugang gegen KPL Identity.
   *
   * @param tokenUrl Token-Endpunkt
   * @param clientId Client-ID von task-management
   * @param clientSecret Secret des Clients
   * @param scope angeforderter Scope
   */
  public record OAuth2(String tokenUrl, String clientId, String clientSecret, String scope) {

    /** Gibt das Secret nie in Log oder Meldung aus. */
    @Override
    public String toString() {
      return "OAuth2[tokenUrl=" + tokenUrl + ", clientId=" + clientId + ", scope=" + scope + "]";
    }
  }

  /**
   * Zeitgrenzen der S2S-Aufrufe.
   *
   * @param connectTimeout Verbindungsaufbau
   * @param readTimeout Warten auf die Antwort
   */
  public record Http(Duration connectTimeout, Duration readTimeout) {}

  /**
   * Zeitplan der Selbstheilung.
   *
   * @param cron Spring-Cron-Ausdruck
   */
  public record Resync(String cron) {}
}
