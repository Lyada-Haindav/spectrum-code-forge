package com.spectrumforge;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

record PremiumPlan(
    String code,
    String label,
    String cycleLabel,
    int priceInr,
    Period duration
) {
    boolean enabled() {
        return priceInr > 0;
    }

    Instant expiresAtFrom(Instant activatedAt) {
        return ZonedDateTime.ofInstant(activatedAt, ZoneOffset.UTC)
            .plus(duration)
            .toInstant();
    }

    Map<String, Object> toMap() {
        return Map.of(
            "code", code,
            "label", label,
            "cycleLabel", cycleLabel,
            "priceInr", priceInr
        );
    }
}
