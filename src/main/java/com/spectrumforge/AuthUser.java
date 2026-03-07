package com.spectrumforge;

import java.util.Map;

record AuthUser(
    String id,
    String name,
    String email,
    boolean emailVerified,
    boolean premium,
    int dailyLimit,
    int dailyUsed,
    int dailyRemaining,
    boolean canGenerate
) {
    Map<String, Object> toMap() {
        return Map.of(
            "id", id,
            "name", name,
            "email", email,
            "emailVerified", emailVerified,
            "premium", premium,
            "dailyLimit", dailyLimit,
            "dailyUsed", dailyUsed,
            "dailyRemaining", dailyRemaining,
            "canGenerate", canGenerate
        );
    }
}
