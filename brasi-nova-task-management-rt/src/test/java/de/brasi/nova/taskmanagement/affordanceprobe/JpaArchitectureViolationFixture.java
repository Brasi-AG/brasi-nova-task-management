package de.brasi.nova.taskmanagement.affordanceprobe;

import org.springframework.data.repository.CrudRepository;

/**
 * Test-only Negativ-Fixture fuer den KSF-Consumer-Contract-Architektur-Check (PLAN #141 Slice 6).
 *
 * <p>Zaehlt zu den Verboten: Erweiterung eines verbotenen Spring-Data-Repository-Basistyps
 * ({@link CrudRepository}). Die Fixture beweist, dass der Scan-Kern eine solche Verletzung
 * als Problem erkennt (Invertierbarkeits-Beweis).
 *
 * <p>{@code CrudRepository} ist in {@code spring-data-commons} vorhanden (transitiv auf dem
 * Classpath). Da das Modul keinen {@code jakarta.persistence}-Classpath hat, wird die
 * Supertyp-Variante als Negativ-Beweis genutzt statt einer Annotation-Fixture.
 *
 * <p>Liegt im test-only affordanceprobe-Paket -> vom Produktions-Scan durch die praezise
 * Paketgrenze ausgeschlossen (kein startsWith-Leak). Dem Arch-Check-Kern beim
 * Invertierbarkeits-Test EXPLIZIT uebergeben.
 */
public interface JpaArchitectureViolationFixture extends CrudRepository<Object, Long> {
  // Leer - nur das verbotene Interface-Erben zaehlt fuer den Scan-Beweis.
}
