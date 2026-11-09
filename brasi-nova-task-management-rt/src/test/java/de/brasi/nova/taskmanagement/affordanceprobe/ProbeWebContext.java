package de.brasi.nova.taskmanagement.affordanceprobe;

import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>Gemeinsamer Test-Context fuer die Affordance-Proben P1/P2/P3/P5. Bootet denselben vollen
 * Web-/HATEOAS-/Jackson-Stack wie die reale {@code BrasiNovaApplication} ueber
 * Boot-{@code @EnableAutoConfiguration} (statt einer handgepflegten Autoconfig-Liste — der
 * KSF-Web-Graph ist eng verzahnt). Dadurch sind der globale {@code NovaFindResponseAdvice}
 * (ResponseBodyAdvice), der {@code @EnableNovaHateoas}-Scan und der {@code SelfLinkEnricher}
 * genauso live wie in Produktion.
 *
 * <p>Der {@code @ComponentScan} ist auf das Probe-Paket beschraenkt; die reale Repository-Schicht
 * wird NICHT gescannt (nur {@code @EnableNovaRepositories} auf die Entity-/Repo-Pakete, damit die
 * Enrichment-Infrastruktur ihre Persistenz-Kollaborateure findet). Die Proben lesen keine Tabellen;
 * ohne MSSQL-Zugang wird der IT uebersprungen ({@code @RefSchemaItEnabled} an den Testklassen).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {JdbcRepositoriesAutoConfiguration.class})
@ComponentScan(
    basePackages = {"de.brasi.nova.taskmanagement.affordanceprobe"},
    excludeFilters = {
      @ComponentScan.Filter(
          type = org.springframework.context.annotation.FilterType.ANNOTATION,
          classes = SpringBootConfiguration.class)
    })
@EnableTransactionManagement
@EnableNovaRepositories(
    basePackages = {
      "de.brasi.nova.taskmanagement.entity",
      "de.brasi.nova.taskmanagement.repository",
      "de.brasi.nova.taskmanagement.refentity",
    })
public class ProbeWebContext {

  @Bean
  AuditorProvider probeAuditorProvider() {
    return () -> "affordance-probe-it";
  }
}
