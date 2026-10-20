package com.shipflow.common.error;

/**
 * Stable machine-readable error codes. Each code knows its HTTP status so the single exception handler
 * needs no per-exception switch; domain code never touches HTTP types.
 */
public enum ErrorCode {
    NOT_FOUND(404),
    FORBIDDEN(403),
    VALIDATION_FAILED(400),
    INVALID_IDEMPOTENCY_KEY(400),
    IDEMPOTENCY_KEY_REUSED(422),
    IDEMPOTENCY_IN_PROGRESS(409),
    DUPLICATE_ORDER(409),
    DUPLICATE_REMITTANCE(409),
    INVALID_STATE_TRANSITION(409),
    CONCURRENT_MODIFICATION(409),
    UNSERVICEABLE(422),
    UNPROCESSABLE(422),
    UNSUPPORTED_SOURCE(404),
    INVALID_SIGNATURE(401);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
