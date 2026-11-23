package de.brasi.nova.taskmanagement.division.model;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Antwort der Vorschlags-API fuer Abteilungen ({@code GET /api/v1/tasks/divisions}, Intake #190).
 * Keine Cursor-Seiten: ein Mandant hat wenige Abteilungen, {@code limit} begrenzt die Menge.
 *
 * @param items Vorschlaege, sortiert nach Name (unbenannte zuletzt); bei gleichem Namen stabil nach
 *     UUID in GUID-Sortierung (fachlich bedeutungslos, nicht alphabetisch)
 */
@Schema(description = "Vorschlaege fuer die Erwaehnung von Abteilungen (eine Seite, ohne Cursor).")
public record DivisionCandidatePage(
    @ArraySchema(
            arraySchema =
                @Schema(
                    description =
                        "Aktive Abteilungen des eigenen Mandanten, nach Name sortiert (ohne Name"
                            + " zuletzt). Bei gleichem Namen ist die Reihenfolge stabil, aber eine"
                            + " GUID-Sortierung der divisionId (fachlich bedeutungslos, nicht"
                            + " alphabetisch). Leer, wenn nichts passt.",
                    requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(implementation = DivisionCandidateModel.class))
        List<DivisionCandidateModel> items) {}
