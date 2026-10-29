package com.shipflow.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Skips integration test classes when no PostgreSQL is available, unless TEST_REQUIRE_DB=true (CI), which fails them. */
public class RequiresPostgres implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (TestDatabase.get() == null && "true".equalsIgnoreCase(System.getenv("TEST_REQUIRE_DB"))) {
            throw new IllegalStateException("TEST_REQUIRE_DB=true but no PostgreSQL is reachable");
        }
        return TestDatabase.get() != null
                ? ConditionEvaluationResult.enabled("PostgreSQL available: " + TestDatabase.get().source())
                : ConditionEvaluationResult.disabled("SKIPPED: no PostgreSQL available (Docker/Testcontainers, "
                + "TEST_DB_URL, or localhost:5432/shipflow_test)");
    }
}
