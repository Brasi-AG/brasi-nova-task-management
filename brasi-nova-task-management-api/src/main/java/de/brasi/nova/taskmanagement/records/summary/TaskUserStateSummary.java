/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.records.summary;

import io.keel.framework.api.model.HasEntityId;
import io.keel.framework.api.model.EntityState;
import java.util.UUID;

/**
 * Leichtgewichtiger Record für die TaskUserState-Entität.
 * Auto-generiert durch Nova CLI.
 */

public record TaskUserStateSummary(
    Long id,
    UUID uuid
) implements HasEntityId {

    @Override
    public Long entityId() {
        return this.id;
    }
}
