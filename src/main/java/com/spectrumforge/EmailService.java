package com.spectrumforge;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EmailService {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .build();

    void sendVerificationEmail(AuthUser user, String verificationToken, AppConfig config) {
        if (!config.emailDeliveryEnabled()) {
            throw new AppException(503, "Verification email delivery is unavailable right now.");
        }

        String verifyUrl = normalizedBaseUrl(config.appBaseUrl())
            + "/verify.html?token="
            + URLEncoder.encode(verificationToken, StandardCharsets.UTF_8);

        String subject = "Confirm your email";
        String text = "Confirm your email for Spectrum Code Forge: " + verifyUrl;
        String html = """
            <div style="font-family:Arial,sans-serif;line-height:1.6;color:#14213d">
              <p style="font-size:14px;letter-spacing:0.18em;text-transform:uppercase;color:#2563eb;margin-bottom:12px">Spectrum Code Forge</p>
              <h1 style="margin:0 0 16px;font-size:28px">Confirm your email</h1>
              <p style="margin:0 0 16px">Hi %s,</p>
              <p style="margin:0 0 20px">Finish email confirmation to unlock solver access, saved history, and premium upgrade.</p>
              <p style="margin:0 0 24px">
                <a href="%s" style="display:inline-block;background:#2563eb;color:#ffffff;text-decoration:none;padding:14px 22px;border-radius:14px;font-weight:700">
                  Confirm email
                </a>
              </p>
              <p style="margin:0 0 10px">If the button does not open, paste this link into your browser:</p>
              <p style="margin:0;color:#475569;word-break:break-all">%s</p>
            </div>
            """.formatted(escapeHtml(user.name()), escapeHtml(verifyUrl), escapeHtml(verifyUrl));

        sendEmail(user.name(), user.email(), subject, text, html, config, "Unable to send the verification email right now.");
    }

    void sendPasswordResetEmail(AuthUser user, String resetToken, AppConfig config) {
        if (!config.emailDeliveryEnabled()) {
            throw new AppException(503, "Password reset email delivery is unavailable right now.");
        }

        String resetUrl = normalizedBaseUrl(config.appBaseUrl())
            + "/reset.html?token="
            + URLEncoder.encode(resetToken, StandardCharsets.UTF_8);

        String subject = "Reset your password";
        String text = "Reset your Spectrum Code Forge password: " + resetUrl;
        String html = """
            <div style="font-family:Arial,sans-serif;line-height:1.6;color:#14213d">
              <p style="font-size:14px;letter-spacing:0.18em;text-transform:uppercase;color:#2563eb;margin-bottom:12px">Spectrum Code Forge</p>
              <h1 style="margin:0 0 16px;font-size:28px">Reset your password</h1>
              <p style="margin:0 0 16px">Hi %s,</p>
              <p style="margin:0 0 20px">Open the secure reset page below and choose a new password for your account.</p>
              <p style="margin:0 0 24px">
                <a href="%s" style="display:inline-block;background:#2563eb;color:#ffffff;text-decoration:none;padding:14px 22px;border-radius:14px;font-weight:700">
                  Reset password
                </a>
              </p>
              <p style="margin:0 0 10px">If the button does not open, paste this link into your browser:</p>
              <p style="margin:0;color:#475569;word-break:break-all">%s</p>
            </div>
            """.formatted(escapeHtml(user.name()), escapeHtml(resetUrl), escapeHtml(resetUrl));

        sendEmail(user.name(), user.email(), subject, text, html, config, "Unable to send the password reset email right now.");
    }

    boolean sendPremiumSuccessEmail(
        AuthUser user,
        String transactionReference,
        String planLabel,
        int amountInr,
        String expiresAt,
        AppConfig config
    ) {
        if (!config.emailDeliveryEnabled()) {
            return false;
        }

        String formattedExpiry = formatDate(expiresAt);
        String subject = "Premium activated successfully";
        String text = "Premium is active on your account. Plan: %s. Amount: INR %d. Reference: %s. Active until: %s."
            .formatted(planLabel, amountInr, transactionReference, formattedExpiry);
        String html = """
            <div style="font-family:Arial,sans-serif;line-height:1.6;color:#14213d">
              <p style="font-size:14px;letter-spacing:0.18em;text-transform:uppercase;color:#f59e0b;margin-bottom:12px">Premium active</p>
              <h1 style="margin:0 0 16px;font-size:28px">%s unlocked</h1>
              <p style="margin:0 0 16px">Hi %s,</p>
              <p style="margin:0 0 18px">Your paid plan is active now. This account can use the solver without the daily free limit until the end of the subscription.</p>
              <div style="background:#f8fafc;border:1px solid #dbeafe;border-radius:16px;padding:16px 18px;margin:0 0 20px">
                <p style="margin:0 0 8px"><strong>Plan:</strong> %s</p>
                <p style="margin:0 0 8px"><strong>Amount:</strong> INR %d</p>
                <p style="margin:0 0 8px"><strong>Reference:</strong> %s</p>
                <p style="margin:0"><strong>Active until:</strong> %s</p>
              </div>
              <p style="margin:0">Open the workspace and continue solving.</p>
            </div>
            """.formatted(
                escapeHtml(planLabel),
                escapeHtml(user.name()),
                escapeHtml(planLabel),
                amountInr,
                escapeHtml(transactionReference),
                escapeHtml(formattedExpiry)
            );

        sendEmail(user.name(), user.email(), subject, text, html, config, "Unable to send the premium confirmation email right now.");
        return true;
    }

    private void sendEmail(
        String recipientName,
        String recipientEmail,
        String subject,
        String textContent,
        String htmlContent,
        AppConfig config,
        String failureMessage
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sender", Map.of(
            "name", config.brevoSenderName(),
            "email", config.brevoSenderEmail()
        ));
        payload.put("to", List.of(Map.of(
            "name", recipientName,
            "email", recipientEmail
        )));
        payload.put("subject", subject);
        payload.put("textContent", textContent);
        payload.put("htmlContent", htmlContent);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
            .timeout(REQUEST_TIMEOUT)
            .header("accept", "application/json")
            .header("api-key", config.brevoApiKey())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(payload), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AppException(502, failureMessage);
            }
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AppException(502, failureMessage);
        }
    }

    private String normalizedBaseUrl(String rawBaseUrl) {
        String value = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String escapeHtml(String value) {
        return String.valueOf(value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String formatDate(String rawInstant) {
        if (rawInstant == null || rawInstant.isBlank()) {
            return "your plan end date";
        }
        try {
            return Instant.parse(rawInstant)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a"));
        } catch (Exception ignored) {
            return rawInstant;
        }
    }
}
