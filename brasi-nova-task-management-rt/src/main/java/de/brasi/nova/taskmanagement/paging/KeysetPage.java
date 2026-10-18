package de.brasi.nova.taskmanagement.paging;

import io.swagger.v3.oas.annotations.media.Schema;
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
public record KeysetPage<T>(
    @Schema(description = "Elemente dieser Seite.")
    List<T> items,
    @Schema(description = "Opaker Cursor fuer die naechste Seite (null wenn hasMore=false).",
        example = "eyJhc09mIjoiMjAyNi0xMC0xOFQwODowMDowMCJ9")
    String nextCursor,
    @Schema(description = "Gibt an, ob weitere Seiten vorhanden sind.", example = "false")
    boolean hasMore,
    @Schema(description = "Snapshot-Wasserzeichen (UTC); fuer alle Seiten desselben Scrolls identisch.",
        example = "2026-10-18T08:00:00Z")
    Instant asOf) {}
