package com.shipflow.carrier.resilience;

import com.shipflow.carrier.domain.CarrierInfo;
import com.shipflow.carrier.port.BookingRequest;
import com.shipflow.carrier.port.BookingResult;
import com.shipflow.carrier.port.CarrierPortRegistry;
import com.shipflow.carrier.port.CarrierRejectedException;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.observability.ShipFlowMetrics;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resilient call wrapper around {@link com.shipflow.carrier.port.CarrierPort}: circuit breaker + retry with
 * jittered exponential backoff (Resilience4j) for technical failures + one hard timeout over all attempts, mapping
 * every result into an explicit {@link CallOutcome}. Retrying a booking is safe because ports must be idempotent on
 * {@code bookingRef}; business rejections are never retried. The breaker sees one result per logical call. Refuses to run inside a DB transaction (a slow
 * carrier must never hold connections or row locks).
 */
@Component
public class CarrierGateway {
    private static final Logger log = LoggerFactory.getLogger(CarrierGateway.class);

    /** Result of a booking call. UNCERTAIN = we do not know whether the carrier created the consignment. */
    public sealed interface CallOutcome {
        record Booked(String awb) implements CallOutcome { }
        record Failed(String reason, boolean businessRejection) implements CallOutcome { }
        record CircuitOpen() implements CallOutcome { }
        record Uncertain(String reason) implements CallOutcome { }
    }

    private final CarrierPortRegistry ports;
    private final CircuitBreakerRegistry breakers;
    private final Duration timeout;
    private final RetryConfig retryConfig;
    private final ShipFlowMetrics metrics;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CarrierGateway(CarrierPortRegistry ports, CircuitBreakerRegistry breakers, ShipFlowProperties props,
                          ShipFlowMetrics metrics) {
        this.ports = ports;
        this.breakers = breakers;
        this.metrics = metrics;
        ShipFlowProperties.Booking b = props.booking();
        this.timeout = b.carrierCallTimeout();
        this.retryConfig = RetryConfig.custom()
                .maxAttempts(Math.max(1, b.retryMaxAttempts()))
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(b.retryBackoff(), 2.0, 0.5))
                .retryOnException(e -> !(e instanceof CarrierRejectedException) && !(e instanceof InterruptedException))
                .build();
    }

    public CallOutcome book(CarrierInfo carrier, BookingRequest request) {
        assertNoTransaction();
        CircuitBreaker cb = breakers.forCarrier(carrier.code());
        if (!cb.tryAcquirePermission()) {
            return new CallOutcome.CircuitOpen();
        }
        Retry retry = Retry.of("carrier-" + carrier.code(), retryConfig);
        retry.getEventPublisher().onRetry(e -> {
            metrics.carrierRetry(carrier.code());
            log.info("retrying carrier booking carrier={} ref={} attempt={} wait={}ms error={}", carrier.code(),
                    request.bookingRef(), e.getNumberOfRetryAttempts(), e.getWaitInterval().toMillis(),
                    String.valueOf(e.getLastThrowable()));
        });
        Future<BookingResult> f = submit(() -> retry.executeCallable(
                () -> ports.forIntegration(carrier.integration()).book(request)));
        try {
            BookingResult r = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            cb.onSuccess();
            return new CallOutcome.Booked(r.awb());
        } catch (TimeoutException e) {
            f.cancel(true);
            cb.onFailure();
            return new CallOutcome.Uncertain("timeout after " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CarrierRejectedException) {
                cb.onSuccess(); // the carrier is healthy; it said no
                return new CallOutcome.Failed(cause.getMessage(), true);
            }
            cb.onFailure();
            return new CallOutcome.Failed(String.valueOf(cause.getMessage()), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            return new CallOutcome.Uncertain("interrupted");
        }
    }

    /** Lookup for uncertain outcomes; empty Optional = carrier confirms it has no such booking. */
    public Optional<Optional<String>> lookup(CarrierInfo carrier, String bookingRef) {
        assertNoTransaction();
        Future<Optional<BookingResult>> f = submit(() ->
                ports.forIntegration(carrier.integration()).lookup(carrier.code(), bookingRef));
        try {
            return Optional.of(f.get(timeout.toMillis(), TimeUnit.MILLISECONDS).map(BookingResult::awb));
        } catch (Exception e) {
            f.cancel(true);
            log.warn("carrier lookup failed carrier={} ref={} error={}", carrier.code(), bookingRef, e.toString());
            return Optional.empty(); // still unknown
        }
    }

    public void cancelQuietly(CarrierInfo carrier, String bookingRef) {
        assertNoTransaction();
        Future<?> f = submit(() -> {
            ports.forIntegration(carrier.integration()).cancel(carrier.code(), bookingRef);
            return null;
        });
        try {
            f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            f.cancel(true);
            log.warn("carrier cancel failed carrier={} ref={} error={}", carrier.code(), bookingRef, e.toString());
        }
    }

    /** Carrier calls run on their own thread; carry the caller's MDC (correlation id) along so the logs line up. */
    private <T> Future<T> submit(Callable<T> task) {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        return executor.submit(() -> {
            if (ctx != null) {
                MDC.setContextMap(ctx);
            }
            try {
                return task.call();
            } finally {
                MDC.clear();
            }
        });
    }

    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("carrier calls must not run inside a database transaction");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
