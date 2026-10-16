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
package de.brasi.nova.taskmanagement;

import io.keel.framework.persistence.factory.EnableNovaRepositories;
import io.keel.framework.persistence.validation.EnableNovaValidation;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Einstiegspunkt des Task-Management-Services. */
@SpringBootApplication(
    scanBasePackages = {"de.brasi.nova", "de.brasi.nova.taskmanagement"},
    exclude = {JdbcRepositoriesAutoConfiguration.class})
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
@EnableNovaRepositories()
@EnableNovaValidation
public class BrasiNovaApplication {

  public static void main(String[] args) {
    SpringApplication.run(BrasiNovaApplication.class, args);
  }
}
