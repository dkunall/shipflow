package com.shipflow.idempotency;

import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.observability.ShipFlowMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Idempotency-Key handling for resource-creating commands (scope = merchant/subject + key).
 * <ol>
 *   <li>Claim with INSERT .. ON CONFLICT DO NOTHING in its own committed transaction: exactly one concurrent
 *       request wins.</li>
 *   <li>Losers: different payload hash -> 422; completed -> replay (same resource id + status); in progress ->
 *       wait briefly for the winner, then replay or 409.</li>
 *   <li>The winner's business effect and the COMPLETED marker commit in the SAME transaction, so there is no
 *       window in which the effect exists but the key is not completed.</li>
 *   <li>Failure deletes the claim so a client retry runs again (the failed attempt had no effect).</li>
 * </ol>
 * Replays return the resource id, not a frozen body: the caller re-reads the current representation.
 */
@Service
public class IdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:\\-]{8,255}$");
    private static final Duration WAIT_FOR_WINNER = Duration.ofSeconds(3);

    public record Request(String scope, String key, String operation, String requestHash) {
    }

    public record Created(UUID resourceId, int status) {
    }

    public record Outcome(UUID resourceId, int status, boolean replayed) {
    }

    private final IdempotencyRepository repository;
    private final TransactionTemplate joinOrNew;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final ShipFlowProperties.Idempotency props;
    private final ShipFlowMetrics metrics;

    public IdempotencyService(IdempotencyRepository repository, PlatformTransactionManager tx, Clock clock,
                              ShipFlowProperties props, ShipFlowMetrics metrics) {
        this.repository = repository;
        this.joinOrNew = new TransactionTemplate(tx);
        this.requiresNew = new TransactionTemplate(tx);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.props = props.idempotency();
        this.metrics = metrics;
    }

    public static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ShipFlowException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key header must be 8-255 characters of [A-Za-z0-9._:-]");
        }
    }

    public Outcome execute(Request req, Supplier<Created> action) {
        validateKey(req.key());
        Instant now = clock.instant();
        Optional<Long> claim = requiresNew.execute(s -> {
            repository.deleteExpired(req.scope(), req.key(), now);
            return repository.tryClaim(req.scope(), req.key(), req.operation(), req.requestHash(), now,
                            now.plus(props.ttl()))
                    .or(() -> repository.reclaimStale(req.scope(), req.key(), req.requestHash(),
                            now.minus(props.inProgressTimeout()), now));
        });
        if (claim == null || claim.isEmpty()) {
            return replayOrReject(req);
        }
        long id = claim.get();
        try {
            return joinOrNew.execute(s -> {
                Created created = action.get();
                repository.complete(id, created.resourceId(), created.status(), clock.instant());
                return new Outcome(created.resourceId(), created.status(), false);
            });
        } catch (RuntimeException e) {
            try {
                requiresNew.executeWithoutResult(s -> repository.release(id));
            } catch (RuntimeException releaseError) {
                log.warn("failed to release idempotency claim id={}", id, releaseError);
            }
            throw e;
        }
    }

    private Outcome replayOrReject(Request req) {
        long deadline = System.nanoTime() + WAIT_FOR_WINNER.toNanos();
        while (true) {
            Optional<IdempotencyRepository.IdempotencyRecord> existing = repository.find(req.scope(), req.key());
            if (existing.isPresent()) {
                var rec = existing.get();
                if (!rec.requestHash().equals(req.requestHash()) || !rec.operation().equals(req.operation())) {
                    throw new ShipFlowException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                            "Idempotency-Key was already used with a different request payload");
                }
                if (rec.completed()) {
                    metrics.idempotentReplay(req.operation());
                    return new Outcome(rec.resourceId(), rec.responseStatus(), true);
                }
            }
            if (System.nanoTime() > deadline) {
                throw new ShipFlowException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                        "A request with this Idempotency-Key is being processed; retry later");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ShipFlowException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "interrupted");
            }
        }
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void purgeExpired() {
        int n = repository.deleteAllExpired(clock.instant());
        if (n > 0) {
            log.info("purged {} expired idempotency records", n);
        }
    }
}
