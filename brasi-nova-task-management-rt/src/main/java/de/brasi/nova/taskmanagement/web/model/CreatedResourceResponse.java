package de.brasi.nova.taskmanagement.web.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Antwort-DTO fuer Create-Endpunkte: die externe UUID der frisch angelegten Ressource.
 *
 * <p>Dient ausschliesslich der OpenAPI-Typisierung: die Create-Handler geben zur Laufzeit
 * weiterhin {@code Map.of("id", id)} zurueck (wire-identisch {@code {"id":"<uuid>"}}). Ohne dieses
 * konkrete Schema wuerde springdoc die Antwort als untypisiertes Objekt dokumentieren, sodass
 * generierte Clients das {@code id}-Feld nicht als UUID modellieren koennten (Pre-Release-Audit).
 *
 * @param id externe UUID (EXTERNALID) der angelegten Ressource
 */
public record CreatedResourceResponse(
    @Schema(
            description = "Externe UUID der angelegten Ressource.",
            example = "aa0e8400-e29b-41d4-a716-446655440010",
            requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id) {}
