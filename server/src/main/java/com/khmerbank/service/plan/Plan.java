package com.khmerbank.service.plan;

import com.khmerbank.exception.ApiException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Server-side whitelist of valid plans. Frontend may PROPOSE a planId,
 * but the server always recomputes days from this table.
 *
 * <p>All plans are FREE right now per project policy.
 */
public enum Plan {
    M1("1month", "1 Month",  30,  "Free"),
    M2("2month", "2 Months", 60,  "Free"),
    M3("3month", "3 Months", 90,  "Free"),
    Y1("1year",  "1 Year",   365, "Free · BEST VALUE");

    public final String id;
    public final String label;
    public final int days;
    public final String discount;

    Plan(String id, String label, int days, String discount) {
        this.id = id;
        this.label = label;
        this.days = days;
        this.discount = discount;
    }

    private static final Map<String, Plan> BY_ID =
            java.util.Arrays.stream(values()).collect(Collectors.toMap(p -> p.id, p -> p));

    public static Plan require(String id) {
        Plan p = BY_ID.get(id);
        if (p == null) {
            throw ApiException.badRequest("BAD_PLAN",
                    "Unknown plan: " + id + ". Valid: " + BY_ID.keySet());
        }
        return p;
    }

    public static List<Plan> all() {
        return List.of(values());
    }
}
