/**
 * Diese Datei ist urheberrechtlich geschützt. Alle Rechte vorbehalten.
 *
 * <p>Diese Datei und die darin enthaltenen Informationen dürfen ohne ausdrückliche schriftliche
 * Genehmigung der Brasi AG Krefeld weder ganz noch teilweise vervielfältigt, weitergegeben,
 * verteilt oder gespeichert werden.
 *
 * <p>Diese Datei ist nur für den internen Gebrauch bestimmt und darf nicht für andere Zwecke
 * verwendet werden.
 *
 * <p>Jede unautorisierte Nutzung, Vervielfältigung oder Verbreitung der Inhalte dieser Datei kann
 * zu straf- und zivilrechtlichen Maßnahmen führen.
 *
 * <p>© Brasi AG Krefeld, 2024. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI-Konfiguration fuer den Task-Management-Service. */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class OpenApiConfig {

  private final ApplicationContext applicationContext;

  @Bean
  OpenAPI customOpenAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Brasi Nova API Dokumentation")
                .version("v1.0")
                .description(
                    """
                        Willkommen zur zentralen **API-Dokumentation der Brasi Nova Plattform**.

                        Diese Dokumentation bietet einen vollständigen Überblick über alle **REST-basierten Schnittstellen**,
                        die zur Integration, Erweiterung und Nutzung der Geschäftslogik zur Verfügung stehen.

                        ---

                        ## Ziel dieser Dokumentation

                        Diese API-Dokumentation dient als **technisches Nachschlagewerk** und **fachliche Orientierung**
                        für Entwickler, Architekten und Systemintegratoren.

                        ---

                        ## Technologische Grundlagen

                        Die Brasi Nova Plattform basiert auf einer modularen **Microservice-Architektur**:

                        - **REST-konforme Endpunkte** mit klaren Ressourcennamen
                        - **HATEOAS** – jede Antwort enthält dynamische Links zu Folgeaktionen
                        - **Standardisierte CRUD- und Suchoperationen**
                        - **Versionierung über URL-Pfade** (z. B. `/api/v1/...`)
                        - **HAL+JSON** als Standardformat für Antwortobjekte
                        """));
  }

  @Bean
  GroupedOpenApi overviewGroup() {
    return GroupedOpenApi.builder().group("overview").pathsToMatch("/api/v1/overview").build();
  }

  @Bean
  GroupedOpenApi allApiGroup() {
    return GroupedOpenApi.builder().group("all").pathsToMatch("/api/v1/**").build();
  }
}
