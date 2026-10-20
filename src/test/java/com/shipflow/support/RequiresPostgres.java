package com.shipflow.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Disables (reports as skipped) integration test classes when no PostgreSQL is available. */
public class RequiresPostgres implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return TestDatabase.get() != null
                ? ConditionEvaluationResult.enabled("PostgreSQL available: " + TestDatabase.get().source())
                : ConditionEvaluationResult.disabled("SKIPPED: no PostgreSQL available (Docker/Testcontainers, "
                + "TEST_DB_URL, or localhost:5432/shipflow_test)");
    }
}
