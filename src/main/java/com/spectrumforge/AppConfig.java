package com.spectrumforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record AppConfig(
    String apiKey,
    String baseUrl,
    String model,
    int port,
    String dataDir,
    int freeDailyLimit,
    String premiumUpiId,
    String premiumUpiName,
    List<PremiumPlan> premiumPlans,
    String brevoApiKey,
    String brevoSenderEmail,
    String brevoSenderName,
    String appBaseUrl
) {
    static AppConfig load() {
        Map<String, String> fileEnv = loadDotEnv(Path.of(".env"));

        String apiKey = value("GEMINI_API_KEY", fileEnv);
        if (apiKey.isBlank()) {
            apiKey = value("GOOGLE_API_KEY", fileEnv);
        }

        String baseUrl = value("GEMINI_BASE_URL", fileEnv);
        if (baseUrl.isBlank()) {
            baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        }

        String model = value("GEMINI_MODEL", fileEnv);
        if (model.isBlank()) {
            model = "gemini-2.5-flash-lite";
        }

        String portValue = value("PORT", fileEnv);
        int port = 3000;
        if (!portValue.isBlank()) {
            try {
                port = Integer.parseInt(portValue.trim());
            } catch (NumberFormatException ignored) {
                port = 3000;
            }
        }

        int freeDailyLimit = parseInt(value("FREE_DAILY_LIMIT", fileEnv), 6);
        String dataDir = value("DATA_DIR", fileEnv);
        if (dataDir.isBlank()) {
            dataDir = ".data";
        }
        String premiumUpiId = value("PREMIUM_UPI_ID", fileEnv);
        String premiumUpiName = value("PREMIUM_UPI_NAME", fileEnv);
        if (premiumUpiName.isBlank()) {
            premiumUpiName = "Spectrum Code Forge";
        }
        List<PremiumPlan> premiumPlans = loadPremiumPlans(fileEnv);

        String brevoApiKey = value("BREVO_API_KEY", fileEnv);
        String brevoSenderEmail = value("BREVO_SENDER_EMAIL", fileEnv);
        String brevoSenderName = value("BREVO_SENDER_NAME", fileEnv);
        if (brevoSenderName.isBlank()) {
            brevoSenderName = "Spectrum Code Forge";
        }

        String appBaseUrl = value("APP_BASE_URL", fileEnv);
        if (appBaseUrl.isBlank()) {
            appBaseUrl = value("RENDER_EXTERNAL_URL", fileEnv);
        }
        if (appBaseUrl.isBlank()) {
            appBaseUrl = "http://localhost:" + port;
        }

        return new AppConfig(
            apiKey,
            baseUrl,
            model,
            port,
            dataDir,
            freeDailyLimit,
            premiumUpiId,
            premiumUpiName,
            premiumPlans,
            brevoApiKey,
            brevoSenderEmail,
            brevoSenderName,
            appBaseUrl
        );
    }

    boolean configured() {
        return !apiKey.isBlank();
    }

    boolean premiumCheckoutEnabled() {
        return !premiumUpiId.isBlank() && enabledPremiumPlans().stream().findFirst().isPresent();
    }

    boolean emailDeliveryEnabled() {
        return !brevoApiKey.isBlank() && !brevoSenderEmail.isBlank();
    }

    List<PremiumPlan> enabledPremiumPlans() {
        return premiumPlans.stream()
            .filter(PremiumPlan::enabled)
            .toList();
    }

    PremiumPlan premiumPlan(String code) {
        String normalizedCode = code == null ? "" : code.trim().toLowerCase();
        for (PremiumPlan plan : enabledPremiumPlans()) {
            if (plan.code().equals(normalizedCode)) {
                return plan;
            }
        }
        throw new AppException(400, "Choose a valid premium plan.");
    }

    PremiumPlan defaultPremiumPlan() {
        return enabledPremiumPlans().stream()
            .findFirst()
            .orElseThrow(() -> new AppException(503, "Premium checkout is unavailable right now."));
    }

    private static String value(String key, Map<String, String> fileEnv) {
        String fromProcess = System.getenv(key);
        if (fromProcess != null && !fromProcess.isBlank()) {
            return fromProcess.trim();
        }
        return fileEnv.getOrDefault(key, "").trim();
    }

    private static int parseInt(String rawValue, int fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<PremiumPlan> loadPremiumPlans(Map<String, String> fileEnv) {
        List<PremiumPlan> plans = new ArrayList<>();
        plans.add(new PremiumPlan(
            "weekly",
            "Weekly plan",
            "7 days access",
            parseInt(value("PREMIUM_WEEKLY_PRICE_INR", fileEnv), 99),
            Period.ofWeeks(1)
        ));
        plans.add(new PremiumPlan(
            "monthly",
            "Monthly plan",
            "1 month access",
            parseInt(value("PREMIUM_MONTHLY_PRICE_INR", fileEnv), 399),
            Period.ofMonths(1)
        ));
        plans.add(new PremiumPlan(
            "yearly",
            "Yearly plan",
            "1 year access",
            parseInt(value("PREMIUM_YEARLY_PRICE_INR", fileEnv), 599),
            Period.ofYears(1)
        ));
        return List.copyOf(plans);
    }

    private static Map<String, String> loadDotEnv(Path file) {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(file)) {
            return values;
        }

        try {
            for (String rawLine : Files.readAllLines(file)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int separatorIndex = line.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }

                String key = line.substring(0, separatorIndex).trim();
                String value = line.substring(separatorIndex + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException ignored) {
            return values;
        }

        return values;
    }
}
