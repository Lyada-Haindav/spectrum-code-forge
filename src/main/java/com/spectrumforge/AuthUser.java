package com.spectrumforge;

import java.util.LinkedHashMap;
import java.util.Map;

record AuthUser(
    String id,
    String name,
    String email,
    boolean emailVerified,
    boolean premium,
    String premiumPlanCode,
    String premiumPlanLabel,
    String premiumExpiresAt,
    int dailyLimit,
    int dailyUsed,
    int dailyRemaining,
    boolean canGenerate
) {
    Map<String, Object> toMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("name", name);
        payload.put("email", email);
        payload.put("emailVerified", emailVerified);
        payload.put("premium", premium);
        payload.put("premiumPlanCode", premiumPlanCode);
        payload.put("premiumPlanLabel", premiumPlanLabel);
        payload.put("premiumExpiresAt", premiumExpiresAt);
        payload.put("dailyLimit", dailyLimit);
        payload.put("dailyUsed", dailyUsed);
        payload.put("dailyRemaining", dailyRemaining);
        payload.put("canGenerate", canGenerate);
        return payload;
    }
}
