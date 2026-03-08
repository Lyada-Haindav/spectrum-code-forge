package com.spectrumforge;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.bson.Document;

import static com.mongodb.client.model.Filters.eq;

final class AuthService {
    private static final String SESSION_COOKIE = "solver_session";
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int TOKEN_BYTES = 24;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final long RESET_TOKEN_MINUTES = 30;
    private static final long SESSION_TTL_DAYS = 30;

    private final MongoCollection<Document> users;
    private final MongoCollection<Document> sessions;
    private final SecureRandom secureRandom = new SecureRandom();

    AuthService(MongoStore store) {
        this.users = store.users();
        this.sessions = store.sessions();
    }

    synchronized RegistrationResult register(String name, String email, String password, int freeDailyLimit) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedName = normalizeName(name, normalizedEmail);
        validatePassword(password);

        if (findUserByEmail(normalizedEmail) != null) {
            throw new AppException(409, "An account already exists for this email.");
        }

        PasswordHash hash = createPasswordHash(password);
        String verificationToken = randomToken();
        StoredUser user = new StoredUser(
            UUID.randomUUID().toString(),
            normalizedName,
            normalizedEmail,
            hash.hash(),
            hash.salt(),
            Instant.now().toString(),
            todayString(),
            0,
            false,
            "",
            "",
            "",
            "",
            false,
            verificationToken,
            Instant.now().toString(),
            "",
            "",
            ""
        );

        try {
            users.insertOne(toDocument(user));
        } catch (MongoWriteException error) {
            if (error.getError() != null && error.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new AppException(409, "An account already exists for this email.");
            }
            throw new AppException(500, "Unable to persist authentication data.");
        }

        return new RegistrationResult(toAuthUser(user, freeDailyLimit), verificationToken);
    }

    synchronized AuthUser login(String email, String password, int freeDailyLimit) {
        String normalizedEmail = normalizeEmail(email);
        StoredUser user = findUserByEmail(normalizedEmail);
        if (user == null || !matches(password, user.passwordHash(), user.passwordSalt())) {
            throw new AppException(401, "Invalid email or password.");
        }
        return toAuthUser(normalizeUserState(user), freeDailyLimit);
    }

    synchronized Optional<AuthUser> currentUser(HttpExchange exchange, int freeDailyLimit) {
        String token = readCookie(exchange.getRequestHeaders(), SESSION_COOKIE);
        if (token.isBlank()) {
            return Optional.empty();
        }

        Document session = sessions.find(eq("_id", token)).first();
        if (session == null) {
            return Optional.empty();
        }

        Object expiresAtValue = session.get("expiresAt");
        if (expiresAtValue instanceof java.util.Date expiresAt && !expiresAt.toInstant().isAfter(Instant.now())) {
            sessions.deleteOne(eq("_id", token));
            return Optional.empty();
        }

        String userId = readString(session, "userId");
        if (userId.isBlank()) {
            sessions.deleteOne(eq("_id", token));
            return Optional.empty();
        }

        StoredUser user = requireStoredUser(userId);
        return Optional.of(toAuthUser(normalizeUserState(user), freeDailyLimit));
    }

    void startSession(HttpExchange exchange, AuthUser user) {
        String token = randomToken();
        Document session = new Document("_id", token)
            .append("userId", user.id())
            .append("createdAt", Instant.now().toString())
            .append("expiresAt", java.util.Date.from(Instant.now().plus(SESSION_TTL_DAYS, ChronoUnit.DAYS)));
        sessions.insertOne(session);
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax"
        );
    }

    void endSession(HttpExchange exchange) {
        String token = readCookie(exchange.getRequestHeaders(), SESSION_COOKIE);
        if (!token.isBlank()) {
            sessions.deleteOne(eq("_id", token));
        }
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
        );
    }

    Map<String, Object> sessionPayload(Optional<AuthUser> user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        boolean authenticated = user.isPresent();
        payload.put("authenticated", authenticated);
        payload.put("user", authenticated ? user.orElseThrow().toMap() : null);
        return payload;
    }

    synchronized void ensureWithinDailyLimit(String userId, int freeDailyLimit) {
        StoredUser user = normalizeUserState(requireStoredUser(userId));
        if (!user.premium() && user.dailyUsageCount() >= freeDailyLimit) {
            throw new AppException(403, dailyLimitExceededMessage(freeDailyLimit));
        }
    }

    synchronized AuthUser recordSuccessfulGenerate(String userId, int freeDailyLimit) {
        StoredUser user = normalizeUserState(requireStoredUser(userId));
        if (user.premium()) {
            return toAuthUser(user, freeDailyLimit);
        }
        if (user.dailyUsageCount() >= freeDailyLimit) {
            throw new AppException(403, dailyLimitExceededMessage(freeDailyLimit));
        }

        StoredUser updated = new StoredUser(
            user.id(),
            user.name(),
            user.email(),
            user.passwordHash(),
            user.passwordSalt(),
            user.createdAt(),
            todayString(),
            user.dailyUsageCount() + 1,
            false,
            "",
            "",
            "",
            user.premiumActivatedAt(),
            user.emailVerified(),
            user.verificationToken(),
            user.verificationSentAt(),
            user.emailVerifiedAt(),
            user.resetToken(),
            user.resetSentAt()
        );
        saveUser(updated);
        return toAuthUser(updated, freeDailyLimit);
    }

    synchronized AuthUser activatePremium(
        String userId,
        String premiumPlanCode,
        String premiumPlanLabel,
        String premiumExpiresAt,
        int freeDailyLimit
    ) {
        StoredUser user = normalizeUserState(requireStoredUser(userId));
        if (user.premium()) {
            return toAuthUser(user, freeDailyLimit);
        }

        StoredUser updated = new StoredUser(
            user.id(),
            user.name(),
            user.email(),
            user.passwordHash(),
            user.passwordSalt(),
            user.createdAt(),
            user.usageDate(),
            user.dailyUsageCount(),
            true,
            premiumPlanCode,
            premiumPlanLabel,
            premiumExpiresAt,
            Instant.now().toString(),
            user.emailVerified(),
            user.verificationToken(),
            user.verificationSentAt(),
            user.emailVerifiedAt(),
            user.resetToken(),
            user.resetSentAt()
        );
        saveUser(updated);
        return toAuthUser(updated, freeDailyLimit);
    }

    synchronized AuthUser verifyEmail(String token, int freeDailyLimit) {
        String normalizedToken = normalizeVerificationToken(token);
        Document userDocument = users.find(new Document("verificationToken", normalizedToken).append("emailVerified", false)).first();
        if (userDocument == null) {
            throw new AppException(400, "This verification link is invalid or expired.");
        }

        StoredUser user = readUser(userDocument);
        StoredUser updated = new StoredUser(
            user.id(),
            user.name(),
            user.email(),
            user.passwordHash(),
            user.passwordSalt(),
            user.createdAt(),
            user.usageDate(),
            user.dailyUsageCount(),
            user.premium(),
            user.premiumPlanCode(),
            user.premiumPlanLabel(),
            user.premiumExpiresAt(),
            user.premiumActivatedAt(),
            true,
            "",
            user.verificationSentAt(),
            Instant.now().toString(),
            user.resetToken(),
            user.resetSentAt()
        );
        saveUser(updated);
        return toAuthUser(updated, freeDailyLimit);
    }

    synchronized VerificationDispatch resendVerification(String userId, int freeDailyLimit) {
        StoredUser user = normalizeUserState(requireStoredUser(userId));
        if (user.emailVerified()) {
            throw new AppException(409, "Email is already verified.");
        }

        String verificationToken = randomToken();
        StoredUser updated = new StoredUser(
            user.id(),
            user.name(),
            user.email(),
            user.passwordHash(),
            user.passwordSalt(),
            user.createdAt(),
            user.usageDate(),
            user.dailyUsageCount(),
            user.premium(),
            user.premiumPlanCode(),
            user.premiumPlanLabel(),
            user.premiumExpiresAt(),
            user.premiumActivatedAt(),
            false,
            verificationToken,
            Instant.now().toString(),
            user.emailVerifiedAt(),
            user.resetToken(),
            user.resetSentAt()
        );
        saveUser(updated);
        return new VerificationDispatch(toAuthUser(updated, freeDailyLimit), verificationToken);
    }

    synchronized Optional<PasswordResetDispatch> requestPasswordReset(String email, int freeDailyLimit) {
        String normalizedEmail = normalizeEmail(email);
        StoredUser user = findUserByEmail(normalizedEmail);
        if (user == null) {
            return Optional.empty();
        }

        user = normalizeUserState(user);
        String resetToken = randomToken();
        StoredUser updated = new StoredUser(
            user.id(),
            user.name(),
            user.email(),
            user.passwordHash(),
            user.passwordSalt(),
            user.createdAt(),
            user.usageDate(),
            user.dailyUsageCount(),
            user.premium(),
            user.premiumPlanCode(),
            user.premiumPlanLabel(),
            user.premiumExpiresAt(),
            user.premiumActivatedAt(),
            user.emailVerified(),
            user.verificationToken(),
            user.verificationSentAt(),
            user.emailVerifiedAt(),
            resetToken,
            Instant.now().toString()
        );
        saveUser(updated);
        return Optional.of(new PasswordResetDispatch(toAuthUser(updated, freeDailyLimit), resetToken));
    }

    synchronized AuthUser resetPassword(String token, String password, int freeDailyLimit) {
        String normalizedToken = normalizeResetToken(token);
        validatePassword(password);

        Document userDocument = users.find(eq("resetToken", normalizedToken)).first();
        if (userDocument == null) {
            throw new AppException(400, "This reset link is invalid or expired.");
        }

        StoredUser user = normalizeUserState(readUser(userDocument));
        if (resetTokenExpired(user)) {
            throw new AppException(400, "This reset link is invalid or expired.");
        }

        PasswordHash hash = createPasswordHash(password);
        StoredUser updated = new StoredUser(
            user.id(),
            user.name(),
            user.email(),
            hash.hash(),
            hash.salt(),
            user.createdAt(),
            user.usageDate(),
            user.dailyUsageCount(),
            user.premium(),
            user.premiumPlanCode(),
            user.premiumPlanLabel(),
            user.premiumExpiresAt(),
            user.premiumActivatedAt(),
            user.emailVerified(),
            user.verificationToken(),
            user.verificationSentAt(),
            user.emailVerifiedAt(),
            "",
            ""
        );
        saveUser(updated);
        clearSessionsForUser(updated.id());
        return toAuthUser(updated, freeDailyLimit);
    }

    private StoredUser findUserByEmail(String email) {
        Document document = users.find(eq("email", email)).first();
        return document == null ? null : readUser(document);
    }

    private StoredUser requireStoredUser(String userId) {
        Document document = users.find(eq("_id", userId)).first();
        if (document == null) {
            throw new AppException(404, "Account not found.");
        }
        return readUser(document);
    }

    private void saveUser(StoredUser user) {
        users.replaceOne(eq("_id", user.id()), toDocument(user), new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    private Document toDocument(StoredUser user) {
        return new Document("_id", user.id())
            .append("name", user.name())
            .append("email", user.email())
            .append("passwordHash", user.passwordHash())
            .append("passwordSalt", user.passwordSalt())
            .append("createdAt", user.createdAt())
            .append("usageDate", user.usageDate())
            .append("dailyUsageCount", user.dailyUsageCount())
            .append("premium", user.premium())
            .append("premiumPlanCode", user.premiumPlanCode())
            .append("premiumPlanLabel", user.premiumPlanLabel())
            .append("premiumExpiresAt", user.premiumExpiresAt())
            .append("premiumActivatedAt", user.premiumActivatedAt())
            .append("emailVerified", user.emailVerified())
            .append("verificationToken", user.verificationToken())
            .append("verificationSentAt", user.verificationSentAt())
            .append("emailVerifiedAt", user.emailVerifiedAt())
            .append("resetToken", user.resetToken())
            .append("resetSentAt", user.resetSentAt());
    }

    private StoredUser readUser(Document document) {
        return new StoredUser(
            readString(document, "_id"),
            readString(document, "name"),
            normalizeEmail(readString(document, "email")),
            readString(document, "passwordHash"),
            readString(document, "passwordSalt"),
            readString(document, "createdAt"),
            readString(document, "usageDate"),
            readInt(document, "dailyUsageCount"),
            readBoolean(document, "premium"),
            readString(document, "premiumPlanCode"),
            readString(document, "premiumPlanLabel"),
            readString(document, "premiumExpiresAt"),
            readString(document, "premiumActivatedAt"),
            readBoolean(document, "emailVerified", true),
            readString(document, "verificationToken"),
            readString(document, "verificationSentAt"),
            readString(document, "emailVerifiedAt"),
            readString(document, "resetToken"),
            readString(document, "resetSentAt")
        );
    }

    private StoredUser normalizeUserState(StoredUser user) {
        StoredUser normalized = user;
        boolean changed = false;

        if (subscriptionExpired(normalized)) {
            normalized = new StoredUser(
                normalized.id(),
                normalized.name(),
                normalized.email(),
                normalized.passwordHash(),
                normalized.passwordSalt(),
                normalized.createdAt(),
                normalized.usageDate(),
                normalized.dailyUsageCount(),
                false,
                "",
                "",
                "",
                "",
                normalized.emailVerified(),
                normalized.verificationToken(),
                normalized.verificationSentAt(),
                normalized.emailVerifiedAt(),
                normalized.resetToken(),
                normalized.resetSentAt()
            );
            changed = true;
        }

        String today = todayString();
        if (!today.equals(normalized.usageDate())) {
            normalized = new StoredUser(
                normalized.id(),
                normalized.name(),
                normalized.email(),
                normalized.passwordHash(),
                normalized.passwordSalt(),
                normalized.createdAt(),
                today,
                0,
                normalized.premium(),
                normalized.premiumPlanCode(),
                normalized.premiumPlanLabel(),
                normalized.premiumExpiresAt(),
                normalized.premiumActivatedAt(),
                normalized.emailVerified(),
                normalized.verificationToken(),
                normalized.verificationSentAt(),
                normalized.emailVerifiedAt(),
                normalized.resetToken(),
                normalized.resetSentAt()
            );
            changed = true;
        }

        if (changed) {
            saveUser(normalized);
        }
        return normalized;
    }

    private AuthUser toAuthUser(StoredUser user, int freeDailyLimit) {
        int used = user.premium() ? 0 : Math.max(0, user.dailyUsageCount());
        int remaining = user.premium() ? -1 : Math.max(0, freeDailyLimit - used);
        return new AuthUser(
            user.id(),
            user.name(),
            user.email(),
            user.emailVerified(),
            user.premium(),
            user.premiumPlanCode(),
            user.premiumPlanLabel(),
            user.premiumExpiresAt(),
            freeDailyLimit,
            used,
            remaining,
            user.emailVerified() && (user.premium() || remaining > 0)
        );
    }

    private String normalizeEmail(String email) {
        String value = email == null ? "" : email.trim().toLowerCase();
        if (value.isBlank() || !value.contains("@") || value.startsWith("@") || value.endsWith("@")) {
            throw new AppException(400, "Enter a valid email address.");
        }
        return value;
    }

    private String normalizeName(String name, String email) {
        String value = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (value.isBlank()) {
            value = email.substring(0, email.indexOf('@'));
        }
        if (value.length() < 2 || value.length() > 36) {
            throw new AppException(400, "Name must be between 2 and 36 characters.");
        }
        return value;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new AppException(400, "Password must be at least 8 characters.");
        }
    }

    private PasswordHash createPasswordHash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt);
        return new PasswordHash(
            Base64.getEncoder().encodeToString(hash),
            Base64.getEncoder().encodeToString(salt)
        );
    }

    private boolean matches(String password, String storedHash, String storedSalt) {
        if (password == null || storedHash.isBlank() || storedSalt.isBlank()) {
            return false;
        }

        byte[] computed = pbkdf2(password.toCharArray(), Base64.getDecoder().decode(storedSalt));
        byte[] existing = Base64.getDecoder().decode(storedHash);
        return MessageDigest.isEqual(existing, computed);
    }

    private byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, HASH_ITERATIONS, HASH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new AppException(500, "Unable to protect account credentials.");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String readCookie(Headers headers, String name) {
        List<String> cookieHeaders = headers.getOrDefault("Cookie", List.of());
        for (String header : cookieHeaders) {
            for (String cookie : header.split(";")) {
                String entry = cookie.trim();
                int separator = entry.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = entry.substring(0, separator).trim();
                if (!name.equals(key)) {
                    continue;
                }
                return entry.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private String readString(Document payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean readBoolean(Document payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private boolean readBoolean(Document payload, String key, boolean fallback) {
        if (!payload.containsKey(key)) {
            return fallback;
        }
        return readBoolean(payload, key);
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

    private boolean subscriptionExpired(StoredUser user) {
        if (!user.premium()) {
            return false;
        }
        if (user.premiumExpiresAt().isBlank()) {
            return false;
        }
        try {
            return !Instant.parse(user.premiumExpiresAt()).isAfter(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean resetTokenExpired(StoredUser user) {
        if (user.resetSentAt().isBlank()) {
            return true;
        }
        try {
            Instant sentAt = Instant.parse(user.resetSentAt());
            return sentAt.plus(RESET_TOKEN_MINUTES, ChronoUnit.MINUTES).isBefore(Instant.now());
        } catch (Exception ignored) {
            return true;
        }
    }

    private String normalizeVerificationToken(String token) {
        String value = token == null ? "" : token.trim();
        if (value.isBlank()) {
            throw new AppException(400, "Verification token is required.");
        }
        return value;
    }

    private String normalizeResetToken(String token) {
        String value = token == null ? "" : token.trim();
        if (value.isBlank()) {
            throw new AppException(400, "Reset token is required.");
        }
        return value;
    }

    private void clearSessionsForUser(String userId) {
        sessions.deleteMany(eq("userId", userId));
    }

    private String todayString() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    private String dailyLimitExceededMessage(int freeDailyLimit) {
        return "Free plan limit reached for today. You can generate up to " + freeDailyLimit + " solutions per day. Upgrade to premium for unlimited solves.";
    }

    private record PasswordHash(String hash, String salt) {
    }

    record RegistrationResult(AuthUser user, String verificationToken) {
    }

    record VerificationDispatch(AuthUser user, String verificationToken) {
    }

    record PasswordResetDispatch(AuthUser user, String resetToken) {
    }

    private record StoredUser(
        String id,
        String name,
        String email,
        String passwordHash,
        String passwordSalt,
        String createdAt,
        String usageDate,
        int dailyUsageCount,
        boolean premium,
        String premiumPlanCode,
        String premiumPlanLabel,
        String premiumExpiresAt,
        String premiumActivatedAt,
        boolean emailVerified,
        String verificationToken,
        String verificationSentAt,
        String emailVerifiedAt,
        String resetToken,
        String resetSentAt
    ) {
    }
}
