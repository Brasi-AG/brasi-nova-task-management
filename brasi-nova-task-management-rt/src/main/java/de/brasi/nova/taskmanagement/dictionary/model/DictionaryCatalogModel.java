package de.brasi.nova.taskmanagement.dictionary.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Gesamtkatalog aller 6 System-Dictionaries (read-only in Phase 1a).
 *
 * @param taskStatus          Aufgaben-Status-Eintraege
 * @param taskPriority        Aufgaben-Prioritaets-Eintraege (mit sortOrder)
 * @param taskDirection       Aufgaben-Richtungs-Eintraege
 * @param notificationType    Benachrichtigungs-Typ-Eintraege
 * @param notificationSeverity Benachrichtigungs-Schweregrad-Eintraege
 * @param tagSeverity         Tag-Schweregrad-Eintraege
 */
public record DictionaryCatalogModel(
    @Schema(description = "Aufgaben-Status-Eintraege (z.B. OPEN, DONE, CANCELLED).")
    List<DictionaryEntryModel> taskStatus,
    @Schema(description = "Aufgaben-Prioritaets-Eintraege mit sortOrder (z.B. HIGH, NORMAL, LOW).")
    List<DictionaryEntryModel> taskPriority,
    @Schema(description = "Aufgaben-Richtungs-Eintraege (z.B. CUSTOMER_INTERNAL).")
    List<DictionaryEntryModel> taskDirection,
    @Schema(description = "Benachrichtigungs-Typ-Eintraege (z.B. ASSIGN, REPLY, MENTION, HANDOVER).")
    List<DictionaryEntryModel> notificationType,
    @Schema(description = "Benachrichtigungs-Schweregrad-Eintraege (z.B. INFO, WARNING).")
    List<DictionaryEntryModel> notificationSeverity,
    @Schema(description = "Tag-Schweregrad-Eintraege (z.B. PRIMARY, DANGER).")
    List<DictionaryEntryModel> tagSeverity) {}
