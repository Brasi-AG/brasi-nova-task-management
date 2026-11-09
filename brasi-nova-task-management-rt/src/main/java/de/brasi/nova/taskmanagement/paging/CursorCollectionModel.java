package de.brasi.nova.taskmanagement.paging;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collection;
import org.springframework.hateoas.CollectionModel;

/**
 * HAL-Collection-Wrapper fuer Keyset-Cursor-paginierte Listen (PLAN #141 Slice 4, B).
 *
 * <p>Erbt von {@link CollectionModel} - Spring HATEOAS serialisiert den Inhalt als {@code
 * {"_embedded":{"<collectionRelation>":[...]},"_links":{...}}}; die Anreicherung (self-/
 * Affordance-Links) erfolgt ueber die KSF-{@code NovaMutationResponseAdvice}/Enrichment-Pipeline,
 * die OpenAPI-Dokumentation ueber {@code @ApiFindCollectionResponse}. Die Cursor-Felder ({@code
 * nextCursor}, {@code hasMore}, {@code asOf}) landen TOP-LEVEL neben {@code _embedded},
 * deckungsgleich mit dem P5-Mechanismus aus der Slice-0-Probe ({@code KeysetCollectionModel}).
 *
 * <p>{@code asOf} ist {@code @JsonInclude(NON_NULL)}: Notifications tragen kein asOf, Tasks schon.
 * Beide Endpunkte nutzen denselben Typ ohne toten {@code asOf:null}-Payload bei Notifications.
 *
 * <p>Factory: {@link #ofTasks} (mit asOf) und {@link #ofNotifications} (ohne asOf).
 *
 * @param <T> Item-Typ der Collection (muss {@code BaseModel<T>} sein fuer Enrichment)
 */
public class CursorCollectionModel<T> extends CollectionModel<T> {

  private final String nextCursor;
  private final boolean hasMore;
  private final Instant asOf;

  /**
   * Vollstaendiger Konstruktor (Jackson/Unterklassen).
   *
   * @param content Items der Collection
   * @param nextCursor opaker Cursor oder {@code null} wenn {@code hasMore=false}
   * @param hasMore ob weitere Seiten vorhanden sind
   * @param asOf Snapshot-Wasserzeichen (UTC); {@code null} fuer Notifications
   */
  public CursorCollectionModel(
      final Collection<T> content,
      final String nextCursor,
      final boolean hasMore,
      final Instant asOf) {
    super(content);
    this.nextCursor = nextCursor;
    this.hasMore = hasMore;
    this.asOf = asOf;
  }

  /**
   * Erstellt ein Task-{@code CursorCollectionModel} mit asOf-Wasserzeichen.
   *
   * @param <T> Item-Typ
   * @param items Items der Collection
   * @param nextCursor opaker Cursor oder {@code null}
   * @param hasMore ob weitere Seiten vorhanden sind
   * @param asOf Snapshot-Wasserzeichen (Pflicht fuer Tasks)
   * @return neue CursorCollectionModel-Instanz
   */
  public static <T> CursorCollectionModel<T> ofTasks(
      final Collection<T> items,
      final String nextCursor,
      final boolean hasMore,
      final Instant asOf) {
    return new CursorCollectionModel<>(items, nextCursor, hasMore, asOf);
  }

  /**
   * Erstellt ein Notification-{@code CursorCollectionModel} ohne asOf.
   *
   * @param <T> Item-Typ
   * @param items Items der Collection
   * @param nextCursor opaker Cursor oder {@code null}
   * @param hasMore ob weitere Seiten vorhanden sind
   * @return neue CursorCollectionModel-Instanz ohne asOf
   */
  public static <T> CursorCollectionModel<T> ofNotifications(
      final Collection<T> items, final String nextCursor, final boolean hasMore) {
    return new CursorCollectionModel<>(items, nextCursor, hasMore, null);
  }

  /**
   * Opaker Keyset-Cursor fuer die naechste Seite ({@code null} wenn {@code hasMore=false}).
   *
   * @return Cursor-String oder {@code null}
   */
  public String getNextCursor() {
    return nextCursor;
  }

  /**
   * Ob weitere Seiten vorhanden sind.
   *
   * @return {@code true} wenn weitere Seiten existieren
   */
  public boolean isHasMore() {
    return hasMore;
  }

  /**
   * Snapshot-Wasserzeichen (UTC); nur fuer Task-Listen gesetzt.
   *
   * @return Instant oder {@code null} fuer Notifications
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public Instant getAsOf() {
    return asOf;
  }
}
