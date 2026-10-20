package com.shipflow.scan;

public enum ScanOutcome {
    /** status advanced (possibly via inferred intermediate steps) */
    APPLIED,
    /** same carrier event id seen before: no effect at all */
    DUPLICATE,
    /** event happened before the event that set the current status: stored, status untouched */
    OUT_OF_ORDER,
    /** event would not move the status forward (already there / lower rank): stored, status untouched */
    STALE,
    /** not a legal move (or wrong carrier): stored for audit, status untouched */
    REJECTED
}
