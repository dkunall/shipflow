package com.shipflow.common.error;

/**
 * A conflict that is expected to disappear if the whole unit of work is retried in a NEW transaction
 * (e.g. an optimistic capacity reservation lost a race at the very end of the transaction).
 */
public class TransientConflictException extends RuntimeException {
    public TransientConflictException(String message) {
        super(message);
    }
}
