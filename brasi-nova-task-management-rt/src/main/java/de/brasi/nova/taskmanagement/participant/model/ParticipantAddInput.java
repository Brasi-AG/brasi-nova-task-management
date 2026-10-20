package de.brasi.nova.taskmanagement.participant.model;

import io.keel.framework.api.validation.EntityValidationException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Eingabe-DTO fuer das additive Hinzufuegen von Teilnehmern zu einer Aufgabe
 * ({@code POST /api/v1/tasks/{id}/participants}).
 *
 * <p>Enthaelt die hinzuzufuegenden Teilnehmer-UUIDs (PROD_BENUTZER.EXTERNALID). Die Operation ist
 * <b>additiv</b> (keine PUT-Set-Semantik) und <b>idempotent</b>: bereits aktive Teilnehmer werden
 * uebersprungen. Verliehen wird ausschliesslich die Teilnehmer-Rolle (PARTICIPANT_FLAG), NIE die
 * Assignee-Rolle.
 *
 * <p>Bewusst {@link List} statt {@link java.util.Set}: die ROHE Laenge wird gegen
 * {@link #MAX_PARTICIPANTS} geprueft, BEVOR dedupliziert wird. Sonst koennten z.B. 101 identische
 * UUIDs die Obergrenze umgehen (BE-4-Lesson). Die Deduplizierung erfolgt im Service.
 *
 * <p>Validierungsregeln:
 * <ul>
 *   <li>{@code participantIds} darf NICHT {@code null} sein (fehlendes Feld / explizit null -> 400).
 *       Ein explizites leeres Array {@code []} ist erlaubt und bewirkt einen No-Op.
 *   <li>Kein Element darf {@code null} sein.
 *   <li>Die rohe Anzahl darf {@link #MAX_PARTICIPANTS} nicht ueberschreiten (auch Duplikate zaehlen).
 * </ul>
 *
 * @param participantIds hinzuzufuegende Teilnehmer-UUIDs (darf leer sein [{@code []}], aber nicht
 *     null; kein null-Element; rohe Laenge hoechstens {@link #MAX_PARTICIPANTS})
 * @throws EntityValidationException wenn {@code participantIds} null ist, ein null-Element enthaelt
 *     oder die Obergrenze (roh) ueberschreitet
 */
public record ParticipantAddInput(
    @ArraySchema(
            schema =
                @Schema(
                    type = "string",
                    format = "uuid",
                    description = "UUID eines hinzuzufuegenden Teilnehmers (PROD_BENUTZER.EXTERNALID)."))
        @Schema(
            description =
                "Hinzuzufuegende Teilnehmer-UUIDs (additiv, idempotent). Leeres Array [] = No-Op.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<UUID> participantIds) {

  /**
   * Obergrenze fuer die Anzahl in EINEM Aufruf hinzuzufuegender Teilnehmer. Begrenzt die nachgelagerte
   * {@code findActiveByUuids}-{@code IN (:uuids)}-Validierung, damit eine (absurd) grosse Menge nicht
   * das SQL-Server-Parameterlimit (2100) sprengt. 100 ist konsistent zur
   * {@code AssigneeUpdateInput}-Obergrenze und zur Keyset-Seitengroessen-Clamp-Konvention [1,100].
   */
  private static final int MAX_PARTICIPANTS = 100;

  /**
   * Kompakter Konstruktor: validiert null, null-Elemente und die ROHE Obergrenze (vor jeder
   * Deduplizierung). Nur ein explizites leeres Array ist ausserhalb der Obergrenze zulaessig.
   */
  public ParticipantAddInput {
    if (participantIds == null) {
      throw new EntityValidationException(
          "Das Feld 'participantIds' darf nicht null sein."
              + " Verwende ein leeres Array [] fuer einen No-Op.");
    }
    // ROHE Laenge VOR jeder Deduplizierung pruefen (BE-4-Lesson): Duplikate umgehen die Grenze NICHT.
    if (participantIds.size() > MAX_PARTICIPANTS) {
      throw new EntityValidationException(
          "Das Feld 'participantIds' darf hoechstens " + MAX_PARTICIPANTS + " Eintraege enthalten.");
    }
    if (participantIds.stream().anyMatch(Objects::isNull)) {
      throw new EntityValidationException(
          "Das Feld 'participantIds' darf keine null-Elemente enthalten.");
    }
  }
}
