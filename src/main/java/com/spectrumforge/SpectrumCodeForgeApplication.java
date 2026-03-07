package com.spectrumforge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

public final class SpectrumCodeForgeApplication {
    private static final Map<String, String> CONTENT_TYPES = Map.of(
        ".css", "text/css; charset=utf-8",
        ".html", "text/html; charset=utf-8",
        ".js", "application/javascript; charset=utf-8",
        ".json", "application/json; charset=utf-8",
        ".svg", "image/svg+xml"
    );

    private final AppConfig config;
    private final GeminiClient client;
    private final AuthService authService;
    private final HistoryService historyService;
    private final BillingService billingService;
    private final EmailService emailService;

    private SpectrumCodeForgeApplication(AppConfig config) {
        this.config = config;
        this.client = new GeminiClient();
        java.nio.file.Path dataDir = java.nio.file.Path.of(config.dataDir());
        this.authService = new AuthService(dataDir);
        this.historyService = new HistoryService(dataDir);
        this.billingService = new BillingService(dataDir);
        this.emailService = new EmailService();
    }

    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.load();
        SpectrumCodeForgeApplication app = new SpectrumCodeForgeApplication(config);
        app.start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.createContext("/api/config", this::handleConfig);
        server.createContext("/api/auth/session", this::handleSession);
        server.createContext("/api/auth/signup", this::handleSignup);
        server.createContext("/api/auth/login", this::handleLogin);
        server.createContext("/api/auth/verify", this::handleVerifyEmail);
        server.createContext("/api/auth/resend-verification", this::handleResendVerification);
        server.createContext("/api/auth/logout", this::handleLogout);
        server.createContext("/api/billing/checkout", this::handleBillingCheckout);
        server.createContext("/api/billing/upgrade", this::handleBillingUpgrade);
        server.createContext("/api/generate", this::handleGenerate);
        server.createContext("/api/history/item", this::handleHistoryItem);
        server.createContext("/api/history/pin", this::handleHistoryPin);
        server.createContext("/api/history/delete", this::handleHistoryDelete);
        server.createContext("/api/history", this::handleHistory);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Spectrum Code Forge running at http://localhost:" + config.port());
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "GET");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ready", config.configured());
            payload.put("freeDailyLimit", config.freeDailyLimit());
            payload.put("premiumPriceInr", config.premiumPriceInr());
            payload.put("premiumCheckoutEnabled", config.premiumCheckoutEnabled());
            sendJson(exchange, 200, payload);
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleGenerate(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "POST");
            AuthUser user = requireVerifiedUser(exchange);
            Map<String, Object> payload = readJsonBody(exchange);
            GenerateRequest request = GenerateRequest.from(payload);
            request.validate();
            authService.ensureWithinDailyLimit(user.id(), config.freeDailyLimit());

            Map<String, Object> result = client.generate(request, config);
            HistoryService.HistorySnapshot snapshot = historyService.save(user, request, result);
            AuthUser refreshedUser = authService.recordSuccessfulGenerate(user.id(), config.freeDailyLimit());
            result.put("historyId", snapshot.id());
            result.put("savedAt", snapshot.createdAt());
            result.put("account", refreshedUser.toMap());
            sendJson(exchange, 200, result);
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "GET");
            sendJson(exchange, 200, authService.sessionPayload(authService.currentUser(exchange, config.freeDailyLimit())));
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleSignup(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "POST");
            Map<String, Object> payload = readJsonBody(exchange);
            AuthService.RegistrationResult registration = authService.register(
                readString(payload, "name"),
                readString(payload, "email"),
                readString(payload, "password"),
                config.freeDailyLimit()
            );
            AuthUser user = registration.user();
            authService.startSession(exchange, user);

            Map<String, Object> response = authService.sessionPayload(Optional.of(user));
            try {
                emailService.sendVerificationEmail(user, registration.verificationToken(), config);
                response.put("verificationEmailSent", true);
            } catch (AppException error) {
                response.put("verificationEmailSent", false);
                response.put("verificationNotice", error.getMessage());
            }

            sendJson(exchange, 201, response);
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "POST");
            Map<String, Object> payload = readJsonBody(exchange);
            AuthUser user = authService.login(
                readString(payload, "email"),
                readString(payload, "password"),
                config.freeDailyLimit()
            );
            authService.startSession(exchange, user);
            sendJson(exchange, 200, authService.sessionPayload(Optional.of(user)));
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleVerifyEmail(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "GET");
            String token = queryParameters(exchange).getOrDefault("token", "").trim();
            AuthUser user = authService.verifyEmail(token, config.freeDailyLimit());
            authService.startSession(exchange, user);

            Map<String, Object> payload = authService.sessionPayload(Optional.of(user));
            payload.put("verified", true);
            sendJson(exchange, 200, payload);
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleResendVerification(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "POST");
            AuthUser user = requireUser(exchange);
            AuthService.VerificationDispatch dispatch = authService.resendVerification(user.id(), config.freeDailyLimit());
            emailService.sendVerificationEmail(dispatch.user(), dispatch.verificationToken(), config);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user", dispatch.user().toMap());
            payload.put("verificationEmailSent", true);
            sendJson(exchange, 200, payload);
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleBillingCheckout(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "GET");
            AuthUser user = requireVerifiedUser(exchange);
            sendJson(exchange, 200, billingService.checkoutFor(user, config));
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleBillingUpgrade(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "POST");
            AuthUser user = requireVerifiedUser(exchange);
            Map<String, Object> payload = readJsonBody(exchange);
            String transactionReference = readString(payload, "transactionReference");
            Map<String, Object> response = billingService.confirmPremium(user, config, transactionReference, authService);

            boolean alreadyPremium = readBoolean(response, "alreadyPremium");
            if (!alreadyPremium) {
                AuthUser refreshedUser = authService.currentUser(exchange, config.freeDailyLimit()).orElse(user);
                String referenceForEmail = transactionReference;
                Object paymentValue = response.get("payment");
                if (paymentValue instanceof Map<?, ?> paymentMap) {
                    referenceForEmail = readString(MiniJson.asObject(paymentMap, "Invalid payment response."), "reference");
                }
                try {
                    boolean premiumEmailSent = emailService.sendPremiumSuccessEmail(
                        refreshedUser,
                        referenceForEmail,
                        config.premiumPriceInr(),
                        config
                    );
                    response.put("premiumEmailSent", premiumEmailSent);
                    if (!premiumEmailSent) {
                        response.put("premiumEmailNotice", "Premium is active now, but confirmation email delivery is unavailable.");
                    }
                } catch (AppException error) {
                    response.put("premiumEmailSent", false);
                    response.put("premiumEmailNotice", error.getMessage());
                }
            }

            sendJson(exchange, 200, response);
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "POST");
            authService.endSession(exchange);
            sendJson(exchange, 200, authService.sessionPayload(Optional.empty()));
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleHistory(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "GET");
            AuthUser user = requireUser(exchange);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", historyService.listForUser(user.id()));
            sendJson(exchange, 200, payload);
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleHistoryItem(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "GET");
            AuthUser user = requireUser(exchange);
            String id = queryParameters(exchange).getOrDefault("id", "").trim();
            if (id.isBlank()) {
                throw new AppException(400, "Saved chat id is required.");
            }

            sendJson(exchange, 200, historyService.getForUser(user.id(), id));
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleHistoryPin(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "POST");
            AuthUser user = requireUser(exchange);
            Map<String, Object> payload = readJsonBody(exchange);
            String id = readString(payload, "id");
            if (id.isBlank()) {
                throw new AppException(400, "Saved chat id is required.");
            }

            boolean pinned = readBoolean(payload, "pinned");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("item", historyService.pinForUser(user.id(), id, pinned));
            sendJson(exchange, 200, response);
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleHistoryDelete(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "POST");
            AuthUser user = requireUser(exchange);
            Map<String, Object> payload = readJsonBody(exchange);
            String id = readString(payload, "id");
            if (id.isBlank()) {
                throw new AppException(400, "Saved chat id is required.");
            }

            historyService.deleteForUser(user.id(), id);
            sendJson(exchange, 200, Map.of("deleted", true));
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        try {
            requireMethod(exchange, "GET");
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isBlank() || path.equals("/")) {
                path = "/index.html";
            }
            if (path.contains("..")) {
                throw new AppException(403, "Access denied.");
            }

            String resourcePath = "/static" + path;
            try (InputStream inputStream = SpectrumCodeForgeApplication.class.getResourceAsStream(resourcePath)) {
                if (inputStream == null) {
                    throw new AppException(404, "File not found.");
                }

                byte[] bytes = inputStream.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", contentType(path));
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(bytes);
                }
            }
        } catch (AppException error) {
            sendError(exchange, error);
        }
    }

    private void requireMethod(HttpExchange exchange, String method) {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new AppException(405, "Method not allowed.");
        }
    }

    private AuthUser requireUser(HttpExchange exchange) {
        return authService.currentUser(exchange, config.freeDailyLimit())
            .orElseThrow(() -> new AppException(401, "Sign in to continue."));
    }

    private AuthUser requireVerifiedUser(HttpExchange exchange) {
        AuthUser user = requireUser(exchange);
        if (!user.emailVerified()) {
            throw new AppException(403, "Verify your email first. Check your inbox for the confirmation link.");
        }
        return user;
    }

    private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return MiniJson.asObject(MiniJson.parse(body), "Request body must be a JSON object.");
    }

    private Map<String, String> queryParameters(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return Map.of();
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }

            int separatorIndex = pair.indexOf('=');
            String rawKey = separatorIndex >= 0 ? pair.substring(0, separatorIndex) : pair;
            String rawValue = separatorIndex >= 0 ? pair.substring(separatorIndex + 1) : "";
            parameters.put(decode(rawKey), decode(rawValue));
        }
        return parameters;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String readString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean readBoolean(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        byte[] bytes = MiniJson.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, AppException error) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error.getMessage());
        sendJson(exchange, error.statusCode(), payload);
    }

    private String contentType(String path) {
        int extensionIndex = path.lastIndexOf('.');
        if (extensionIndex == -1) {
            return "application/octet-stream";
        }
        return CONTENT_TYPES.getOrDefault(path.substring(extensionIndex).toLowerCase(), "application/octet-stream");
    }
}
