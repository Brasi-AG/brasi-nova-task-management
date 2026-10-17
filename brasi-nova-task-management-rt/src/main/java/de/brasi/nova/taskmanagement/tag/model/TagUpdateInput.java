package de.brasi.nova.taskmanagement.tag.model;

import io.keel.framework.api.service.model.PatchValue;

/**
 * Patch-DTO zum Aktualisieren eines Tags.
 *
 * <p>{@link PatchValue#absent()} bedeutet: Feld unveraendert lassen.
 * {@link PatchValue#of(Object)} bedeutet: Feld auf neuen Wert setzen.
 *
 * @param name         neuer Tag-Name (oder absent)
 * @param severityCode neuer CODE des Tag-Schweregrads (oder absent)
 */
public record TagUpdateInput(PatchValue<String> name, PatchValue<String> severityCode) {}
