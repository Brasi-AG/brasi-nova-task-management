package de.brasi.nova.taskmanagement.paging;

import java.time.Instant;
import java.util.List;

/**
 * Generische Keyset-paginierte Seite (arch-v2 §2.1).
 *
 * <p>Im Gegensatz zur {@link de.brasi.nova.taskmanagement.notification.model.NotificationPage}
 * traegt diese Seite zusaetzlich das {@code asOf}-Wasserzeichen (arch-v2 §3): Bootstrap erzeugt EIN
 * {@code asOf} (UTC), das in den Cursor eingebettet und in jeder Folge-Query als
 * {@code LAST_ACTIVITY_AT <= :asOf} angewendet wird.
 *
 * @param <T>        Element-Typ dieser Seite
 * @param items      Elemente dieser Seite
 * @param nextCursor opaker Cursor fuer die naechste Seite ({@code null} wenn {@code hasMore=false})
 * @param hasMore    ob weitere Seiten vorhanden sind
 * @param asOf       Snapshot-Wasserzeichen (UTC); fuer alle Seiten desselben Scrolls identisch
 */
public record KeysetPage<T>(List<T> items, String nextCursor, boolean hasMore, Instant asOf) {}
