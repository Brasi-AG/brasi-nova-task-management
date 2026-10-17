package de.brasi.nova.taskmanagement.entity;

import static org.assertj.core.api.Assertions.assertThat;

import de.brasi.nova.taskmanagement.entity.dictionary.TaskDirection;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskPriority;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskStatus;
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
 * Reflektiver Mapping-Test fuer Task-3-REWORK: Dictionary-Entities, FK-Felder auf Task, Rest-Enums,
 * Code-Konstanten. Kein Spring-Kontext noetig.
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

  // ---------------------------------------------------------------------------
  // Hilfsmethoden
  // ---------------------------------------------------------------------------

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
