/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.records.summary.dictionary;

import io.keel.framework.api.model.HasEntityId;
import io.keel.framework.api.model.EntityState;
import java.util.UUID;

/**
 * Leichtgewichtiger Record für die TagSeverity-Entität.
 * Auto-generiert durch Nova CLI.
 */

public record TagSeveritySummary(
    Long id,
    UUID uuid,
    String dictionaryEntryType,
    String entry
) implements HasEntityId {

    @Override
    public Long entityId() {
        return this.id;
    }
}
