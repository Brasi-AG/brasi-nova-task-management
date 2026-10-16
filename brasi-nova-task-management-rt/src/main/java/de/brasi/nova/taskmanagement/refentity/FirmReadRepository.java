package de.brasi.nova.taskmanagement.refentity;

import io.keel.framework.api.repository.NovaRepository;

/**
 * Read-only-Repository fuer {@link Firm} (PROD_FIRMA). Pflicht fuer den {@code
 * CachingIdMappingService}: ohne registriertes {@code NovaRepository<Firm>} wirft er beim Start
 * "Kein NovaRepository registriert". Keine Schreibpfade.
 */
public interface FirmReadRepository extends NovaRepository<Firm> {}
