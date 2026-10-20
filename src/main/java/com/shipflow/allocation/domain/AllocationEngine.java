package com.shipflow.allocation.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * Pure allocation policy: filter (hard constraints) -> price (rate card) -> score (weighted rules) -> rank.
 * Deterministic: ties are broken by lower price, then carrier code. Has no I/O, so it is unit-tested directly.
 */
public final class AllocationEngine {
    private final List<CandidateFilter> filters;
    private final List<AllocationRule> rules;
    private final ToDoubleFunction<String> weights;

    public AllocationEngine(List<CandidateFilter> filters, List<AllocationRule> rules, ToDoubleFunction<String> weights) {
        this.filters = List.copyOf(filters);
        this.rules = List.copyOf(rules);
        this.weights = weights;
    }

    public AllocationPlan plan(AllocationContext ctx, List<CarrierCandidate> candidates) {
        List<AllocationPlan.Rejection> rejected = new ArrayList<>();
        List<PricedCandidate> eligible = new ArrayList<>();
        for (CarrierCandidate c : candidates) {
            Optional<AllocationPlan.Rejection> rejection = firstRejection(c, ctx);
            if (rejection.isPresent()) {
                rejected.add(rejection.get());
                continue;
            }
            var quote = c.rateCard().quote(ctx.weightGrams(), ctx.codAmount());
            if (quote.isEmpty()) {
                rejected.add(new AllocationPlan.Rejection(c.carrier().code(), "rate-card",
                        "no rate for " + ctx.weightGrams() + " g" + (ctx.isCod() ? " / COD currency" : "")));
                continue;
            }
            eligible.add(new PricedCandidate(c, quote.get()));
        }
        CandidatePool pool = new CandidatePool(eligible);
        List<AllocationPlan.ScoredCandidate> ranked = new ArrayList<>();
        for (PricedCandidate p : eligible) {
            List<RuleScore> scores = new ArrayList<>();
            double total = 0;
            for (AllocationRule rule : rules) {
                double w = rule.effectiveWeight(weights.applyAsDouble(rule.key()), ctx);
                if (w <= 0) {
                    continue;
                }
                RuleScore rs = new RuleScore(rule.key(), clamp(rule.score(p, pool, ctx)), w, rule.detail(p));
                scores.add(rs);
                total += rs.contribution();
            }
            ranked.add(new AllocationPlan.ScoredCandidate(p, List.copyOf(scores), total));
        }
        ranked.sort(Comparator.comparingDouble(AllocationPlan.ScoredCandidate::total).reversed()
                .thenComparing(sc -> sc.priced().quote().total().amount(), BigDecimal::compareTo)
                .thenComparing(AllocationPlan.ScoredCandidate::code));
        return new AllocationPlan(ctx, List.copyOf(ranked), List.copyOf(rejected));
    }

    private Optional<AllocationPlan.Rejection> firstRejection(CarrierCandidate c, AllocationContext ctx) {
        for (CandidateFilter f : filters) {
            Optional<String> reason = f.reject(c, ctx);
            if (reason.isPresent()) {
                return Optional.of(new AllocationPlan.Rejection(c.carrier().code(), f.name(), reason.get()));
            }
        }
        return Optional.empty();
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.max(0, Math.min(1, v));
    }
}
