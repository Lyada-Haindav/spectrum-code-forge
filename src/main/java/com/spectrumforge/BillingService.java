package com.spectrumforge;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;

import static com.mongodb.client.model.Filters.eq;

final class BillingService {
    private final MongoCollection<Document> payments;

    BillingService(MongoStore store) {
        this.payments = store.payments();
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
        Instant activatedAt = Instant.now();
        String expiresAt = plan.expiresAtFrom(activatedAt).toString();
        AuthUser upgradedUser = authService.activatePremium(
            user.id(),
            plan.code(),
            plan.label(),
            expiresAt,
            config.freeDailyLimit()
        );

        Document payment = new Document("_id", UUID.randomUUID().toString())
            .append("userId", user.id())
            .append("createdAt", activatedAt.toString())
            .append("transactionReference", normalizedReference)
            .append("planCode", plan.code())
            .append("planLabel", plan.label())
            .append("amountInr", plan.priceInr())
            .append("upiId", config.premiumUpiId())
            .append("expiresAt", expiresAt)
            .append("status", "completed");

        try {
            payments.insertOne(payment);
        } catch (MongoWriteException error) {
            if (error.getError() != null && error.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new AppException(409, "This payment reference is already linked to another premium account.");
            }
            throw new AppException(500, "Unable to persist premium billing data.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", upgradedUser.toMap());
        payload.put("payment", Map.of(
            "id", payment.getString("_id"),
            "reference", payment.getString("transactionReference"),
            "planCode", payment.getString("planCode"),
            "planLabel", payment.getString("planLabel"),
            "amountInr", payment.getInteger("amountInr", 0),
            "expiresAt", payment.getString("expiresAt"),
            "createdAt", payment.getString("createdAt")
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
}
