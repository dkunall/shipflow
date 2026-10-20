package com.shipflow.ndr;

import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.security.Actor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ndr")
public class NdrController {
    private final NdrReportService reports;

    public NdrController(NdrReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('MERCHANT','OPS','ADMIN')")
    public NdrReportService.Report report(@RequestParam Instant from, @RequestParam Instant to,
                                          @RequestParam(required = false) UUID merchantId) {
        if (!to.isAfter(from) || Duration.between(from, to).toDays() > 92) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "'to' must be after 'from' and within 92 days");
        }
        Actor actor = Actor.current();
        UUID scope = actor.isInternal() ? merchantId : actor.requireMerchant();
        if (!actor.isInternal() && merchantId != null && !merchantId.equals(scope)) {
            throw ShipFlowException.forbidden("cannot report on another merchant");
        }
        return reports.report(from, to, scope);
    }
}
