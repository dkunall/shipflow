package com.shipflow.observability;

import org.slf4j.MDC;

public final class Correlation {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private Correlation() {
    }

    public static String currentId() {
        return MDC.get(MDC_KEY);
    }
}
