package com.shipflow.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.domain.CarrierKind;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.security.Actor;
import com.shipflow.security.HmacSigner;
import com.shipflow.security.Role;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/api/v1")
public class ScanController {
    public static final String CARRIER_SIGNATURE_HEADER = "X-Carrier-Signature";
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300;

    private final ScanIngestionService ingestion;
    private final CarrierDirectory carriers;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Clock clock;

    public ScanController(ScanIngestionService ingestion, CarrierDirectory carriers, ObjectMapper mapper,
                          Validator validator, Clock clock) {
        this.ingestion = ingestion;
        this.carriers = carriers;
        this.mapper = mapper;
        this.validator = validator;
        this.clock = clock;
    }

    /** Rider app / ops console. Riders may only report for own-fleet carriers. */
    @PostMapping("/scans")
    @PreAuthorize("hasAnyRole('RIDER','OPS','ADMIN')")
    public ScanResult scan(@Valid @RequestBody ScanCommand cmd) {
        Actor actor = Actor.current();
        String source = actor.has(Role.RIDER) && !actor.isInternal() ? "RIDER_APP" : "OPS";
        if (source.equals("RIDER_APP") && carriers.requireCode(cmd.carrierCode()).kind() != CarrierKind.OWN_FLEET) {
            throw ShipFlowException.forbidden("riders can only report scans for own-fleet carriers");
        }
        return ingestion.ingest(cmd, source, actor.subject());
    }

    /** 3PL push webhook, authenticated by HMAC over the raw body with the carrier's inbound secret. */
    @PostMapping("/carrier-webhooks/{carrierCode}/scans")
    public ScanResult carrierWebhook(@PathVariable String carrierCode,
                                     @RequestHeader(value = CARRIER_SIGNATURE_HEADER, required = false) String signature,
                                     @RequestBody String rawBody) throws Exception {
        var carrier = carriers.byCode(carrierCode).orElse(null);
        if (carrier == null || !HmacSigner.verify(carrier.inboundWebhookSecret(), signature, rawBody,
                clock.instant().getEpochSecond(), SIGNATURE_TOLERANCE_SECONDS)) {
            throw new ShipFlowException(ErrorCode.INVALID_SIGNATURE, "invalid or missing carrier signature");
        }
        ScanCommand cmd = mapper.readValue(rawBody, ScanCommand.class);
        var violations = validator.validate(cmd);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        if (!carrierCode.equals(cmd.carrierCode())) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "carrierCode in body does not match the path");
        }
        return ingestion.ingest(cmd, "CARRIER_WEBHOOK", "carrier:" + carrierCode);
    }
}
