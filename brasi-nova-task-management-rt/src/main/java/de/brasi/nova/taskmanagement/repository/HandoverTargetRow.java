package de.brasi.nova.taskmanagement.repository;

import java.util.UUID;

/**
 * Projektionszeile der Batch-Abfrage {@code findVisibleHandoverTargets}: je Aufgabe das juengste
 * sichtbare Handover-Ereignis ({@code SHOULD_STAY=false}) des Anfragers und seine Ziel-Refs.
 *
 * <p>Eine Zeile je {@code (taskId, toRef)}-Paar; {@code toRef} ist {@code null}, wenn das juengste
 * sichtbare Ereignis KEINE Ziele hat (Selbst-Handover ohne Ziele -&gt; leere Ziel-Liste). Kein
 * {@code @NovaTable}-Entity und kein Skalar -&gt; KSF mappt die SQL-Aliase {@code taskId}/{@code toRef}
 * per Record-Konstruktor ({@code -parameters} im Build gesetzt). Die Paarung ist je Ergebniszeile
 * gesichert (beide Werte aus DERSELBEN Zeile), nie ueber ungesicherte Listenreihenfolge.
 *
 * @param taskId interne Aufgaben-ID des Ereignisses ({@code TASK_HANDOVER.TASK_ID})
 * @param toRef  externe UUID eines Uebergabeziels ({@code TASK_HANDOVER_TARGET.TO_REF}) oder
 *     {@code null}, wenn das Ereignis keine Ziele hat
 */
public record HandoverTargetRow(Long taskId, UUID toRef) {}
