package com.shipflow.allocation;

import com.shipflow.allocation.domain.AllocationContext;
import com.shipflow.allocation.domain.AllocationEngine;
import com.shipflow.allocation.domain.AllocationPlan;
import com.shipflow.allocation.domain.CandidateFilter;
import com.shipflow.allocation.domain.CarrierCandidate;
import com.shipflow.allocation.filters.Filters;
import com.shipflow.allocation.rules.Rules;
import com.shipflow.carrier.domain.CarrierInfo;
import com.shipflow.carrier.domain.CarrierKind;
import com.shipflow.carrier.domain.RateCard;
import com.shipflow.carrier.domain.RateSlab;
import com.shipflow.common.money.Money;
import com.shipflow.scorecard.CarrierScorecard;
import com.shipflow.scorecard.ScorecardMath;
import com.shipflow.support.TestProps;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AllocationEngineTest {
    private static final List<CandidateFilter> FILTERS = List.of(new Filters.ExcludedCarrierFilter(),
            new Filters.CodSupportFilter(), new Filters.WeightLimitFilter(), new Filters.CapacityFilter());

    private final AllocationEngine engine = new AllocationEngine(FILTERS, List.of(new Rules.CostRule(),
            new Rules.SlaRule(), new Rules.CapacityHeadroomRule(), new Rules.OnTimeRule(), new Rules.FirstAttemptRule(),
            new Rules.RtoRule()), TestProps.WEIGHTS::get);

    static CarrierCandidate cand(long id, String code, String price, int sla, boolean cod, int cap, int reserved,
                                 CarrierScorecard sc) {
        return new CarrierCandidate(new CarrierInfo(id, code, code, CarrierKind.THIRD_PARTY, "SIMULATED_3PL", true, "s"),
                sla, cod, new RateCard("AED", List.of(new RateSlab(5000, new BigDecimal(price))), new BigDecimal("1.00"),
                10_000, new BigDecimal("1.5"), new BigDecimal("2.00")), cap, reserved, sc);
    }

    static CarrierScorecard prior() {
        return ScorecardMath.scorecard(0, 0, 0, 0, TestProps.scorecard());
    }

    static AllocationContext ctx(Money cod, String risk, Set<Long> excluded) {
        return new AllocationContext(UUID.randomUUID(), "Z", 1200, cod, LocalDate.of(2026, 10, 1), risk, excluded);
    }

    @Test
    void hardConstraintsRejectWithReasons() {
        var plan = engine.plan(ctx(Money.of("100", "AED"), "LOW", Set.of(4L)), List.of(
                cand(1, "OK", "10", 2, true, 10, 0, prior()),
                cand(2, "NOCOD", "5", 1, false, 10, 0, prior()),
                cand(3, "FULL", "5", 1, true, 10, 10, prior()),
                cand(4, "EXCL", "5", 1, true, 10, 0, prior())));
        assertThat(plan.ranked()).extracting(AllocationPlan.ScoredCandidate::code).containsExactly("OK");
        assertThat(plan.rejected()).extracting(AllocationPlan.Rejection::carrier)
                .containsExactlyInAnyOrder("NOCOD", "FULL", "EXCL");
        assertThat(plan.rejected()).extracting(AllocationPlan.Rejection::filter).contains("cod", "capacity", "excluded");
    }

    @Test
    void overweightIsRejected() {
        var c = new AllocationContext(UUID.randomUUID(), "Z", 50_000, null, LocalDate.now(), "LOW", Set.of());
        var plan = engine.plan(c, List.of(cand(1, "A", "10", 2, true, 10, 0, prior())));
        assertThat(plan.ranked()).isEmpty();
        assertThat(plan.rejected().get(0).filter()).isEqualTo("weight");
    }

    @Test
    void scoresAreWeightedSumsInUnitRangeAndExplainEachRule() {
        var plan = engine.plan(ctx(null, "LOW", Set.of()), List.of(
                cand(1, "CHEAP", "10", 3, true, 100, 0, prior()),
                cand(2, "FAST", "20", 1, true, 100, 0, prior())));
        for (var sc : plan.ranked()) {
            assertThat(sc.total()).isBetween(0.0, 1.0);
            assertThat(sc.scores()).hasSize(6);
            double sum = sc.scores().stream().mapToDouble(s -> s.score() * s.weight()).sum();
            assertThat(sc.total()).isCloseTo(sum, within(1e-9));
        }
        var cheap = plan.ranked().stream().filter(s -> s.code().equals("CHEAP")).findFirst().orElseThrow();
        assertThat(cheap.scores().stream().filter(s -> s.rule().equals("cost")).findFirst().orElseThrow().score()).isEqualTo(1.0);
        assertThat(cheap.scores().stream().filter(s -> s.rule().equals("sla")).findFirst().orElseThrow().score())
                .isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    void tiesBreakOnPriceThenCodeDeterministically() {
        var plan = engine.plan(ctx(null, "LOW", Set.of()), List.of(
                cand(2, "ZULU", "10", 2, true, 100, 0, prior()),
                cand(1, "ALPHA", "10", 2, true, 100, 0, prior())));
        assertThat(plan.ranked()).extracting(AllocationPlan.ScoredCandidate::code).containsExactly("ALPHA", "ZULU");
    }

    @Test
    void highRiskDoublesFirstAttemptWeight() {
        var low = engine.plan(ctx(null, "LOW", Set.of()), List.of(cand(1, "A", "10", 2, true, 100, 0, prior())));
        var high = engine.plan(ctx(null, "HIGH", Set.of()), List.of(cand(1, "A", "10", 2, true, 100, 0, prior())));
        double w = low.ranked().get(0).scores().stream().filter(s -> s.rule().equals("first-attempt")).findFirst().orElseThrow().weight();
        double wh = high.ranked().get(0).scores().stream().filter(s -> s.rule().equals("first-attempt")).findFirst().orElseThrow().weight();
        assertThat(wh).isEqualTo(2 * w);
    }

    @Test
    void codFeeIsPartOfThePriceComparison() {
        // CHEAP freight but a high COD fee floor vs a slightly dearer carrier
        var withCod = engine.plan(ctx(Money.of("1000", "AED"), "LOW", Set.of()), List.of(
                cand(1, "A", "10", 2, true, 100, 0, prior()),
                cand(2, "B", "10.50", 2, true, 100, 0, prior())));
        // both 1.5% of 1000 = 15.00 COD fee => totals 25.00 and 25.50
        assertThat(withCod.ranked().get(0).priced().quote().total()).isEqualTo(Money.of("25.00", "AED"));
    }

    /**
     * Feature A simulation: two equally priced carriers; BRAVO truly delivers better. Outcomes of each allocation feed
     * the rolling scorecard; the share of shipments going to BRAVO must grow from the first to the last window.
     */
    @Test
    void simulationShiftsVolumeTowardTheBetterCarrier() {
        Map<String, double[]> truth = Map.of("ALPHA", new double[]{0.70, 0.60, 0.25}, // on-time, first-attempt, rto
                "BRAVO", new double[]{0.95, 0.90, 0.03});
        Map<String, long[]> counts = new HashMap<>(); // delivered, onTime, firstAttempt, rto
        counts.put("ALPHA", new long[4]);
        counts.put("BRAVO", new long[4]);
        Random rnd = new Random(42);
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            List<CarrierCandidate> cands = new ArrayList<>();
            long id = 1;
            for (String code : List.of("ALPHA", "BRAVO")) {
                long[] c = counts.get(code);
                cands.add(cand(id++, code, code.equals("ALPHA") ? "10.00" : "10.20", 2, true, 10_000, 0,
                        ScorecardMath.scorecard(c[0], c[1], c[2], c[3], TestProps.scorecard())));
            }
            String pick = engine.plan(ctx(null, "LOW", Set.of()), cands).ranked().get(0).code();
            chosen.add(pick);
            double[] t = truth.get(pick);
            long[] c = counts.get(pick);
            if (rnd.nextDouble() < t[2]) {
                c[3]++;
            } else {
                c[0]++;
                if (rnd.nextDouble() < t[0]) c[1]++;
                if (rnd.nextDouble() < t[1]) c[2]++;
            }
        }
        assertThat(chosen.get(0)).isEqualTo("ALPHA"); // cheaper, so it starts as the favourite
        long early = chosen.subList(0, 10).stream().filter("BRAVO"::equals).count();
        long late = chosen.subList(500, 600).stream().filter("BRAVO"::equals).count();
        assertThat(early).isLessThan(5);
        assertThat(late).isGreaterThanOrEqualTo(95);
    }
}
