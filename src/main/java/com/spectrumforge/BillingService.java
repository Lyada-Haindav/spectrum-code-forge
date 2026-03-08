package com.spectrumforge;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BillingService {
    private final Path paymentsFile;
    private final List<StoredPayment> payments = new ArrayList<>();

    BillingService(Path dataDir) {
        this.paymentsFile = dataDir.resolve("payments.json");
        try {
            Files.createDirectories(dataDir);
        } catch (IOException error) {
            throw new AppException(500, "Unable to prepare premium billing storage.");
        }
        loadPayments();
    }

    synchronized Map<String, Object> checkoutFor(AuthUser user, AppConfig config) {
        if (!config.premiumCheckoutEnabled()) {
            throw new AppException(503, "Premium checkout is unavailable right now.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("premium", user.premium());
        payload.put("upiId", config.premiumUpiId());
        payload.put("upiName", config.premiumUpiName());
        payload.put("plans", buildCheckoutPlans(user, config));
        payload.put("defaultPlanCode", config.defaultPremiumPlan().code());
        return payload;
    }

    synchronized Map<String, Object> confirmPremium(
        AuthUser user,
        AppConfig config,
        String planCode,
        String transactionReference,
        AuthService authService
    ) {
        if (!config.premiumCheckoutEnabled()) {
            throw new AppException(503, "Premium checkout is unavailable right now.");
        }

        if (user.premium()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user", user.toMap());
            payload.put("alreadyPremium", true);
            return payload;
        }

        PremiumPlan plan = config.premiumPlan(planCode);
        String normalizedReference = normalizeReference(transactionReference);
        boolean exists = payments.stream()
            .anyMatch(payment -> payment.transactionReference().equalsIgnoreCase(normalizedReference));
        if (exists) {
            throw new AppException(409, "This payment reference is already linked to another premium account.");
        }

        Instant activatedAt = Instant.now();
        String expiresAt = plan.expiresAtFrom(activatedAt).toString();
        AuthUser upgradedUser = authService.activatePremium(
            user.id(),
            plan.code(),
            plan.label(),
            expiresAt,
            config.freeDailyLimit()
        );
        StoredPayment payment = new StoredPayment(
            UUID.randomUUID().toString(),
            user.id(),
            activatedAt.toString(),
            normalizedReference,
            plan.code(),
            plan.label(),
            plan.priceInr(),
            config.premiumUpiId(),
            expiresAt,
            "completed"
        );
        payments.add(payment);
        savePayments();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", upgradedUser.toMap());
        payload.put("payment", Map.of(
            "id", payment.id(),
            "reference", payment.transactionReference(),
            "planCode", payment.planCode(),
            "planLabel", payment.planLabel(),
            "amountInr", payment.amountInr(),
            "expiresAt", payment.expiresAt(),
            "createdAt", payment.createdAt()
        ));
        return payload;
    }

    private List<Object> buildCheckoutPlans(AuthUser user, AppConfig config) {
        List<Object> plans = new ArrayList<>();
        for (PremiumPlan plan : config.enabledPremiumPlans()) {
            String note = buildNote(user, plan);
            Map<String, Object> item = new LinkedHashMap<>();
            item.putAll(plan.toMap());
            item.put("note", note);
            item.put("upiUrl", buildUpiUrl(config, plan, note));
            plans.add(item);
        }
        return plans;
    }

    private String buildUpiUrl(AppConfig config, PremiumPlan plan, String note) {
        return "upi://pay"
            + "?pa=" + encode(config.premiumUpiId())
            + "&pn=" + encode(config.premiumUpiName())
            + "&am=" + encode(String.valueOf(plan.priceInr()))
            + "&cu=INR"
            + "&tn=" + encode(note);
    }

    private String buildNote(AuthUser user, PremiumPlan plan) {
        return "Spectrum " + plan.label() + " " + user.id().substring(0, Math.min(8, user.id().length()));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalizeReference(String reference) {
        String value = reference == null ? "" : reference.trim().replaceAll("\\s+", "").toUpperCase();
        if (value.length() < 8 || value.length() > 32 || !value.matches("[A-Z0-9-]+")) {
            throw new AppException(400, "Enter a valid UPI transaction reference or UTR.");
        }
        return value;
    }

    private synchronized void loadPayments() {
        payments.clear();
        if (!Files.exists(paymentsFile)) {
            return;
        }

        try {
            String json = Files.readString(paymentsFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }

            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(json), "Invalid payments store.");
            for (Object entry : MiniJson.asList(root.get("payments"))) {
                if (!(entry instanceof Map<?, ?>)) {
                    continue;
                }

                Map<String, Object> item = MiniJson.asObject(entry, "Invalid payments store.");
                String id = readString(item, "id");
                String userId = readString(item, "userId");
                String createdAt = readString(item, "createdAt");
                String transactionReference = readString(item, "transactionReference");
                String planCode = readString(item, "planCode");
                String planLabel = readString(item, "planLabel");
                String upiId = readString(item, "upiId");
                String expiresAt = readString(item, "expiresAt");
                String status = readString(item, "status");
                int amountInr = readInt(item, "amountInr");

                if (id.isBlank() || userId.isBlank() || transactionReference.isBlank() || createdAt.isBlank()) {
                    continue;
                }

                payments.add(new StoredPayment(
                    id,
                    userId,
                    createdAt,
                    transactionReference,
                    planCode,
                    planLabel,
                    amountInr,
                    upiId,
                    expiresAt,
                    status
                ));
            }
        } catch (IOException error) {
            throw new AppException(500, "Unable to read premium billing data.");
        }
    }

    private synchronized void savePayments() {
        List<Object> serializedPayments = new ArrayList<>();
        for (StoredPayment payment : payments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", payment.id());
            item.put("userId", payment.userId());
            item.put("createdAt", payment.createdAt());
            item.put("transactionReference", payment.transactionReference());
            item.put("planCode", payment.planCode());
            item.put("planLabel", payment.planLabel());
            item.put("amountInr", payment.amountInr());
            item.put("upiId", payment.upiId());
            item.put("expiresAt", payment.expiresAt());
            item.put("status", payment.status());
            serializedPayments.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payments", serializedPayments);
        writeAtomically(paymentsFile, MiniJson.stringify(payload));
    }

    private void writeAtomically(Path target, String content) {
        try {
            Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new AppException(500, "Unable to persist premium billing data.");
        }
    }

    private String readString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int readInt(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record StoredPayment(
        String id,
        String userId,
        String createdAt,
        String transactionReference,
        String planCode,
        String planLabel,
        int amountInr,
        String upiId,
        String expiresAt,
        String status
    ) {
    }
}
