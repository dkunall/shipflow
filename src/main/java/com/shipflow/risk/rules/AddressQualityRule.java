package com.shipflow.risk.rules;

import com.shipflow.risk.RiskFactor;
import com.shipflow.risk.RiskInput;
import com.shipflow.risk.RiskRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Address completeness heuristics (max 30): no building/villa/flat number, very short address, no landmark,
 * placeholder text. GCC addresses are often landmark-based, so a missing landmark costs less than a missing number.
 */
@Component
@Order(10)
public class AddressQualityRule implements RiskRule {
    public static final String CODE = "ADDRESS_QUALITY";
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern PLACEHOLDER = Pattern.compile("^(na|n/a|test|xxx+|none|-+|\\.+|asdf)$",
            Pattern.CASE_INSENSITIVE);

    public String code() {
        return CODE;
    }

    public int maxPoints() {
        return 30;
    }

    public RiskFactor evaluate(RiskInput in) {
        String line1 = in.addressLine1() == null ? "" : in.addressLine1().trim();
        String full = (line1 + " " + (in.addressLine2() == null ? "" : in.addressLine2())).trim();
        int points = 0;
        List<String> reasons = new ArrayList<>();
        if (PLACEHOLDER.matcher(line1).matches()) {
            points += 15;
            reasons.add("placeholder address");
        }
        if (!HAS_DIGIT.matcher(full).matches()) {
            points += 10;
            reasons.add("no building/flat number");
        }
        if (full.length() < 20) {
            points += 8;
            reasons.add("address shorter than 20 chars");
        }
        if (in.landmark() == null || in.landmark().isBlank()) {
            points += 4;
            reasons.add("no landmark");
        }
        if (full.toLowerCase(Locale.ROOT).contains("call on arrival") || full.toLowerCase(Locale.ROOT).contains("call me")) {
            points += 3;
            reasons.add("address defers to phone call");
        }
        points = Math.min(points, maxPoints());
        return new RiskFactor(CODE, points, maxPoints(), reasons.isEmpty() ? "complete address" : String.join(", ", reasons));
    }
}
