package de.brasi.nova.taskmanagement.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Format-Sentinel fuer {@link TaskManagementPermissions}: beweist, dass jede Enforcement-Konstante
 * das Format {@code task-management:<ACTION>} hat und der Zwei-Ebenen-Vertrag eingehalten wird.
 * Driftet eine Konstante weg (z.B. fehlender Praefix), faellt dieser Test.
 */
class TaskManagementPermissionsTest {

  private static final String MODULE_NAME = "task-management";
  private static final String EXPECTED_PREFIX = MODULE_NAME + ":";

  @Test
  @DisplayName("Alle Enforcement-Konstanten haben das Format 'task-management:<ACTION>'")
  void allAuthoritiesHaveCorrectPrefix() {
    for (final String authority : TaskManagementPermissions.ALL_AUTHORITIES) {
      assertThat(authority)
          .as("Authority '%s' muss mit '%s' beginnen", authority, EXPECTED_PREFIX)
          .startsWith(EXPECTED_PREFIX);

      final String action = authority.substring(EXPECTED_PREFIX.length());
      assertThat(action).as("Action-Teil von '%s' darf nicht leer sein", authority).isNotEmpty();
    }
  }

  @Test
  @DisplayName("Enforcement-Konstanten = MODULE_NAME + ':' + bare Action-Key")
  void enforcementConstantEqualsModuleNamePlusBareKey() {
    assertThat(TaskManagementPermissions.TASK_READ)
        .isEqualTo(MODULE_NAME + ":" + TaskManagementPermissions.TASK_READ_ACTION);
    assertThat(TaskManagementPermissions.TASK_WRITE)
        .isEqualTo(MODULE_NAME + ":" + TaskManagementPermissions.TASK_WRITE_ACTION);
    assertThat(TaskManagementPermissions.TASK_CREATE)
        .isEqualTo(MODULE_NAME + ":" + TaskManagementPermissions.TASK_CREATE_ACTION);
    assertThat(TaskManagementPermissions.NOTIFICATION_READ)
        .isEqualTo(MODULE_NAME + ":" + TaskManagementPermissions.NOTIFICATION_READ_ACTION);
    assertThat(TaskManagementPermissions.NOTIFICATION_WRITE)
        .isEqualTo(MODULE_NAME + ":" + TaskManagementPermissions.NOTIFICATION_WRITE_ACTION);
    assertThat(TaskManagementPermissions.TAG_ADMIN)
        .isEqualTo(MODULE_NAME + ":" + TaskManagementPermissions.TAG_ADMIN_ACTION);
  }

  @Test
  @DisplayName("ALL_AUTHORITIES und ALL_ACTION_KEYS haben dieselbe Laenge und Reihenfolge")
  void allAuthoritiesAndActionKeysHaveSameSizeAndOrder() {
    final List<String> authorities = TaskManagementPermissions.ALL_AUTHORITIES;
    final List<String> actionKeys = TaskManagementPermissions.ALL_ACTION_KEYS;

    assertThat(authorities).hasSameSizeAs(actionKeys);

    for (int i = 0; i < authorities.size(); i++) {
      assertThat(authorities.get(i))
          .as("ALL_AUTHORITIES[%d] muss aus MODULE_NAME + ':' + ALL_ACTION_KEYS[%d] bestehen", i, i)
          .isEqualTo(MODULE_NAME + ":" + actionKeys.get(i));
    }
  }

  @Test
  @DisplayName("MODULE_NAME ist 'task-management'")
  void moduleNameIsTaskManagement() {
    assertThat(TaskManagementPermissions.MODULE_NAME).isEqualTo("task-management");
  }

  @Test
  @DisplayName("Alle sechs erwarteten Authorities sind vorhanden")
  void allSixExpectedAuthoritiesPresent() {
    assertThat(TaskManagementPermissions.ALL_AUTHORITIES)
        .containsExactly(
            "task-management:TASK_READ",
            "task-management:TASK_WRITE",
            "task-management:TASK_CREATE",
            "task-management:NOTIFICATION_READ",
            "task-management:NOTIFICATION_WRITE",
            "task-management:TAG_ADMIN");
  }
}
