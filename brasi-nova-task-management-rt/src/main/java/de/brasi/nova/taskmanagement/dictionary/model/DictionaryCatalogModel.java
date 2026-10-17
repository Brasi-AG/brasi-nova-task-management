package de.brasi.nova.taskmanagement.dictionary.model;

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
    List<DictionaryEntryModel> taskStatus,
    List<DictionaryEntryModel> taskPriority,
    List<DictionaryEntryModel> taskDirection,
    List<DictionaryEntryModel> notificationType,
    List<DictionaryEntryModel> notificationSeverity,
    List<DictionaryEntryModel> tagSeverity) {}
