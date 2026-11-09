package de.brasi.nova.taskmanagement.affordanceprobe;

import java.time.Instant;
import java.util.Collection;
import org.springframework.hateoas.CollectionModel;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0, P5-mech). KEIN Produktionscode.
 *
 * <p>{@link CollectionModel}-Subklasse, die Keyset-Cursor-Eigenschaften ({@code nextCursor}, {@code
 * hasMore}, {@code asOf}) traegt. P5 beobachtet, wo diese Properties im Draht landen und wo die
 * Items ({@code _embedded.<rel>} vs. flaches {@code items}) und {@code _links} erscheinen.
 */
public class KeysetCollectionModel extends CollectionModel<ProbeModel> {

  private final String nextCursor;
  private final boolean hasMore;
  private final Instant asOf;

  public KeysetCollectionModel(
      final Collection<ProbeModel> content,
      final String nextCursor,
      final boolean hasMore,
      final Instant asOf) {
    super(content);
    this.nextCursor = nextCursor;
    this.hasMore = hasMore;
    this.asOf = asOf;
  }

  public String getNextCursor() {
    return nextCursor;
  }

  public boolean isHasMore() {
    return hasMore;
  }

  public Instant getAsOf() {
    return asOf;
  }
}
