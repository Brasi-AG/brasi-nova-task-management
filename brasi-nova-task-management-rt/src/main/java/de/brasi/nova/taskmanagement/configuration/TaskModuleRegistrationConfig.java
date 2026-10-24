package de.brasi.nova.taskmanagement.configuration;

import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.security.registration.ModuleRegistrationClient;
import io.keel.framework.security.registration.ModuleRegistrationProperties;
import io.keel.framework.security.registration.ModuleRegistrationProperties.ModuleAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publishes the complete task permission catalog when a runtime profile enables registration. */
@Configuration
public class TaskModuleRegistrationConfig {

  @Bean
  @ConditionalOnProperty(name = "ksf.module-registration.enabled", havingValue = "true")
  ModuleRegistrationClient moduleRegistrationClient(final ModuleRegistrationProperties properties) {
    properties.setModuleName(TaskManagementPermissions.MODULE_NAME);
    properties.setModuleVersion("1.0.0");
    properties.setDisplayName("Task Management");
    properties.setActions(
        TaskManagementPermissions.ALL_ACTION_KEYS.stream()
            .map(TaskModuleRegistrationConfig::action)
            .toList());
    return new ModuleRegistrationClient(properties);
  }

  private static ModuleAction action(final String key) {
    final String displayName =
        switch (key) {
          case TaskManagementPermissions.TASK_READ_ACTION -> "Aufgaben lesen";
          case TaskManagementPermissions.TASK_WRITE_ACTION -> "Aufgaben schreiben";
          case TaskManagementPermissions.TASK_CREATE_ACTION -> "Aufgaben erstellen";
          case TaskManagementPermissions.NOTIFICATION_READ_ACTION -> "Benachrichtigungen lesen";
          case TaskManagementPermissions.NOTIFICATION_WRITE_ACTION ->
              "Benachrichtigungen schreiben";
          case TaskManagementPermissions.TAG_ADMIN_ACTION -> "Tags verwalten";
          default -> throw new IllegalArgumentException("Unbekannte Task-Aktion: " + key);
        };
    final ModuleAction action = new ModuleAction();
    action.setActionKey(key);
    action.setDisplayName(displayName);
    return action;
  }
}
