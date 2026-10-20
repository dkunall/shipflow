package com.shipflow.booking.application;

import com.shipflow.booking.api.ConsigneeDto;
import com.shipflow.booking.api.CreateShipmentRequest;
import com.shipflow.booking.api.MoneyDto;
import com.shipflow.shipment.domain.PaymentMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RFC 4180-style CSV (quoted fields, escaped quotes, commas/newlines inside quotes) -> booking requests.
 * Header names (case-insensitive): merchant_order_ref, origin_hub, name, phone, address_line1, address_line2,
 * landmark, city, pincode, lat, lng, weight_g, payment_mode, cod_amount, declared_value, currency.
 */
public final class CsvShipmentParser {
    public record Row(int line, CreateShipmentRequest request, String error) {
    }

    public static final List<String> REQUIRED = List.of("merchant_order_ref", "origin_hub", "name", "phone",
            "address_line1", "city", "pincode", "weight_g", "payment_mode", "declared_value", "currency");

    private CsvShipmentParser() {
    }

    public static List<Row> parse(Reader reader, int maxRows) throws IOException {
        List<List<String>> records = records(new BufferedReader(reader));
        if (records.isEmpty()) {
            throw new IllegalArgumentException("empty CSV");
        }
        List<String> header = records.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        for (String r : REQUIRED) {
            if (!header.contains(r)) {
                throw new IllegalArgumentException("missing CSV column: " + r);
            }
        }
        if (records.size() - 1 > maxRows) {
            throw new IllegalArgumentException("too many rows: " + (records.size() - 1) + " > " + maxRows);
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> rec = records.get(i);
            if (rec.size() == 1 && rec.get(0).isBlank()) {
                continue;
            }
            Map<String, String> m = new HashMap<>();
            for (int c = 0; c < header.size() && c < rec.size(); c++) {
                String v = rec.get(c).trim();
                m.put(header.get(c), v.isEmpty() ? null : v);
            }
            try {
                rows.add(new Row(i + 1, toRequest(m), null));
            } catch (RuntimeException e) {
                rows.add(new Row(i + 1, null, e.getMessage()));
            }
        }
        return rows;
    }

    private static CreateShipmentRequest toRequest(Map<String, String> m) {
        String currency = m.get("currency");
        PaymentMode mode = parse(m, "payment_mode", v -> PaymentMode.valueOf(v.toUpperCase(Locale.ROOT)));
        MoneyDto cod = m.get("cod_amount") == null ? null
                : new MoneyDto(parse(m, "cod_amount", BigDecimal::new), currency);
        return new CreateShipmentRequest(m.get("merchant_order_ref"), m.get("origin_hub"),
                new ConsigneeDto(m.get("name"), m.get("phone"), m.get("address_line1"), m.get("address_line2"),
                        m.get("landmark"), m.get("city"), m.get("pincode"),
                        m.get("lat") == null ? null : parse(m, "lat", Double::valueOf),
                        m.get("lng") == null ? null : parse(m, "lng", Double::valueOf)),
                parse(m, "weight_g", Integer::valueOf), mode, cod,
                new MoneyDto(parse(m, "declared_value", BigDecimal::new), currency));
    }

    /** Parses a required column, naming the column in the error so the merchant can fix the row. */
    private static <T> T parse(Map<String, String> m, String column, java.util.function.Function<String, T> parser) {
        String v = require(m, column);
        try {
            return parser.apply(v);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(column + ": invalid value '" + v + "'");
        }
    }

    private static String require(Map<String, String> m, String k) {
        String v = m.get(k);
        if (v == null) {
            throw new IllegalArgumentException(k + " is required");
        }
        return v;
    }

    static List<List<String>> records(BufferedReader r) throws IOException {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int ch;
        boolean any = false;
        while ((ch = r.read()) != -1) {
            any = true;
            char c = (char) ch;
            if (inQuotes) {
                if (c == '"') {
                    r.mark(1);
                    int nx = r.read();
                    if (nx == '"') {
                        field.append('"');
                    } else {
                        inQuotes = false;
                        if (nx != -1) {
                            r.reset();
                        }
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cur.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    r.mark(1);
                    if (r.read() != '\n') {
                        r.reset();
                    }
                }
                cur.add(field.toString());
                field.setLength(0);
                out.add(cur);
                cur = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quoted field");
        }
        if (any && (field.length() > 0 || !cur.isEmpty())) {
            cur.add(field.toString());
            out.add(cur);
        }
        return out;
    }
}
