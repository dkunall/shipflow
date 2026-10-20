package com.shipflow.allocation.rules;

import com.shipflow.allocation.domain.AllocationContext;
import com.shipflow.allocation.domain.AllocationRule;
import com.shipflow.allocation.domain.CandidatePool;
import com.shipflow.allocation.domain.PricedCandidate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;

/** Built-in scoring strategies. Add one = add an AllocationRule bean + a weight in config. */
public final class Rules {
    private Rules() {
    }

    /** Cheapest total (freight + COD fee) scores 1, most expensive 0 (min-max normalised). */
    @Component
    @Order(10)
    public static class CostRule implements AllocationRule {
        public String key() {
            return "cost";
        }

        public double score(PricedCandidate c, CandidatePool pool, AllocationContext ctx) {
            BigDecimal min = pool.minPrice();
            BigDecimal range = pool.maxPrice().subtract(min);
            if (range.signum() == 0) {
                return 1.0;
            }
            BigDecimal over = c.quote().total().amount().subtract(min);
            return 1.0 - over.divide(range, MathContext.DECIMAL64).doubleValue();
        }

        public String detail(PricedCandidate c) {
            return "total " + c.quote().total() + " (freight " + c.quote().freight().amount()
                    + ", cod fee " + c.quote().codFee().amount() + ")";
        }
    }

    /** Fastest SLA scores 1, slowest 0. */
    @Component
    @Order(20)
    public static class SlaRule implements AllocationRule {
        public String key() {
            return "sla";
        }

        public double score(PricedCandidate c, CandidatePool pool, AllocationContext ctx) {
            int range = pool.maxSla() - pool.minSla();
            return range == 0 ? 1.0 : (double) (pool.maxSla() - c.candidate().slaDays()) / range;
        }

        public String detail(PricedCandidate c) {
            return "SLA " + c.candidate().slaDays() + " day(s)";
        }
    }

    /** Prefers carriers with headroom left today (spreads load before a carrier is saturated). */
    @Component
    @Order(30)
    public static class CapacityHeadroomRule implements AllocationRule {
        public String key() {
            return "capacity";
        }

        public double score(PricedCandidate c, CandidatePool pool, AllocationContext ctx) {
            int cap = c.candidate().capacity();
            return cap <= 0 ? 0.0 : (double) c.candidate().remainingCapacity() / cap;
        }

        public String detail(PricedCandidate c) {
            return c.candidate().remainingCapacity() + "/" + c.candidate().capacity() + " slots left";
        }
    }

    /** Smoothed rolling on-time delivery rate in this zone. */
    @Component
    @Order(40)
    public static class OnTimeRule implements AllocationRule {
        public String key() {
            return "on-time";
        }

        public double score(PricedCandidate c, CandidatePool pool, AllocationContext ctx) {
            return c.candidate().scorecard().onTimeRate();
        }

        public String detail(PricedCandidate c) {
            var s = c.candidate().scorecard();
            return String.format("on-time %.1f%% (%d/%d raw)", s.onTimeRate() * 100, s.onTime(), s.delivered());
        }
    }

    /** Smoothed first-attempt delivery rate; weight doubled for HIGH delivery-risk shipments. */
    @Component
    @Order(50)
    public static class FirstAttemptRule implements AllocationRule {
        public String key() {
            return "first-attempt";
        }

        public double score(PricedCandidate c, CandidatePool pool, AllocationContext ctx) {
            return c.candidate().scorecard().firstAttemptRate();
        }

        @Override
        public double effectiveWeight(double configured, AllocationContext ctx) {
            return ctx.isHighRisk() ? configured * 2 : configured;
        }

        public String detail(PricedCandidate c) {
            var s = c.candidate().scorecard();
            return String.format("first-attempt %.1f%% (%d/%d raw)", s.firstAttemptRate() * 100, s.firstAttempt(), s.delivered());
        }
    }

    /** 1 - smoothed RTO rate. */
    @Component
    @Order(60)
    public static class RtoRule implements AllocationRule {
        public String key() {
            return "rto";
        }

        public double score(PricedCandidate c, CandidatePool pool, AllocationContext ctx) {
            return 1.0 - c.candidate().scorecard().rtoRate();
        }

        public String detail(PricedCandidate c) {
            var s = c.candidate().scorecard();
            return String.format("RTO %.1f%% (%d raw)", s.rtoRate() * 100, s.rto());
        }
    }
}
