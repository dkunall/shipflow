package com.shipflow.scan;

import java.util.List;

public record ScanResult(String carrierEventId, ScanOutcome outcome, String shipmentStatus, List<String> appliedSteps,
                         String detail) {
}
