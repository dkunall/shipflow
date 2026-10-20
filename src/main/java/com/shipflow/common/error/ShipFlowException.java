package com.shipflow.common.error;

import java.util.Map;

/** Base business exception: a stable {@link ErrorCode}, a human message and optional structured details. */
public class ShipFlowException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;

    public ShipFlowException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ShipFlowException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ShipFlowException notFound(String what, Object id) {
        return new ShipFlowException(ErrorCode.NOT_FOUND, what + " not found: " + id);
    }

    public static ShipFlowException forbidden(String message) {
        return new ShipFlowException(ErrorCode.FORBIDDEN, message);
    }

    public static ShipFlowException unprocessable(String message) {
        return new ShipFlowException(ErrorCode.UNPROCESSABLE, message);
    }
}
