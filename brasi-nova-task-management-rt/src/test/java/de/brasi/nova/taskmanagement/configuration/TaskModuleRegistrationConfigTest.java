package de.brasi.nova.taskmanagement.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.security.KsfSecurityAutoConfiguration;
import io.keel.framework.security.registration.ModuleRegistrationClient;
import io.keel.framework.security.registration.ModuleRegistrationProperties;
import io.keel.framework.security.registration.ModuleRegistrationProperties.ModuleAction;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TaskModuleRegistrationConfigTest {

  private static final String CONFIG_CLASS =
      "de.brasi.nova.taskmanagement.configuration.TaskModuleRegistrationConfig";

  @Test
  void registers_the_complete_bare_action_catalog_from_task_permissions() throws Exception {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KsfSecurityAutoConfiguration.class))
        .withPropertyValues("ksf.module-registration.enabled=true")
        .withUserConfiguration(Class.forName(CONFIG_CLASS))
        .run(
            context -> {
              assertThat(context.getBeanNamesForType(ModuleRegistrationClient.class)).hasSize(1);
              final ModuleRegistrationProperties properties =
                  context.getBean(ModuleRegistrationProperties.class);
              assertThat(properties.getModuleName())
                  .isEqualTo(TaskManagementPermissions.MODULE_NAME);
              assertThat(properties.getModuleVersion()).isNotBlank();
              assertThat(properties.getDisplayName()).isNotBlank();
              assertThat(properties.getActions())
                  .extracting(ModuleAction::getActionKey)
                  .containsExactlyElementsOf(TaskManagementPermissions.ALL_ACTION_KEYS);
              assertThat(properties.getActions())
                  .hasSize(6)
                  .allSatisfy(
                      action -> {
                        assertThat(action.getActionKey()).doesNotContain(":");
                        assertThat(action.getDisplayName()).isNotBlank();
                      });
              assertThat(properties.getActions())
                  .extracting(ModuleAction::getActionKey)
                  .contains(TaskManagementPermissions.TASK_CREATE_ACTION);
            });
  }

  @Test
  void runtime_defaults_to_registration_disabled() throws Exception {
    final Properties properties = new Properties();
    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream("application.properties")) {
      assertThat(input).isNotNull();
      properties.load(input);
    }
    assertThat(properties.getProperty("ksf.module-registration.enabled")).isEqualTo("false");

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KsfSecurityAutoConfiguration.class))
        .withPropertyValues(
            "ksf.module-registration.enabled="
                + properties.getProperty("ksf.module-registration.enabled"))
        .withUserConfiguration(Class.forName(CONFIG_CLASS))
        .run(
            context ->
                assertThat(context.getBeanNamesForType(ModuleRegistrationClient.class)).isEmpty());
  }

  @Test
  void enabled_registration_with_blank_external_key_skips_http() throws Exception {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KsfSecurityAutoConfiguration.class))
        .withPropertyValues(
            "ksf.module-registration.enabled=true",
            "ksf.module-registration.security-server-url=http://127.0.0.1:9",
            "ksf.module-registration.api-key= ")
        .withUserConfiguration(Class.forName(CONFIG_CLASS))
        .run(
            context -> {
              assertThat(context.getBean(ModuleRegistrationProperties.class).getApiKey()).isBlank();
              assertThat(context.getBean(ModuleRegistrationClient.class).register()).isFalse();
            });
  }
}
