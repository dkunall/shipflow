package com.shipflow.booking.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.booking.adapter.OrderAdapter;
import com.shipflow.booking.adapter.OrderAdapterRegistry;
import com.shipflow.booking.application.BulkUploadService;
import com.shipflow.booking.application.ShipmentBookingFacade;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.security.Actor;
import com.shipflow.shipment.api.ShipmentView;
import com.shipflow.shipment.application.ShipmentViews;
import com.shipflow.shipment.persistence.ShipmentRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Thin HTTP adapter for booking: auth/role, merchant scoping, idempotency header, DTO mapping. */
@RestController
@RequestMapping("/api/v1")
public class ShipmentBookingController {
    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    private final ShipmentBookingFacade facade;
    private final BulkUploadService bulk;
    private final OrderAdapterRegistry adapters;
    private final ShipmentRepository shipments;
    private final ShipmentViews views;
    private final Validator validator;

    public ShipmentBookingController(ShipmentBookingFacade facade, BulkUploadService bulk,
                                     OrderAdapterRegistry adapters, ShipmentRepository shipments, ShipmentViews views,
                                     Validator validator) {
        this.facade = facade;
        this.bulk = bulk;
        this.adapters = adapters;
        this.shipments = shipments;
        this.views = views;
        this.validator = validator;
    }

    @PostMapping("/shipments")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ShipmentView> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody CreateShipmentRequest request) {
        Actor actor = Actor.current();
        if (key == null) {
            throw new ShipFlowException(ErrorCode.INVALID_IDEMPOTENCY_KEY, "Idempotency-Key header is required");
        }
        var result = facade.create(actor, actor.requireMerchant(), key, request, "API", null);
        return respond(result);
    }

    @PostMapping(value = "/shipments/bulk", consumes = "text/csv")
    @PreAuthorize("hasRole('MERCHANT')")
    public BulkUploadService.Report bulkCsv(@RequestBody String csv) {
        Actor actor = Actor.current();
        return bulk.upload(actor, actor.requireMerchant(), new StringReader(csv));
    }

    @PostMapping(value = "/shipments/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('MERCHANT')")
    public BulkUploadService.Report bulkMultipart(@RequestParam("file") MultipartFile file) throws IOException {
        Actor actor = Actor.current();
        return bulk.upload(actor, actor.requireMerchant(),
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
    }

    /** E-store webhook-style ingestion through the pluggable OrderAdapter registry. */
    @PostMapping("/integrations/{source}/orders")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ShipmentView> ingestOrder(@PathVariable String source,
                                                    @RequestParam("originHub") String originHub,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                    @RequestBody JsonNode payload) {
        Actor actor = Actor.current();
        OrderAdapter adapter = adapters.get(source);
        CreateShipmentRequest request = adapter.toShipmentRequest(payload, originHub);
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        String effectiveKey = key != null ? key : adapter.externalOrderId(payload);
        return respond(facade.create(actor, actor.requireMerchant(), effectiveKey, request, source.toUpperCase(),
                payload));
    }

    private ResponseEntity<ShipmentView> respond(ShipmentBookingFacade.Result result) {
        UUID id = result.shipmentId();
        ShipmentView view = views.of(shipments.findById(id).orElseThrow());
        var builder = ResponseEntity.created(URI.create("/api/v1/shipments/" + id));
        if (result.replayed()) {
            builder.header(REPLAY_HEADER, "true");
        }
        return builder.body(view);
    }
}
