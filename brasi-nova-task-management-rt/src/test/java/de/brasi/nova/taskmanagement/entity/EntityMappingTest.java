package de.brasi.nova.taskmanagement.entity;

import static org.assertj.core.api.Assertions.assertThat;

import de.brasi.nova.taskmanagement.entity.dictionary.NotificationSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.NotificationType;
import de.brasi.nova.taskmanagement.entity.dictionary.TagSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskDirection;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskPriority;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskStatus;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TagSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskDirectionCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskPriorityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaFkScope;
import io.keel.framework.api.annotation.db.NovaForeignKey;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaDictionaryEntry;
import io.keel.framework.api.model.NovaEntity;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Reflektiver Mapping-Test fuer Task-3- und Task-4-REWORK: Dictionary-Entities, FK-Felder (global)
 * auf Task/TaskNotification/TaskTag/TaskTagAssignment, Rest-Enums, Code-Konstanten sowie die
 * uebrigen Fach-Entities (Comment/History/Notification/Tag/TagAssignment/ReminderReceipt). Kein
 * Spring-Kontext noetig.
 */
class EntityMappingTest {

  // ---------------------------------------------------------------------------
  // (a) Task: FK-Long-Felder + @NovaForeignKey + @NovaFkScope(GLOBAL), kein Enum
  // ---------------------------------------------------------------------------

  @Test
  void task_taskStatusId_isLongFkGlobal() throws Exception {
    Field f = Task.class.getDeclaredField("taskStatusId");
    assertThat(f.getType()).isEqualTo(Long.class);

    NovaColumn col = f.getAnnotation(NovaColumn.class);
    assertThat(col).isNotNull();
    assertThat(col.name()).isEqualTo("TASK_STATUS_ID");
    assertThat(col.nullable()).isFalse();

    NovaForeignKey fk = col.foreignKey();
    assertThat(fk).isNotNull();
    assertThat(fk.referencedEntity()).isEqualTo(TaskStatus.class);

    NovaFkScope scope = f.getAnnotation(NovaFkScope.class);
    assertThat(scope).isNotNull();
    assertThat(scope.mode()).isEqualTo(NovaFkScope.Mode.GLOBAL);
  }

  @Test
  void task_priorityId_isLongFkGlobal() throws Exception {
    Field f = Task.class.getDeclaredField("priorityId");
    assertThat(f.getType()).isEqualTo(Long.class);

    NovaColumn col = f.getAnnotation(NovaColumn.class);
    assertThat(col).isNotNull();
    assertThat(col.name()).isEqualTo("PRIORITY_ID");
    assertThat(col.nullable()).isFalse();

    NovaForeignKey fk = col.foreignKey();
    assertThat(fk).isNotNull();
    assertThat(fk.referencedEntity()).isEqualTo(TaskPriority.class);

    NovaFkScope scope = f.getAnnotation(NovaFkScope.class);
    assertThat(scope).isNotNull();
    assertThat(scope.mode()).isEqualTo(NovaFkScope.Mode.GLOBAL);
  }

  @Test
  void task_directionId_isLongFkGlobal() throws Exception {
    Field f = Task.class.getDeclaredField("directionId");
    assertThat(f.getType()).isEqualTo(Long.class);

    NovaColumn col = f.getAnnotation(NovaColumn.class);
    assertThat(col).isNotNull();
    assertThat(col.name()).isEqualTo("DIRECTION_ID");
    assertThat(col.nullable()).isFalse();

    NovaForeignKey fk = col.foreignKey();
    assertThat(fk).isNotNull();
    assertThat(fk.referencedEntity()).isEqualTo(TaskDirection.class);

    NovaFkScope scope = f.getAnnotation(NovaFkScope.class);
    assertThat(scope).isNotNull();
    assertThat(scope.mode()).isEqualTo(NovaFkScope.Mode.GLOBAL);
  }

  @Test
  void task_hasNoEnumFields_taskStatusPriorityDirection() {
    Set<String> fieldNames =
        Arrays.stream(Task.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());
    assertThat(fieldNames).doesNotContain("taskStatus", "priority", "direction");
  }

  @Test
  void task_extendsNovaEntity() {
    assertThat(NovaEntity.class.isAssignableFrom(Task.class)).isTrue();
  }

  @Test
  void task_novaTableName_isTask() {
    NovaTable table = Task.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("TASK");
  }

  // ---------------------------------------------------------------------------
  // (b) Dictionary-Entities: extends NovaDictionaryEntry, @NovaTable, code-Feld
  // ---------------------------------------------------------------------------

  @Test
  void taskStatus_extendsDictionaryEntry_andTableName() {
    assertThat(NovaDictionaryEntry.class.isAssignableFrom(TaskStatus.class)).isTrue();
    NovaTable table = TaskStatus.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("DIC_TASK_STATUS");
  }

  @Test
  void taskStatus_hasCodeField_withNovaColumn() throws Exception {
    Field f = TaskStatus.class.getDeclaredField("code");
    assertThat(f.getType()).isEqualTo(String.class);
    NovaColumn col = f.getAnnotation(NovaColumn.class);
    assertThat(col).isNotNull();
    assertThat(col.name()).isEqualTo("CODE");
    assertThat(col.nullable()).isFalse();
    assertThat(col.length()).isEqualTo(50);
  }

  @Test
  void taskPriority_extendsDictionaryEntry_andTableName() {
    assertThat(NovaDictionaryEntry.class.isAssignableFrom(TaskPriority.class)).isTrue();
    NovaTable table = TaskPriority.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("DIC_TASK_PRIORITY");
  }

  @Test
  void taskPriority_hasCodeAndSortOrderFields() throws Exception {
    Field code = TaskPriority.class.getDeclaredField("code");
    assertThat(code.getType()).isEqualTo(String.class);
    NovaColumn colCode = code.getAnnotation(NovaColumn.class);
    assertThat(colCode).isNotNull();
    assertThat(colCode.name()).isEqualTo("CODE");
    assertThat(colCode.nullable()).isFalse();
    assertThat(colCode.length()).isEqualTo(50);

    Field sortOrder = TaskPriority.class.getDeclaredField("sortOrder");
    NovaColumn colSort = sortOrder.getAnnotation(NovaColumn.class);
    assertThat(colSort).isNotNull();
    assertThat(colSort.name()).isEqualTo("SORT_ORDER");
  }

  @Test
  void taskDirection_extendsDictionaryEntry_andTableName() {
    assertThat(NovaDictionaryEntry.class.isAssignableFrom(TaskDirection.class)).isTrue();
    NovaTable table = TaskDirection.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("DIC_TASK_DIRECTION");
  }

  @Test
  void taskDirection_hasCodeField() throws Exception {
    Field f = TaskDirection.class.getDeclaredField("code");
    assertThat(f.getType()).isEqualTo(String.class);
    NovaColumn col = f.getAnnotation(NovaColumn.class);
    assertThat(col).isNotNull();
    assertThat(col.name()).isEqualTo("CODE");
    assertThat(col.nullable()).isFalse();
    assertThat(col.length()).isEqualTo(50);
  }

  // ---------------------------------------------------------------------------
  // (c) Rest-Enums, Datumtypen, weitere Spaltennamen, @NovaTable-Namen
  // ---------------------------------------------------------------------------

  @Test
  void participantKind_hasPersonAndTeam() {
    Set<String> constants =
        Arrays.stream(ParticipantKind.values())
            .map(Enum::name)
            .collect(Collectors.toSet());
    assertThat(constants).containsExactlyInAnyOrder("PERSON", "TEAM");
  }

  @Test
  void exitKind_hasHandoverAndUnsubscribed() {
    Set<String> constants =
        Arrays.stream(ExitKind.values())
            .map(Enum::name)
            .collect(Collectors.toSet());
    assertThat(constants).containsExactlyInAnyOrder("HANDOVER", "UNSUBSCRIBED");
  }

  @Test
  void task_dueDateIsLocalDate() throws Exception {
    Field f = Task.class.getDeclaredField("dueDate");
    assertThat(f.getType()).isEqualTo(LocalDate.class);
  }

  @Test
  void task_startDateIsLocalDate() throws Exception {
    Field f = Task.class.getDeclaredField("startDate");
    assertThat(f.getType()).isEqualTo(LocalDate.class);
  }

  @Test
  void task_lastActivityAtIsLocalDateTime() throws Exception {
    Field f = Task.class.getDeclaredField("lastActivityAt");
    assertThat(f.getType()).isEqualTo(LocalDateTime.class);
  }

  @Test
  void task_sourceEntityRefIsUUID() throws Exception {
    Field f = Task.class.getDeclaredField("sourceEntityRef");
    assertThat(f.getType()).isEqualTo(UUID.class);
  }

  @Test
  void task_selectedColumnAnnotations() throws Exception {
    assertColumnName(Task.class, "title", "TITLE");
    assertColumnName(Task.class, "description", "DESCRIPTION");
    assertColumnName(Task.class, "originTenantId", "ORIGIN_TENANT_ID");
    assertColumnName(Task.class, "commentCount", "COMMENT_COUNT");
    assertColumnName(Task.class, "sourceModule", "SOURCE_MODULE");
    assertColumnName(Task.class, "sourceEntityType", "SOURCE_ENTITY_TYPE");
    assertColumnName(Task.class, "sourceEntityRef", "SOURCE_ENTITY_REF");
    assertColumnName(Task.class, "dueDate", "DUE_DATE");
    assertColumnName(Task.class, "startDate", "START_DATE");
    assertColumnName(Task.class, "lastActivityAt", "LAST_ACTIVITY_AT");
  }

  @Test
  void taskParticipant_novaTableAndSelectedColumns() throws Exception {
    NovaTable table = TaskParticipant.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("TASK_PARTICIPANT");

    assertColumnName(TaskParticipant.class, "taskId", "TASK_ID");
    assertColumnName(TaskParticipant.class, "participantRef", "PARTICIPANT_REF");
    assertColumnName(TaskParticipant.class, "participantKind", "PARTICIPANT_KIND");
    assertColumnName(TaskParticipant.class, "participantTenantId", "PARTICIPANT_TENANT_ID");
    assertColumnName(TaskParticipant.class, "creatorFlag", "CREATOR_FLAG");
    assertColumnName(TaskParticipant.class, "assigneeFlag", "ASSIGNEE_FLAG");
    assertColumnName(TaskParticipant.class, "participantFlag", "PARTICIPANT_FLAG");
    assertColumnName(TaskParticipant.class, "exitKind", "EXIT_KIND");
    assertColumnName(TaskParticipant.class, "exitAt", "EXIT_AT");

    Field participantKindField = TaskParticipant.class.getDeclaredField("participantKind");
    assertThat(participantKindField.getType()).isEqualTo(ParticipantKind.class);

    Field exitKindField = TaskParticipant.class.getDeclaredField("exitKind");
    assertThat(exitKindField.getType()).isEqualTo(ExitKind.class);
  }

  @Test
  void taskUserState_novaTableAndSelectedColumns() throws Exception {
    NovaTable table = TaskUserState.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("TASK_USER_STATE");

    assertColumnName(TaskUserState.class, "taskId", "TASK_ID");
    assertColumnName(TaskUserState.class, "userRef", "USER_REF");
    assertColumnName(TaskUserState.class, "userTenantId", "USER_TENANT_ID");
    assertColumnName(TaskUserState.class, "isPinned", "IS_PINNED");
    assertColumnName(TaskUserState.class, "isFlagged", "IS_FLAGGED");
    assertColumnName(TaskUserState.class, "isMuted", "IS_MUTED");
    assertColumnName(TaskUserState.class, "readAt", "READ_AT");
    assertColumnName(TaskUserState.class, "unsubscribedAt", "UNSUBSCRIBED_AT");
  }

  // ---------------------------------------------------------------------------
  // (d) Code-Konstanten-Mengen
  // ---------------------------------------------------------------------------

  @Test
  void taskStatusCodes_exactSet() throws Exception {
    Set<String> values = getPublicStringConstants(TaskStatusCodes.class);
    assertThat(values).containsExactlyInAnyOrder("OPEN", "DONE", "CANCELLED");
    Set<String> fieldNames = getPublicStringConstantFieldNames(TaskStatusCodes.class);
    assertThat(fieldNames).containsExactlyInAnyOrder("OPEN", "DONE", "CANCELLED");
  }

  @Test
  void taskPriorityCodes_exactSet() throws Exception {
    Set<String> values = getPublicStringConstants(TaskPriorityCodes.class);
    assertThat(values).containsExactlyInAnyOrder("LOW", "NORMAL", "HIGH", "URGENT");
    Set<String> fieldNames = getPublicStringConstantFieldNames(TaskPriorityCodes.class);
    assertThat(fieldNames).containsExactlyInAnyOrder("LOW", "NORMAL", "HIGH", "URGENT");
  }

  @Test
  void taskDirectionCodes_exactSet() throws Exception {
    Set<String> values = getPublicStringConstants(TaskDirectionCodes.class);
    assertThat(values)
        .containsExactlyInAnyOrder("BRASI_CUSTOMER", "CUSTOMER_INTERNAL", "INTER_COMPANY");
    Set<String> fieldNames = getPublicStringConstantFieldNames(TaskDirectionCodes.class);
    assertThat(fieldNames)
        .containsExactlyInAnyOrder("BRASI_CUSTOMER", "CUSTOMER_INTERNAL", "INTER_COMPANY");
  }

  // ===========================================================================
  // Task 4 (REWORK): Notification/Tag-Severity-Dictionaries + restliche Entities
  // ===========================================================================

  // (b4) Neue Dictionary-Entities: extends NovaDictionaryEntry, @NovaTable DIC_*, CODE-Feld

  @Test
  void notificationType_dictionaryContract() throws Exception {
    assertDictionaryCodeContract(NotificationType.class, "DIC_NOTIFICATION_TYPE");
  }

  @Test
  void notificationSeverity_dictionaryContract() throws Exception {
    assertDictionaryCodeContract(NotificationSeverity.class, "DIC_NOTIFICATION_SEVERITY");
  }

  @Test
  void tagSeverity_dictionaryContract() throws Exception {
    assertDictionaryCodeContract(TagSeverity.class, "DIC_TAG_SEVERITY");
  }

  // (a4) FK-Long-Felder (GLOBAL) auf TaskNotification/TaskTag/TaskTagAssignment

  @Test
  void taskNotification_typeId_isLongFkGlobal() throws Exception {
    assertLongFkGlobal(TaskNotification.class, "typeId", "TYPE_ID", NotificationType.class, false);
  }

  @Test
  void taskNotification_severityId_isLongFkGlobalNullable() throws Exception {
    assertLongFkGlobal(
        TaskNotification.class, "severityId", "SEVERITY_ID", NotificationSeverity.class, true);
  }

  @Test
  void taskTag_severityId_isLongFkGlobal() throws Exception {
    assertLongFkGlobal(TaskTag.class, "severityId", "SEVERITY_ID", TagSeverity.class, false);
  }

  @Test
  void taskTagAssignment_tagId_isLongFkGlobal() throws Exception {
    assertLongFkGlobal(TaskTagAssignment.class, "tagId", "TAG_ID", TaskTag.class, false);
  }

  @Test
  void taskNotification_hasNoEnumFields_typeSeverity() {
    Set<String> fieldNames =
        Arrays.stream(TaskNotification.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());
    assertThat(fieldNames).doesNotContain("type", "severity");
  }

  @Test
  void taskTag_hasNoSeverityEnumField() {
    Set<String> fieldNames =
        Arrays.stream(TaskTag.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());
    assertThat(fieldNames).doesNotContain("severity");
  }

  // (c4) TaskTag ist ein Dictionary (user-verwalteter Tag-Katalog, kein DIC_*-System-Dictionary)

  @Test
  void taskTag_extendsDictionaryEntry_andTableName() {
    assertThat(NovaDictionaryEntry.class.isAssignableFrom(TaskTag.class)).isTrue();
    NovaTable table = TaskTag.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("TASK_TAG");
  }

  // (c4) @NovaTable-Namen + Spalten + Typen der uebrigen Fach-Entities

  @Test
  void taskComment_novaTableAndSelectedColumns() throws Exception {
    NovaTable table = TaskComment.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("TASK_COMMENT");
    assertThat(NovaEntity.class.isAssignableFrom(TaskComment.class)).isTrue();

    assertColumnName(TaskComment.class, "taskId", "TASK_ID");
    assertColumnName(TaskComment.class, "authorRef", "AUTHOR_REF");
    assertColumnName(TaskComment.class, "parentCommentId", "PARENT_COMMENT_ID");
    assertColumnName(TaskComment.class, "text", "TEXT");
    assertColumnName(TaskComment.class, "isEdited", "IS_EDITED");

    assertThat(TaskComment.class.getDeclaredField("authorRef").getType()).isEqualTo(UUID.class);
    assertThat(TaskComment.class.getDeclaredField("taskId").getType()).isEqualTo(Long.class);
    assertThat(TaskComment.class.getDeclaredField("parentCommentId").getType())
        .isEqualTo(Long.class);
  }

  @Test
  void taskHistoryEntry_novaTableAndSelectedColumns() throws Exception {
    NovaTable table = TaskHistoryEntry.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("TASK_HISTORY_ENTRY");

    assertColumnName(TaskHistoryEntry.class, "taskId", "TASK_ID");
    assertColumnName(TaskHistoryEntry.class, "actorRef", "ACTOR_REF");
    assertColumnName(TaskHistoryEntry.class, "text", "TEXT");
    assertColumnName(TaskHistoryEntry.class, "changeDetail", "CHANGE_DETAIL");

    assertThat(TaskHistoryEntry.class.getDeclaredField("actorRef").getType()).isEqualTo(UUID.class);
  }

  @Test
  void taskNotification_novaTableAndSelectedColumns() throws Exception {
    NovaTable table = TaskNotification.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("TASK_NOTIFICATION");

    assertColumnName(TaskNotification.class, "recipientRef", "RECIPIENT_REF");
    assertColumnName(TaskNotification.class, "taskId", "TASK_ID");
    assertColumnName(TaskNotification.class, "isRead", "IS_READ");
    assertColumnName(TaskNotification.class, "actorRef", "ACTOR_REF");
    assertColumnName(TaskNotification.class, "text", "TEXT");
    assertColumnName(TaskNotification.class, "preview", "PREVIEW");

    assertThat(TaskNotification.class.getDeclaredField("recipientRef").getType())
        .isEqualTo(UUID.class);
    assertThat(TaskNotification.class.getDeclaredField("actorRef").getType()).isEqualTo(UUID.class);
    assertThat(TaskNotification.class.getDeclaredField("taskId").getType()).isEqualTo(Long.class);
  }

  @Test
  void taskTagAssignment_novaTableAndSelectedColumns() throws Exception {
    NovaTable table = TaskTagAssignment.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("TASK_TAG_ASSIGNMENT");
    assertThat(NovaEntity.class.isAssignableFrom(TaskTagAssignment.class)).isTrue();

    assertColumnName(TaskTagAssignment.class, "taskId", "TASK_ID");
    assertThat(TaskTagAssignment.class.getDeclaredField("taskId").getType()).isEqualTo(Long.class);
  }

  @Test
  void taskReminderReceipt_novaTableAndSelectedColumns() throws Exception {
    NovaTable table = TaskReminderReceipt.class.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("TASK_REMINDER_RECEIPT");
    assertThat(NovaEntity.class.isAssignableFrom(TaskReminderReceipt.class)).isTrue();

    assertColumnName(TaskReminderReceipt.class, "taskId", "TASK_ID");
    assertColumnName(TaskReminderReceipt.class, "recipientRef", "RECIPIENT_REF");
    assertColumnName(TaskReminderReceipt.class, "occurrenceDate", "OCCURRENCE_DATE");

    assertThat(TaskReminderReceipt.class.getDeclaredField("recipientRef").getType())
        .isEqualTo(UUID.class);
    assertThat(TaskReminderReceipt.class.getDeclaredField("occurrenceDate").getType())
        .isEqualTo(LocalDate.class);
  }

  // (d4) Code-Konstanten-Mengen (Werte UND Feldnamen)

  @Test
  void notificationTypeCodes_exactSet() throws Exception {
    Set<String> values = getPublicStringConstants(NotificationTypeCodes.class);
    assertThat(values)
        .containsExactlyInAnyOrder(
            "ASSIGN", "REPLY", "DUE", "MENTION", "DONE", "CHANGE", "HANDOVER", "SYSTEM");
    Set<String> fieldNames = getPublicStringConstantFieldNames(NotificationTypeCodes.class);
    assertThat(fieldNames)
        .containsExactlyInAnyOrder(
            "ASSIGN", "REPLY", "DUE", "MENTION", "DONE", "CHANGE", "HANDOVER", "SYSTEM");
  }

  @Test
  void notificationSeverityCodes_exactSet() throws Exception {
    Set<String> values = getPublicStringConstants(NotificationSeverityCodes.class);
    assertThat(values).containsExactlyInAnyOrder("INFO", "SUCCESS", "WARN", "ERROR");
    Set<String> fieldNames = getPublicStringConstantFieldNames(NotificationSeverityCodes.class);
    assertThat(fieldNames).containsExactlyInAnyOrder("INFO", "SUCCESS", "WARN", "ERROR");
  }

  @Test
  void tagSeverityCodes_exactSet() throws Exception {
    Set<String> values = getPublicStringConstants(TagSeverityCodes.class);
    assertThat(values)
        .containsExactlyInAnyOrder("PRIMARY", "SUCCESS", "WARN", "DANGER", "SECONDARY");
    Set<String> fieldNames = getPublicStringConstantFieldNames(TagSeverityCodes.class);
    assertThat(fieldNames)
        .containsExactlyInAnyOrder("PRIMARY", "SUCCESS", "WARN", "DANGER", "SECONDARY");
  }

  // ---------------------------------------------------------------------------
  // Hilfsmethoden
  // ---------------------------------------------------------------------------

  /** Prueft ein Long-FK-Feld mit @NovaForeignKey + @NovaFkScope(GLOBAL). */
  private void assertLongFkGlobal(
      Class<?> clazz,
      String fieldName,
      String expectedColumn,
      Class<?> referencedEntity,
      boolean nullable)
      throws Exception {
    Field f = clazz.getDeclaredField(fieldName);
    assertThat(f.getType()).isEqualTo(Long.class);

    NovaColumn col = f.getAnnotation(NovaColumn.class);
    assertThat(col)
        .as("@NovaColumn fehlt an %s.%s", clazz.getSimpleName(), fieldName)
        .isNotNull();
    assertThat(col.name()).isEqualTo(expectedColumn);
    assertThat(col.nullable()).isEqualTo(nullable);

    NovaForeignKey fk = col.foreignKey();
    assertThat(fk).isNotNull();
    assertThat(fk.referencedEntity()).isEqualTo(referencedEntity);

    NovaFkScope scope = f.getAnnotation(NovaFkScope.class);
    assertThat(scope)
        .as("@NovaFkScope fehlt an %s.%s", clazz.getSimpleName(), fieldName)
        .isNotNull();
    assertThat(scope.mode()).isEqualTo(NovaFkScope.Mode.GLOBAL);
  }

  /** Prueft den Dictionary-Vertrag: extends NovaDictionaryEntry, @NovaTable, CODE-Spalte. */
  private void assertDictionaryCodeContract(Class<?> dicClass, String expectedTable)
      throws Exception {
    assertThat(NovaDictionaryEntry.class.isAssignableFrom(dicClass)).isTrue();
    NovaTable table = dicClass.getAnnotation(NovaTable.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo(expectedTable);

    Field code = dicClass.getDeclaredField("code");
    assertThat(code.getType()).isEqualTo(String.class);
    NovaColumn col = code.getAnnotation(NovaColumn.class);
    assertThat(col).isNotNull();
    assertThat(col.name()).isEqualTo("CODE");
    assertThat(col.nullable()).isFalse();
    assertThat(col.length()).isEqualTo(50);
  }

  private void assertColumnName(Class<?> clazz, String fieldName, String expectedColumnName)
      throws Exception {
    Field f = clazz.getDeclaredField(fieldName);
    NovaColumn col = f.getAnnotation(NovaColumn.class);
    assertThat(col)
        .as("@NovaColumn fehlt an Feld %s.%s", clazz.getSimpleName(), fieldName)
        .isNotNull();
    assertThat(col.name())
        .as("Spaltenname fuer %s.%s", clazz.getSimpleName(), fieldName)
        .isEqualTo(expectedColumnName);
  }

  private Set<String> getPublicStringConstants(Class<?> clazz) throws Exception {
    Set<String> values =
        Arrays.stream(clazz.getDeclaredFields())
            .filter(
                f ->
                    java.lang.reflect.Modifier.isPublic(f.getModifiers())
                        && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && java.lang.reflect.Modifier.isFinal(f.getModifiers())
                        && f.getType() == String.class)
            .map(
                f -> {
                  try {
                    return (String) f.get(null);
                  } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(Collectors.toSet());
    return values;
  }

  /** Liefert die Feldnamen (nicht Werte) aller public-static-final-String-Konstanten. */
  private Set<String> getPublicStringConstantFieldNames(Class<?> clazz) {
    return Arrays.stream(clazz.getDeclaredFields())
        .filter(
            f ->
                java.lang.reflect.Modifier.isPublic(f.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && java.lang.reflect.Modifier.isFinal(f.getModifiers())
                    && f.getType() == String.class)
        .map(Field::getName)
        .collect(Collectors.toSet());
  }
}
