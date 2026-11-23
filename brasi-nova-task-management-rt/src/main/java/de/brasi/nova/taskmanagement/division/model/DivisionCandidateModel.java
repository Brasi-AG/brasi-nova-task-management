package de.brasi.nova.taskmanagement.division.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Ein Vorschlag fuer die Abteilungserwaehnung (Intake #190): eine aktive Abteilung des Mandanten.
 *
 * @param divisionId UUID der Abteilung in application (Wert fuer {@code divisionMentions})
 * @param name Anzeigename, {@code null}, solange application keinen liefert
 * @param memberCount Mitglieder der Abteilung mit aktivem Benutzer im Mandanten; die
 *     Personengrenze der Erwaehnung zaehlt dagegen alle Mitglieder
 */
@Schema(
    description =
        "Vorschlag fuer die Erwaehnung einer Abteilung im Kommentar: eine aktive Abteilung des"
            + " eigenen Mandanten.")
public record DivisionCandidateModel(
    @Schema(
            description =
                "UUID der Abteilung (Division in application); dieser Wert gehoert in"
                    + " divisionMentions beim Kommentar.",
            example = "5d1c0a3e-2b4f-4c6d-8e9f-0a1b2c3d4e5f",
            requiredMode = Schema.RequiredMode.REQUIRED)
        UUID divisionId,
    @Schema(
            description =
                "Anzeigename der Abteilung; null, solange application fuer diese Abteilung noch"
                    + " keinen Namen geliefert hat (das Frontend zeigt dann einen Ersatz an).",
            example = "Vertrieb",
            nullable = true)
        String name,
    @Schema(
            description =
                "Zahl der Mitglieder der Abteilung (mit Unterabteilungen) mit aktivem Benutzer im"
                    + " eigenen Mandanten: so viele Personen erreicht eine Erwaehnung hoechstens."
                    + " Zaehlt nur aktive Benutzer; die Personengrenze der Erwaehnung am Kommentar"
                    + " zaehlt dagegen alle Mitglieder, auch solche ohne aktiven Benutzer. Ein"
                    + " Client darf memberCount daher nicht gegen diese Grenze vorpruefen.",
            example = "12",
            requiredMode = Schema.RequiredMode.REQUIRED)
        int memberCount) {}
