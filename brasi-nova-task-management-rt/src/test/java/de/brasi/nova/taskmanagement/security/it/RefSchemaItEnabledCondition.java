package de.brasi.nova.taskmanagement.security.it;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Aktiviert einen IT nur, wenn die Zugangsdaten zur MSSQL-Wegwerf-DB konfiguriert sind.
 * Uebersprungen (kein Fail) wenn nicht konfiguriert — so bleibt die Unit-Suite ohne DB-Zugang
 * gruen und der IT ist CI-portabel.
 */
public class RefSchemaItEnabledCondition implements ExecutionCondition {

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {
    if (RefSchemaItEnv.isConfigured()) {
      return ConditionEvaluationResult.enabled("RefSchema-DB-IT konfiguriert");
    }
    return ConditionEvaluationResult.disabled(
        "RefSchema-DB-IT nicht konfiguriert (kein BRASI_INT_DB_USER/PASSWORD) - uebersprungen");
  }
}
