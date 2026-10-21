package de.brasi.nova.taskmanagement.participant.model;

import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import io.keel.framework.api.validation.EntityValidationException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Eingabe-DTO fuer den Austritt des aktuellen Akteurs aus einer Aufgabe
 * ({@code POST /api/v1/tasks/{id}/exit}).
 *
 * <p>Der Akteur setzt auf seinem {@link de.brasi.nova.taskmanagement.entity.TaskParticipant}
 * den {@code EXIT_KIND} und {@code EXIT_AT}. Bei {@link ExitKind#HANDOVER} koennen zusaetzlich
 * Uebergabeziele ({@code handedOverToIds}) und ein optionaler Verbleib ({@code shouldStay})
 * angegeben werden (BE-7).
 *
 * <p>Bewusst {@link List} statt {@link java.util.Set}: die ROHE Laenge wird gegen
 * {@link #MAX_HANDOVER_TARGETS} geprueft, BEVOR dedupliziert wird (BE-4/BE-6-Lesson). Die
 * Deduplizierung erfolgt im Service.
 *
 * <p><b>Nur Form-Validierung im compact-ctor</b> (SPEC F4): exitKind non-null; handedOverToIds
 * null-&gt;[] mit roher Laenge &le; {@link #MAX_HANDOVER_TARGETS} und ohne null-Element; shouldStay
 * null-&gt;false. Die fachliche Semantik (UNSUBSCRIBED-mit-Zielen -&gt; 400; leer-stay -&gt; 400;
 * Selbstziel/unbekannt/fremd -&gt; 400; Creator-409) bleibt im {@code AssigneeService}.
 *
 * @param exitKind        Art des Austritts (HANDOVER | UNSUBSCRIBED), darf nicht null sein
 * @param handedOverToIds Uebergabeziele (nur HANDOVER; null-&gt;[]; rohe Laenge &le; 100; kein
 *     null-Element)
 * @param shouldStay      optionaler Verbleib als Teilnehmer bei HANDOVER (null-&gt;false)
 * @throws EntityValidationException wenn exitKind null ist, handedOverToIds ein null-Element
 *     enthaelt oder die rohe Obergrenze ueberschreitet
 */
public record ExitInput(
    @Schema(
            description =
                "Art des Austritts: HANDOVER (Aufgabe uebergeben) oder UNSUBSCRIBED (abgemeldet).",
            example = "HANDOVER",
            requiredMode = Schema.RequiredMode.REQUIRED)
        ExitKind exitKind,
    @ArraySchema(
            schema =
                @Schema(
                    type = "string",
                    format = "uuid",
                    description = "UUID eines Uebergabeziels (PROD_BENUTZER.EXTERNALID)."))
        @Schema(
            description =
                "Uebergabeziele (nur HANDOVER). Werden zu Assignees; null/weggelassen = [].",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<UUID> handedOverToIds,
    @Schema(
            description =
                "Nur HANDOVER: true = Akteur bleibt Teilnehmer (kein Austritt), false/weggelassen ="
                    + " Akteur verlaesst die Aufgabe.",
            example = "false",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean shouldStay) {

  /**
   * Obergrenze fuer die Anzahl in EINEM Handover angegebener Ziele. Begrenzt die nachgelagerte
   * {@code findActiveByUuids}-{@code IN (:uuids)}-Validierung, damit eine (absurd) grosse Menge
   * nicht das SQL-Server-Parameterlimit (2100) sprengt. 100 ist konsistent zur
   * {@code AssigneeUpdateInput}-/{@code ParticipantAddInput}-Obergrenze.
   */
  private static final int MAX_HANDOVER_TARGETS = 100;

  /**
   * Kompakter Konstruktor: Form-Validierung. exitKind non-null (400), handedOverToIds null-&gt;[]
   * (defensiv kopiert; rohe Laenge &le; {@link #MAX_HANDOVER_TARGETS} VOR Dedup; kein null-Element),
   * shouldStay null-&gt;false.
   */
  public ExitInput {
    if (exitKind == null) {
      throw new EntityValidationException("Das Feld 'exitKind' darf nicht null sein.");
    }
    if (handedOverToIds == null) {
      handedOverToIds = List.of();
    } else {
      // ROHE Laenge VOR jeder Deduplizierung pruefen: Duplikate umgehen die Grenze NICHT.
      if (handedOverToIds.size() > MAX_HANDOVER_TARGETS) {
        throw new EntityValidationException(
            "Das Feld 'handedOverToIds' darf hoechstens "
                + MAX_HANDOVER_TARGETS
                + " Eintraege enthalten.");
      }
      if (handedOverToIds.stream().anyMatch(Objects::isNull)) {
        throw new EntityValidationException(
            "Das Feld 'handedOverToIds' darf keine null-Elemente enthalten.");
      }
      handedOverToIds = List.copyOf(handedOverToIds);
    }
    if (shouldStay == null) {
      shouldStay = Boolean.FALSE;
    }
  }

  /**
   * Komfort-Konstruktor fuer Aufrufer ohne Handover-Ziele (UNSUBSCRIBED oder Selbst-Handover):
   * delegiert mit leeren Zielen und shouldStay=false.
   *
   * @param exitKind Art des Austritts
   */
  public ExitInput(final ExitKind exitKind) {
    this(exitKind, null, null);
  }
}
