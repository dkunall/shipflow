package com.shipflow.booking.application;

import com.shipflow.booking.carrier.BookingOrchestrator;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.security.Actor;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Bulk CSV booking. Each row is validated and booked in its own transaction (partial success, per-row result);
 * duplicates are detected by UNIQUE (merchant_id, merchant_order_ref), which makes re-uploading the same file
 * safe. Carrier bookings then run with bounded parallelism outside transactions.
 */
@Service
public class BulkUploadService {
    public static final int MAX_ROWS = 2000;
    private static final int CARRIER_PARALLELISM = 16;

    public record RowResult(int line, String merchantOrderRef, String status, UUID shipmentId, String error) {
    }

    public record Report(int total, int created, int duplicates, int invalid, List<RowResult> rows) {
    }

    private final ShipmentBookingService booking;
    private final BookingOrchestrator orchestrator;
    private final Validator validator;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public BulkUploadService(ShipmentBookingService booking, BookingOrchestrator orchestrator, Validator validator) {
        this.booking = booking;
        this.orchestrator = orchestrator;
        this.validator = validator;
    }

    public Report upload(Actor actor, UUID merchantId, Reader csv) {
        List<CsvShipmentParser.Row> rows;
        try {
            rows = CsvShipmentParser.parse(csv, MAX_ROWS);
        } catch (Exception e) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "invalid CSV: " + e.getMessage());
        }
        List<RowResult> results = new ArrayList<>();
        List<UUID> created = new ArrayList<>();
        int dup = 0, invalid = 0;
        for (CsvShipmentParser.Row row : rows) {
            if (row.error() != null) {
                results.add(new RowResult(row.line(), null, "INVALID", null, row.error()));
                invalid++;
                continue;
            }
            var violations = validator.validate(row.request());
            if (!violations.isEmpty()) {
                String msg = violations.stream().sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage()).reduce((a, b) -> a + "; " + b).orElse("");
                results.add(new RowResult(row.line(), row.request().merchantOrderRef(), "INVALID", null, msg));
                invalid++;
                continue;
            }
            try {
                var b = booking.createAndAllocate(merchantId, row.request(), "CSV", actor.subject());
                created.add(b.shipmentId());
                results.add(new RowResult(row.line(), row.request().merchantOrderRef(), "CREATED", b.shipmentId(), null));
            } catch (ShipFlowException e) {
                boolean isDup = e.code() == ErrorCode.DUPLICATE_ORDER;
                if (isDup) {
                    dup++;
                } else {
                    invalid++;
                }
                results.add(new RowResult(row.line(), row.request().merchantOrderRef(), isDup ? "DUPLICATE" : "REJECTED",
                        null, e.getMessage()));
            }
        }
        Semaphore permits = new Semaphore(CARRIER_PARALLELISM);
        List<Future<?>> futures = new ArrayList<>();
        for (UUID id : created) {
            futures.add(pool.submit(() -> {
                permits.acquireUninterruptibly();
                try {
                    orchestrator.book(id, actor.subject());
                } finally {
                    permits.release();
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
                // left ALLOCATED without booking -> BookingReconciler re-drives it
            }
        }
        return new Report(rows.size(), created.size(), dup, invalid, results);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    static String describe(ConstraintViolation<?> v) {
        return v.getPropertyPath() + ": " + v.getMessage();
    }
}
