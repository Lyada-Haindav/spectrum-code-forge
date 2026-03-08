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
    private static final String STATUS_PENDING_REVIEW = "pending_review";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";

    private final MongoCollection<Document> payments;

    BillingService(MongoStore store) {
        this.payments = store.payments();
    }

    synchronized Map<String, Object> checkoutFor(AuthUser user, AppConfig config) {
        if (!config.premiumCheckoutEnabled()) {
            throw new AppException(503, "Premium checkout is unavailable right now.");
        }

        Document pendingPayment = findPendingPaymentByUser(user.id());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("premium", user.premium());
        payload.put("upiId", config.premiumUpiId());
        payload.put("upiName", config.premiumUpiName());
        payload.put("plans", buildCheckoutPlans(user, config));
        payload.put("defaultPlanCode", pendingPayment == null
            ? config.defaultPremiumPlan().code()
            : readString(pendingPayment, "planCode"));
        payload.put("pendingPayment", pendingPayment == null ? null : toPaymentSummary(pendingPayment));
        return payload;
    }

    synchronized Map<String, Object> submitPremiumRequest(AuthUser user, AppConfig config, String planCode, String transactionReference) {
        if (!config.premiumCheckoutEnabled()) {
            throw new AppException(503, "Premium checkout is unavailable right now.");
        }

        if (user.premium()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user", user.toMap());
            payload.put("alreadyPremium", true);
            return payload;
        }

        Document existingPendingPayment = findPendingPaymentByUser(user.id());
        if (existingPendingPayment != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user", user.toMap());
            payload.put("pendingReview", true);
            payload.put("alreadyPending", true);
            payload.put("payment", toPaymentSummary(existingPendingPayment));
            return payload;
        }

        PremiumPlan plan = config.premiumPlan(planCode);
        String normalizedReference = normalizeReference(transactionReference);
        Instant submittedAt = Instant.now();

        Document payment = new Document("_id", UUID.randomUUID().toString())
            .append("userId", user.id())
            .append("userName", user.name())
            .append("userEmail", user.email())
            .append("createdAt", submittedAt.toString())
            .append("transactionReference", normalizedReference)
            .append("planCode", plan.code())
            .append("planLabel", plan.label())
            .append("amountInr", plan.priceInr())
            .append("upiId", config.premiumUpiId())
            .append("status", STATUS_PENDING_REVIEW);

        try {
            payments.insertOne(payment);
        } catch (MongoWriteException error) {
            if (error.getError() != null && error.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new AppException(409, "This payment reference is already linked to another premium request.");
            }
            throw new AppException(500, "Unable to persist premium billing data.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", user.toMap());
        payload.put("pendingReview", true);
        payload.put("payment", toPaymentSummary(payment));
        return payload;
    }

    synchronized Map<String, Object> reviewQueue(AppConfig config, String reviewKey) {
        requireReviewKey(config, reviewKey);

        List<Object> items = payments.find(eq("status", STATUS_PENDING_REVIEW))
            .sort(new Document("createdAt", -1))
            .map(this::toPaymentSummary)
            .into(new ArrayList<>());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payments", items);
        payload.put("count", items.size());
        return payload;
    }

    synchronized Map<String, Object> approvePendingPayment(
        AppConfig config,
        String reviewKey,
        String paymentId,
        AuthService authService
    ) {
        requireReviewKey(config, reviewKey);
        String normalizedPaymentId = normalizePaymentId(paymentId);
        Document payment = requirePayment(normalizedPaymentId);
        String status = readString(payment, "status");
        if (STATUS_APPROVED.equals(status)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("alreadyApproved", true);
            payload.put("payment", toPaymentSummary(payment));
            return payload;
        }
        if (!STATUS_PENDING_REVIEW.equals(status)) {
            throw new AppException(409, "Only pending payments can be approved.");
        }

        PremiumPlan plan = config.premiumPlan(readString(payment, "planCode"));
        Instant approvedAt = Instant.now();
        String expiresAt = plan.expiresAtFrom(approvedAt).toString();
        payments.updateOne(
            eq("_id", normalizedPaymentId),
            new Document("$set", new Document("status", STATUS_APPROVED)
                .append("approvedAt", approvedAt.toString())
                .append("expiresAt", expiresAt))
        );

        AuthUser upgradedUser = authService.activatePremium(
            readString(payment, "userId"),
            plan.code(),
            plan.label(),
            expiresAt,
            config.freeDailyLimit()
        );

        Document updatedPayment = requirePayment(normalizedPaymentId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", upgradedUser.toMap());
        payload.put("payment", toPaymentSummary(updatedPayment));
        return payload;
    }

    synchronized Map<String, Object> rejectPendingPayment(AppConfig config, String reviewKey, String paymentId, String note) {
        requireReviewKey(config, reviewKey);
        String normalizedPaymentId = normalizePaymentId(paymentId);
        Document payment = requirePayment(normalizedPaymentId);
        String status = readString(payment, "status");
        if (STATUS_REJECTED.equals(status)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("alreadyRejected", true);
            payload.put("payment", toPaymentSummary(payment));
            return payload;
        }
        if (!STATUS_PENDING_REVIEW.equals(status)) {
            throw new AppException(409, "Only pending payments can be rejected.");
        }

        payments.updateOne(
            eq("_id", normalizedPaymentId),
            new Document("$set", new Document("status", STATUS_REJECTED)
                .append("rejectedAt", Instant.now().toString())
                .append("reviewNote", normalizeReviewNote(note)))
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment", toPaymentSummary(requirePayment(normalizedPaymentId)));
        return payload;
    }

    private Document findPendingPaymentByUser(String userId) {
        return payments.find(new Document("userId", userId).append("status", STATUS_PENDING_REVIEW))
            .sort(new Document("createdAt", -1))
            .first();
    }

    private Document requirePayment(String paymentId) {
        Document payment = payments.find(eq("_id", paymentId)).first();
        if (payment == null) {
            throw new AppException(404, "Payment request not found.");
        }
        return payment;
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

    private Map<String, Object> toPaymentSummary(Document payment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", readString(payment, "_id"));
        payload.put("userId", readString(payment, "userId"));
        payload.put("userName", readString(payment, "userName"));
        payload.put("userEmail", readString(payment, "userEmail"));
        payload.put("reference", readString(payment, "transactionReference"));
        payload.put("planCode", readString(payment, "planCode"));
        payload.put("planLabel", readString(payment, "planLabel"));
        payload.put("amountInr", readInt(payment, "amountInr"));
        payload.put("status", readString(payment, "status"));
        payload.put("upiId", readString(payment, "upiId"));
        payload.put("createdAt", readString(payment, "createdAt"));
        payload.put("approvedAt", readString(payment, "approvedAt"));
        payload.put("expiresAt", readString(payment, "expiresAt"));
        payload.put("reviewNote", readString(payment, "reviewNote"));
        return payload;
    }

    private void requireReviewKey(AppConfig config, String reviewKey) {
        if (config.premiumReviewKey().isBlank()) {
            throw new AppException(503, "Manual premium review is not configured yet.");
        }
        if (!config.premiumReviewKey().equals(reviewKey == null ? "" : reviewKey.trim())) {
            throw new AppException(401, "Invalid premium review key.");
        }
    }

    private String normalizePaymentId(String paymentId) {
        String value = paymentId == null ? "" : paymentId.trim();
        if (value.isBlank()) {
            throw new AppException(400, "Payment id is required.");
        }
        return value;
    }

    private String normalizeReviewNote(String note) {
        String value = note == null ? "" : note.trim().replaceAll("\\s+", " ");
        return value.length() > 160 ? value.substring(0, 160) : value;
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

    private String readString(Document payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int readInt(Document payload, String key) {
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
}
