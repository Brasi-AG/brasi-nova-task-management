package de.brasi.nova.taskmanagement.division;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Registriert {@link DivisionSyncProperties} und das {@link RestTemplate} fuer die S2S-Abrufe bei
 * application (Intake #190).
 *
 * <p>Das {@code divisionSyncRestTemplate} setzt je Anfrage den Bearer-Header aus {@link
 * DivisionSyncTokenProvider}. Es ist kein Standard-Kandidat ({@code defaultCandidate = false}):
 * eine Injektion nur nach Typ erhaelt es nie, und das allgemeine {@code RestTemplate} von KSF
 * ({@code @ConditionalOnMissingBean}) bleibt bestehen. So geht das Token an keinen anderen Host.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DivisionSyncProperties.class)
public class DivisionHttpConfig {

  /**
   * Client fuer die Abrufe der Abteilungsmitglieder: Zeitgrenzen aus {@link
   * DivisionSyncProperties.Http}, Bearer-Token je Anfrage.
   *
   * @param restTemplateBuilder Builder von Spring Boot
   * @param properties Konfiguration des Abteilungs-Abgleichs
   * @param tokenProvider Quelle des S2S-Tokens
   * @return konfiguriertes {@link RestTemplate}
   */
  @Bean(defaultCandidate = false)
  @Qualifier("divisionSyncRestTemplate") public RestTemplate divisionSyncRestTemplate(
      final RestTemplateBuilder restTemplateBuilder,
      final DivisionSyncProperties properties,
      final DivisionSyncTokenProvider tokenProvider) {
    return withTimeouts(restTemplateBuilder, properties)
        .additionalInterceptors(
            (request, body, execution) -> {
              request.getHeaders().setBearerAuth(tokenProvider.accessToken());
              return execution.execute(request, body);
            })
        .build();
  }

  /**
   * Uebernimmt die Zeitgrenzen aus {@link DivisionSyncProperties.Http}, soweit gesetzt.
   *
   * @param builder Ausgangs-Builder
   * @param properties Konfiguration des Abteilungs-Abgleichs
   * @return Builder mit Zeitgrenzen
   */
  static RestTemplateBuilder withTimeouts(
      final RestTemplateBuilder builder, final DivisionSyncProperties properties) {
    RestTemplateBuilder result = builder;
    final DivisionSyncProperties.Http http = properties.http();
    if (http != null && http.connectTimeout() != null) {
      result = result.connectTimeout(http.connectTimeout());
    }
    if (http != null && http.readTimeout() != null) {
      result = result.readTimeout(http.readTimeout());
    }
    return result;
  }
}
