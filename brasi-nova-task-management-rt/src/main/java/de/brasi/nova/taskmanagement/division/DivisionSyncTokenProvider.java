package de.brasi.nova.taskmanagement.division;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Holt das S2S-Token fuer die Abrufe bei application per client_credentials gegen KPL Identity
 * (Basic Auth, Scope aus {@link DivisionSyncProperties.OAuth2}).
 *
 * <p>Das Token bleibt im Speicher, bis 30 Sekunden vor Ablauf von {@code expires_in}. Abgerufen
 * wird erst beim ersten Aufruf von {@link #accessToken()}, nie beim Start; die Bean prueft beim
 * Start auch keine Werte (Pflichtpruefung nur bei {@code enabled=true} ueber {@link
 * DivisionSyncProperties#requireCompleteWhenEnabled(String)}).
 */
@Component
public class DivisionSyncTokenProvider {

  /** Sicherheitsabstand vor dem Ablauf, ab dem ein neues Token geholt wird. */
  private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

  private final DivisionSyncProperties properties;
  private final RestTemplate restTemplate;
  private final Clock clock;

  private String cachedToken;
  private Instant renewAt;

  /**
   * Spring-Konstruktor: eigenes {@link RestTemplate} ohne Bearer-Interceptor, mit den Zeitgrenzen
   * aus {@link DivisionSyncProperties.Http}.
   *
   * @param properties Konfiguration des Abteilungs-Abgleichs
   * @param restTemplateBuilder Builder von Spring Boot
   */
  @Autowired
  public DivisionSyncTokenProvider(
      final DivisionSyncProperties properties, final RestTemplateBuilder restTemplateBuilder) {
    this(
        properties,
        DivisionHttpConfig.withTimeouts(restTemplateBuilder, properties).build(),
        Clock.systemUTC());
  }

  /**
   * Konstruktor fuer Tests mit eigenem {@link RestTemplate} und eigener Uhr.
   *
   * @param properties Konfiguration des Abteilungs-Abgleichs
   * @param restTemplate Client fuer den Token-Endpunkt
   * @param clock Uhr fuer Abrufzeit und Ablauf
   */
  DivisionSyncTokenProvider(
      final DivisionSyncProperties properties, final RestTemplate restTemplate, final Clock clock) {
    this.properties = properties;
    this.restTemplate = restTemplate;
    this.clock = clock;
  }

  /**
   * Liefert ein gueltiges Access-Token, aus dem Speicher oder neu geholt.
   *
   * @return Access-Token fuer den Bearer-Header
   * @throws DivisionFetchException wenn der Token-Endpunkt nicht oder unbrauchbar antwortet
   */
  public synchronized String accessToken() {
    final Instant now = clock.instant();
    if (cachedToken != null && now.isBefore(renewAt)) {
      return cachedToken;
    }
    final TokenResponse response = requestToken();
    cachedToken = response.accessToken();
    renewAt = now.plusSeconds(response.expiresIn()).minus(EXPIRY_MARGIN);
    return cachedToken;
  }

  private TokenResponse requestToken() {
    final DivisionSyncProperties.OAuth2 oauth2 = properties.oauth2();
    if (oauth2 == null) {
      throw new DivisionFetchException(
          "Token-Abruf nicht möglich: " + DivisionSyncProperties.PREFIX + ".oauth2 fehlt.");
    }
    final HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth(oauth2.clientId(), oauth2.clientSecret(), StandardCharsets.UTF_8);
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("scope", oauth2.scope());

    final TokenResponse response;
    try {
      response =
          restTemplate.postForObject(
              oauth2.tokenUrl(), new HttpEntity<>(form, headers), TokenResponse.class);
    } catch (final RestClientResponseException e) {
      throw new DivisionFetchException(
          "Token-Abruf fehlgeschlagen: HTTP " + e.getStatusCode().value(), e);
    } catch (final RestClientException e) {
      throw new DivisionFetchException("Token-Abruf fehlgeschlagen (Verbindung oder Antwort).", e);
    }
    if (response == null
        || response.accessToken() == null
        || response.accessToken().isBlank()
        || response.expiresIn() <= 0) {
      throw new DivisionFetchException(
          "Token-Abruf fehlgeschlagen: Antwort ohne access_token oder expires_in.");
    }
    return response;
  }

  /**
   * Antwort des Token-Endpunkts; weitere Felder werden ignoriert.
   *
   * @param accessToken Access-Token
   * @param expiresIn Gueltigkeit in Sekunden
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") long expiresIn) {}
}
