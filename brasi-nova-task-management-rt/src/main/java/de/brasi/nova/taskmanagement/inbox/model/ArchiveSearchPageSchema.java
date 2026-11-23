package de.brasi.nova.taskmanagement.inbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reine OpenAPI-Beschreibung der Antwort von {@code GET /api/v1/tasks/archive/search} (Intake
 * #149). Zur Laufzeit liefert der Endpunkt eine {@link
 * de.brasi.nova.taskmanagement.paging.CursorCollectionModel} von {@link TaskSummaryModel}; diese
 * Klassen beschreiben genau diese Wire-Form, werden aber nie instanziiert.
 *
 * <p>Warum eigene Schemas: Das von springdoc erzeugte Schema der Cursor-Collection verweist auf die
 * gemeinsamen Komponenten {@code Links}/{@code Link} (spring-hateoas) und auf die KSF-Felder {@code
 * id}/{@code modelState} ohne Beschreibung. Das OpenAPI-Gate verlangt fuer jede neue Operation
 * vollstaendig beschriebene Felder; eine Aenderung der gemeinsamen Komponenten wuerde dagegen die
 * Fingerprints aller Nachbar-Operationen in der Basisliste verschieben. Die Feldmenge von {@link
 * Item} haelt {@code ArchiveSearchPageSchemaTest} deckungsgleich mit {@link TaskSummaryModel}.
 */
public final class ArchiveSearchPageSchema {

  /**
   * Gueltig kodierter Beispiel-Cursor der Archivsuche fuer die OpenAPI-Doku (5 Token: asOf,
   * lastActivityAt, externalId, Generation, Scope-Token fuer query "Quartalsbericht" und filter
   * "all"). Mit dem echten Codec ({@code KeysetCursor}) erzeugt; {@code ArchiveSearchTermTest}
   * prueft, dass er dekodierbar ist und zum Scope passt. Die Zeit-Token sind lokale Wanduhr der
   * Persistenz-Zone (Berliner Zeit); {@code ArchiveSearchOpenApiExamplesTest} prueft, dass {@code
   * asOf} und {@code lastActivityAt} im 200-Beispiel dazu passen.
   */
  public static final String EXAMPLE_CURSOR =
      "MjAyNi0xMS0xN1QxMTowMDowMC4xMjM0NTYfMjAyNi0xMS0xNlQwOTozMDowMC41MDAfM2YyYjdjMWUtOGE0ZC00ZTU5LTljM2EtMGIxZDJlM2Y0YTViHzMfMjAyOWIzYjlhODAwMjRhNA";

  private ArchiveSearchPageSchema() {}

  /**
   * Trefferseite der Archivsuche (HAL-Collection mit Cursor-Feldern).
   *
   * @param embedded eingebettete Treffer
   * @param links HAL-Links der Collection
   * @param nextCursor Cursor der naechsten Seite
   * @param hasMore weitere Seite vorhanden
   * @param asOf Wasserzeichen des Scrolls
   */
  @Schema(
      name = "ArchiveSearchPage",
      description =
          "Keyset-paginierte HAL-Collection der Archivaufgaben des angemeldeten Benutzers, die den"
              + " Suchbegriff treffen.")
  public record Page(
      @JsonProperty("_embedded")
          @Schema(
              description =
                  "Eingebettete Treffer der Seite. Fehlt, wenn die Seite keine Treffer enthaelt.")
          Embedded embedded,
      @JsonProperty("_links")
          @Schema(
              description =
                  "HAL-Links der Collection je Relation, etwa Anlege-Aktionen bei passendem Recht.")
          Map<String, HalLink> links,
      @Schema(
              description =
                  "Opaker Cursor der naechsten Seite; null, wenn keine weitere Seite existiert.",
              example = EXAMPLE_CURSOR)
          String nextCursor,
      @Schema(description = "Ob eine weitere Seite existiert.", example = "false") boolean hasMore,
      @Schema(
              description =
                  "Wasserzeichen des Scrolls (UTC); alle Seiten eines Scrolls tragen denselben Wert.",
              example = "2026-11-17T10:00:00Z")
          Instant asOf) {}

  /**
   * Eingebettete Treffer unter {@code _embedded}.
   *
   * @param tasks Treffer der Seite
   */
  @Schema(name = "ArchiveSearchEmbedded", description = "Eingebettete Treffer der Archivsuche.")
  public record Embedded(
      @ArraySchema(
              arraySchema =
                  @Schema(
                      description =
                          "Gefundene Archivaufgaben, neueste Aktivitaet zuerst; jede Aufgabe"
                              + " hoechstens einmal."))
          List<Item> tasks) {}

  /**
   * Ein Treffer (Wire-Form von {@link TaskSummaryModel}).
   *
   * @param id UUID der Aufgabe
   * @param modelState Lebenszyklus-Zustand
   * @param links HAL-Links der Aufgabe
   * @param title Titel
   * @param statusCode Status-CODE
   * @param priorityCode Prioritaets-CODE
   * @param dueDate Faelligkeitsdatum
   * @param lastActivityAt letzte Aktivitaet
   * @param commentCount Anzahl Kommentare
   * @param attachmentCount Anzahl der aktiven Anhaenge an Aufgabe und Kommentaren
   * @param createdById Ersteller
   * @param createdAt Erstellzeitpunkt
   * @param description Beschreibung
   * @param assigneeIds Zugewiesene
   * @param participantIds Beteiligte
   * @param tagIds Tags
   * @param isPinned angepinnt
   * @param isFlagged markiert
   * @param isMuted stummgeschaltet
   * @param isDone abgeschlossen
   * @param exit Austritts-Info
   */
  @Schema(name = "ArchiveSearchItem", description = "Gefundene Archivaufgabe (Zusammenfassung).")
  public record Item(
      @Schema(description = "UUID der Aufgabe.", example = "3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b")
          UUID id,
      @Schema(description = "Lebenszyklus-Zustand des Eintrags.", example = "ACTIVE")
          String modelState,
      @JsonProperty("_links")
          @Schema(
              description =
                  "HAL-Links der Aufgabe je Relation (self und Aktionen nach Recht und"
                      + " Mitgliedschaft).")
          Map<String, HalLink> links,
      @Schema(description = "Titel der Aufgabe.", example = "Quartalsbericht erstellen")
          String title,
      @Schema(description = "Aktueller Status-CODE.", example = "DONE") String statusCode,
      @Schema(description = "Prioritaets-CODE.", example = "NORMAL") String priorityCode,
      @Schema(description = "Optionales Faelligkeitsdatum.", example = "2026-12-31")
          LocalDate dueDate,
      @Schema(
              description = "Zeitpunkt der letzten Aktivitaet (UTC).",
              example = "2026-11-16T09:30:00Z")
          Instant lastActivityAt,
      @Schema(description = "Anzahl der Kommentare.", example = "2") int commentCount,
      @Schema(
              description =
                  "Anzahl der Anhänge (denormalisiert). Gezählt werden aktive Anhänge an der"
                      + " Aufgabe und an ihren aktiven Kommentaren.",
              example = "3")
          int attachmentCount,
      @Schema(
              description = "UUID des Erstellers oder null.",
              example = "550e8400-e29b-41d4-a716-446655440000")
          UUID createdById,
      @Schema(description = "Erstellzeitpunkt der Aufgabe (UTC).", example = "2026-11-01T06:30:00Z")
          Instant createdAt,
      @Schema(description = "Optionale Beschreibung.", example = "Zahlen aus dem Lager abgleichen")
          String description,
      @ArraySchema(
              arraySchema =
                  @Schema(description = "UUIDs der aktiven Zugewiesenen (ohne Austritt)."),
              schema = @Schema(description = "UUID eines Zugewiesenen."))
          List<UUID> assigneeIds,
      @ArraySchema(
              arraySchema =
                  @Schema(
                      description =
                          "UUIDs der aktiven Beteiligten (ohne Zugewiesene und Ersteller)."),
              schema = @Schema(description = "UUID eines Beteiligten."))
          List<UUID> participantIds,
      @ArraySchema(
              arraySchema =
                  @Schema(description = "UUIDs der zugeordneten Tags (auch katalog-inaktive)."),
              schema = @Schema(description = "UUID eines Tags."))
          List<UUID> tagIds,
      @JsonProperty("isPinned")
          @Schema(description = "Persoenlicher Marker: angepinnt.", example = "false")
          boolean isPinned,
      @JsonProperty("isFlagged")
          @Schema(description = "Persoenlicher Marker: markiert.", example = "false")
          boolean isFlagged,
      @JsonProperty("isMuted")
          @Schema(description = "Persoenlicher Marker: stummgeschaltet.", example = "false")
          boolean isMuted,
      @JsonProperty("isDone")
          @Schema(description = "Abgeschlossen (Status DONE oder CANCELLED).", example = "true")
          boolean isDone,
      // Eigene Komponente statt ExitModel: swagger-core uebernimmt die Beschreibung der zuerst
      // gesehenen Property in die gemeinsame Komponente; ein Verweis von hier aus veraendert
      // ExitModel je nach Reihenfolge und damit die Basislisten-Fingerprints der Nachbarn.
      @Schema(description = "Austritts-Info des Anfragers; null, wenn er nicht ausgetreten ist.")
          Exit exit) {}

  /**
   * Austritts-Info eines Treffers (Wire-Form von {@link ExitModel}).
   *
   * @param kind Austrittsart
   * @param handedOverToIds Uebergabeziele
   * @param exitedAt Austrittszeitpunkt (UTC)
   */
  @Schema(name = "ArchiveSearchExit", description = "Austritts-Info des Anfragers.")
  public record Exit(
      @Schema(
              description = "Austrittsart: 'handover' (Uebergabe) oder 'unsubscribed' (Abmeldung).",
              example = "handover")
          String kind,
      @ArraySchema(
              arraySchema =
                  @Schema(
                      description =
                          "Uebergabeziele bei Handover; leer bei Abmeldung oder Handover ohne"
                              + " Ziele."),
              schema = @Schema(description = "UUID eines Uebergabeziels."))
          List<UUID> handedOverToIds,
      @Schema(
              description =
                  "Zeitpunkt des Austritts (ISO 8601, UTC) bei Uebergabe und Abmeldung. null nur"
                      + " bei Altdaten, fuer die kein Austrittszeitpunkt erfasst ist.",
              example = "2026-11-17T09:30:00Z",
              nullable = true)
          Instant exitedAt) {}

  /**
   * problem+json-Antwort der Security-Filterkette bei fehlender Anmeldung (401).
   *
   * @param type Problem-Typ
   * @param title Kurztitel
   * @param status HTTP-Status
   * @param detail stabiles Detail-Label
   * @param requestId Korrelations-ID
   */
  @Schema(
      name = "ArchiveSearchProblem",
      description = "Fehlerantwort (problem+json) der Security-Filterkette.")
  public record Problem(
      @Schema(description = "Problem-Typ (immer about:blank).", example = "about:blank")
          String type,
      @Schema(description = "Kurztitel des Fehlers.", example = "Unauthorized") String title,
      @Schema(description = "HTTP-Statuscode.", example = "401") int status,
      @Schema(
              description = "Stabiles, wertfreies Detail-Label.",
              example = "Authentifizierung erforderlich")
          String detail,
      @Schema(
              description =
                  "Korrelations-ID aus dem Header X-Request-Id; fehlt, wenn keine gesendet wurde.",
              example = "req-4711")
          String requestId) {}

  /**
   * HAL-Link.
   *
   * @param href Ziel-URI
   * @param templated ob {@code href} ein URI-Template ist
   */
  @Schema(name = "ArchiveSearchLink", description = "HAL-Link einer Relation.")
  public record HalLink(
      @Schema(description = "Ziel-URI des Links.", example = "/api/v1/tasks/3f2b7c1e") String href,
      @Schema(
              description = "Ob href ein URI-Template ist; fehlt bei festen Links.",
              example = "false")
          Boolean templated) {}
}
