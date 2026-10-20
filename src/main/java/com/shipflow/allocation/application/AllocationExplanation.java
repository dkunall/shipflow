package com.shipflow.allocation.application;

import com.shipflow.allocation.domain.AllocationPlan;
import com.shipflow.allocation.domain.RuleScore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the stored, human/machine-readable "why this carrier" document. */
final class AllocationExplanation {
    private AllocationExplanation() {
    }

    static Map<String, Object> build(AllocationPlan plan, String chosen, List<Map<String, Object>> reservationAttempts,
                                     String trigger) {
        Map<String, Object> doc = new LinkedHashMap<>();
        var ctx = plan.context();
        doc.put("trigger", trigger);
        doc.put("chosen", chosen);
        doc.put("context", Map.of(
                "zone", ctx.zoneCode(),
                "weightGrams", ctx.weightGrams(),
                "cod", ctx.isCod() ? ctx.codAmount().toString() : "none",
                "serviceDate", ctx.serviceDate().toString(),
                "riskBand", ctx.riskBand(),
                "excludedCarrierIds", ctx.excludedCarrierIds()));
        List<Map<String, Object>> ranked = new ArrayList<>();
        int rank = 1;
        for (AllocationPlan.ScoredCandidate sc : plan.ranked()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("rank", rank++);
            c.put("carrier", sc.code());
            c.put("totalScore", round(sc.total()));
            c.put("price", sc.priced().quote().total().toString());
            c.put("slaDays", sc.priced().candidate().slaDays());
            Map<String, Object> rules = new LinkedHashMap<>();
            for (RuleScore rs : sc.scores()) {
                rules.put(rs.rule(), Map.of("score", round(rs.score()), "weight", round(rs.weight()),
                        "contribution", round(rs.contribution()), "detail", rs.detail()));
            }
            c.put("rules", rules);
            ranked.add(c);
        }
        doc.put("ranked", ranked);
        doc.put("rejected", plan.rejected().stream()
                .map(r -> Map.of("carrier", r.carrier(), "filter", r.filter(), "reason", r.reason())).toList());
        doc.put("reservationAttempts", reservationAttempts);
        if (chosen != null && !plan.ranked().isEmpty()) {
            doc.put("summary", summary(plan, chosen));
        } else {
            doc.put("summary", plan.ranked().isEmpty() ? "no eligible carrier" : "all eligible carriers ran out of capacity");
        }
        return doc;
    }

    private static String summary(AllocationPlan plan, String chosen) {
        AllocationPlan.ScoredCandidate win = plan.ranked().stream().filter(s -> s.code().equals(chosen)).findFirst()
                .orElseThrow();
        RuleScore top = win.scores().stream().max((a, b) -> Double.compare(a.contribution(), b.contribution()))
                .orElse(null);
        return chosen + " scored " + round(win.total()) + (top == null ? "" : "; strongest factor: " + top.rule()
                + " (" + top.detail() + ")") + "; " + plan.ranked().size() + " eligible, " + plan.rejected().size()
                + " rejected";
    }

    static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
