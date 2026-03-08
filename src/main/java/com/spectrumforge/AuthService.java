package com.spectrumforge;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class AuthService {
    private static final String SESSION_COOKIE = "solver_session";
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int TOKEN_BYTES = 24;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final long RESET_TOKEN_MINUTES = 30;

    private final Path usersFile;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, StoredUser> usersById = new LinkedHashMap<>();
    private final Map<String, String> userIdByEmail = new LinkedHashMap<>();
    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();

    AuthService(Path dataDir) {
        this.usersFile = dataDir.resolve("users.json");
        try {
            Files.createDirectories(dataDir);
        } catch (IOException error) {
            throw new AppException(500, "Unable to prepare authentication storage.");
        }
        loadUsers();
    }

    synchronized RegistrationResult register(String name, String email, String password, int freeDailyLimit) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedName = normalizeName(name, normalizedEmail);
        validatePassword(password);

        if (userIdByEmail.containsKey(normalizedEmail)) {
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
        usersById.put(user.id(), user);
        userIdByEmail.put(user.email(), user.id());
        saveUsers();
        return new RegistrationResult(toAuthUser(user, freeDailyLimit), verificationToken);
    }

    synchronized AuthUser login(String email, String password, int freeDailyLimit) {
        String normalizedEmail = normalizeEmail(email);
        String userId = userIdByEmail.get(normalizedEmail);
        if (userId == null) {
            throw new AppException(401, "Invalid email or password.");
        }

        StoredUser user = usersById.get(userId);
        if (user == null || !matches(password, user.passwordHash(), user.passwordSalt())) {
            throw new AppException(401, "Invalid email or password.");
        }

        return toAuthUser(normalizeUserState(user), freeDailyLimit);
    }

    Optional<AuthUser> currentUser(HttpExchange exchange, int freeDailyLimit) {
        String token = readCookie(exchange.getRequestHeaders(), SESSION_COOKIE);
        if (token.isBlank()) {
            return Optional.empty();
        }

        SessionRecord session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }

        synchronized (this) {
            StoredUser user = usersById.get(session.userId());
            if (user == null) {
                sessions.remove(token);
                return Optional.empty();
            }
            return Optional.of(toAuthUser(normalizeUserState(user), freeDailyLimit));
        }
    }

    void startSession(HttpExchange exchange, AuthUser user) {
        String token = randomToken();
        sessions.put(token, new SessionRecord(user.id(), Instant.now().toString()));
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax"
        );
    }

    void endSession(HttpExchange exchange) {
        String token = readCookie(exchange.getRequestHeaders(), SESSION_COOKIE);
        if (!token.isBlank()) {
            sessions.remove(token);
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
        StoredUser user = requireStoredUser(userId);
        StoredUser normalized = normalizeUserState(user);
        if (!normalized.premium() && normalized.dailyUsageCount() >= freeDailyLimit) {
            throw new AppException(403, dailyLimitExceededMessage(freeDailyLimit));
        }
    }

    synchronized AuthUser recordSuccessfulGenerate(String userId, int freeDailyLimit) {
        StoredUser user = requireStoredUser(userId);
        StoredUser normalized = normalizeUserState(user);

        if (normalized.premium()) {
            return toAuthUser(normalized, freeDailyLimit);
        }

        if (normalized.dailyUsageCount() >= freeDailyLimit) {
            throw new AppException(403, dailyLimitExceededMessage(freeDailyLimit));
        }

        StoredUser updated = new StoredUser(
            normalized.id(),
            normalized.name(),
            normalized.email(),
            normalized.passwordHash(),
            normalized.passwordSalt(),
            normalized.createdAt(),
            todayString(),
            normalized.dailyUsageCount() + 1,
            false,
            "",
            "",
            "",
            normalized.premiumActivatedAt(),
            normalized.emailVerified(),
            normalized.verificationToken(),
            normalized.verificationSentAt(),
            normalized.emailVerifiedAt(),
            normalized.resetToken(),
            normalized.resetSentAt()
        );
        usersById.put(updated.id(), updated);
        saveUsers();
        return toAuthUser(updated, freeDailyLimit);
    }

    synchronized AuthUser activatePremium(
        String userId,
        String premiumPlanCode,
        String premiumPlanLabel,
        String premiumExpiresAt,
        int freeDailyLimit
    ) {
        StoredUser user = requireStoredUser(userId);
        StoredUser normalized = normalizeUserState(user);
        if (normalized.premium()) {
            return toAuthUser(normalized, freeDailyLimit);
        }

        StoredUser updated = new StoredUser(
            normalized.id(),
            normalized.name(),
            normalized.email(),
            normalized.passwordHash(),
            normalized.passwordSalt(),
            normalized.createdAt(),
            normalized.usageDate(),
            normalized.dailyUsageCount(),
            true,
            premiumPlanCode,
            premiumPlanLabel,
            premiumExpiresAt,
            Instant.now().toString(),
            normalized.emailVerified(),
            normalized.verificationToken(),
            normalized.verificationSentAt(),
            normalized.emailVerifiedAt(),
            normalized.resetToken(),
            normalized.resetSentAt()
        );
        usersById.put(updated.id(), updated);
        saveUsers();
        return toAuthUser(updated, freeDailyLimit);
    }

    synchronized AuthUser verifyEmail(String token, int freeDailyLimit) {
        String normalizedToken = normalizeVerificationToken(token);
        StoredUser matchedUser = null;

        for (StoredUser user : usersById.values()) {
            if (!user.emailVerified() && normalizedToken.equals(user.verificationToken())) {
                matchedUser = user;
                break;
            }
        }

        if (matchedUser == null) {
            throw new AppException(400, "This verification link is invalid or expired.");
        }

        StoredUser updated = new StoredUser(
            matchedUser.id(),
            matchedUser.name(),
            matchedUser.email(),
            matchedUser.passwordHash(),
            matchedUser.passwordSalt(),
            matchedUser.createdAt(),
            matchedUser.usageDate(),
            matchedUser.dailyUsageCount(),
            matchedUser.premium(),
            matchedUser.premiumPlanCode(),
            matchedUser.premiumPlanLabel(),
            matchedUser.premiumExpiresAt(),
            matchedUser.premiumActivatedAt(),
            true,
            "",
            matchedUser.verificationSentAt(),
            Instant.now().toString(),
            matchedUser.resetToken(),
            matchedUser.resetSentAt()
        );
        usersById.put(updated.id(), updated);
        saveUsers();
        return toAuthUser(updated, freeDailyLimit);
    }

    synchronized VerificationDispatch resendVerification(String userId, int freeDailyLimit) {
        StoredUser user = requireStoredUser(userId);
        StoredUser normalized = normalizeUserState(user);
        if (normalized.emailVerified()) {
            throw new AppException(409, "Email is already verified.");
        }

        String verificationToken = randomToken();
        StoredUser updated = new StoredUser(
            normalized.id(),
            normalized.name(),
            normalized.email(),
            normalized.passwordHash(),
            normalized.passwordSalt(),
            normalized.createdAt(),
            normalized.usageDate(),
            normalized.dailyUsageCount(),
            normalized.premium(),
            normalized.premiumPlanCode(),
            normalized.premiumPlanLabel(),
            normalized.premiumExpiresAt(),
            normalized.premiumActivatedAt(),
            false,
            verificationToken,
            Instant.now().toString(),
            normalized.emailVerifiedAt(),
            normalized.resetToken(),
            normalized.resetSentAt()
        );
        usersById.put(updated.id(), updated);
        saveUsers();
        return new VerificationDispatch(toAuthUser(updated, freeDailyLimit), verificationToken);
    }

    synchronized Optional<PasswordResetDispatch> requestPasswordReset(String email, int freeDailyLimit) {
        String normalizedEmail = normalizeEmail(email);
        String userId = userIdByEmail.get(normalizedEmail);
        if (userId == null) {
            return Optional.empty();
        }

        StoredUser user = requireStoredUser(userId);
        StoredUser normalized = normalizeUserState(user);
        String resetToken = randomToken();
        StoredUser updated = new StoredUser(
            normalized.id(),
            normalized.name(),
            normalized.email(),
            normalized.passwordHash(),
            normalized.passwordSalt(),
            normalized.createdAt(),
            normalized.usageDate(),
            normalized.dailyUsageCount(),
            normalized.premium(),
            normalized.premiumPlanCode(),
            normalized.premiumPlanLabel(),
            normalized.premiumExpiresAt(),
            normalized.premiumActivatedAt(),
            normalized.emailVerified(),
            normalized.verificationToken(),
            normalized.verificationSentAt(),
            normalized.emailVerifiedAt(),
            resetToken,
            Instant.now().toString()
        );
        usersById.put(updated.id(), updated);
        saveUsers();
        return Optional.of(new PasswordResetDispatch(toAuthUser(updated, freeDailyLimit), resetToken));
    }

    synchronized AuthUser resetPassword(String token, String password, int freeDailyLimit) {
        String normalizedToken = normalizeResetToken(token);
        validatePassword(password);

        StoredUser matchedUser = null;
        for (StoredUser user : usersById.values()) {
            if (!user.resetToken().isBlank() && normalizedToken.equals(user.resetToken())) {
                matchedUser = user;
                break;
            }
        }

        if (matchedUser == null || resetTokenExpired(matchedUser)) {
            throw new AppException(400, "This reset link is invalid or expired.");
        }

        StoredUser normalized = normalizeUserState(matchedUser);
        PasswordHash hash = createPasswordHash(password);
        StoredUser updated = new StoredUser(
            normalized.id(),
            normalized.name(),
            normalized.email(),
            hash.hash(),
            hash.salt(),
            normalized.createdAt(),
            normalized.usageDate(),
            normalized.dailyUsageCount(),
            normalized.premium(),
            normalized.premiumPlanCode(),
            normalized.premiumPlanLabel(),
            normalized.premiumExpiresAt(),
            normalized.premiumActivatedAt(),
            normalized.emailVerified(),
            normalized.verificationToken(),
            normalized.verificationSentAt(),
            normalized.emailVerifiedAt(),
            "",
            ""
        );
        usersById.put(updated.id(), updated);
        clearSessionsForUser(updated.id());
        saveUsers();
        return toAuthUser(updated, freeDailyLimit);
    }

    private synchronized void loadUsers() {
        usersById.clear();
        userIdByEmail.clear();

        if (!Files.exists(usersFile)) {
            return;
        }

        try {
            String json = Files.readString(usersFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }

            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(json), "Invalid users store.");
            for (Object entry : MiniJson.asList(root.get("users"))) {
                if (!(entry instanceof Map<?, ?>)) {
                    continue;
                }

                Map<String, Object> item = MiniJson.asObject(entry, "Invalid users store.");
                StoredUser user = new StoredUser(
                    readString(item, "id"),
                    readString(item, "name"),
                    normalizeEmail(readString(item, "email")),
                    readString(item, "passwordHash"),
                    readString(item, "passwordSalt"),
                    readString(item, "createdAt"),
                    readString(item, "usageDate"),
                    readInt(item, "dailyUsageCount"),
                    readBoolean(item, "premium"),
                    readString(item, "premiumPlanCode"),
                    readString(item, "premiumPlanLabel"),
                    readString(item, "premiumExpiresAt"),
                    readString(item, "premiumActivatedAt"),
                    readBoolean(item, "emailVerified", true),
                    readString(item, "verificationToken"),
                    readString(item, "verificationSentAt"),
                    readString(item, "emailVerifiedAt"),
                    readString(item, "resetToken"),
                    readString(item, "resetSentAt")
                );

                if (user.id().isBlank() || user.email().isBlank() || user.passwordHash().isBlank() || user.passwordSalt().isBlank()) {
                    continue;
                }

                usersById.put(user.id(), user);
                userIdByEmail.put(user.email(), user.id());
            }
        } catch (IOException error) {
            throw new AppException(500, "Unable to read authentication data.");
        }
    }

    private synchronized void saveUsers() {
        List<Object> users = new ArrayList<>();
        for (StoredUser user : usersById.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.id());
            item.put("name", user.name());
            item.put("email", user.email());
            item.put("passwordHash", user.passwordHash());
            item.put("passwordSalt", user.passwordSalt());
            item.put("createdAt", user.createdAt());
            item.put("usageDate", user.usageDate());
            item.put("dailyUsageCount", user.dailyUsageCount());
            item.put("premium", user.premium());
            item.put("premiumPlanCode", user.premiumPlanCode());
            item.put("premiumPlanLabel", user.premiumPlanLabel());
            item.put("premiumExpiresAt", user.premiumExpiresAt());
            item.put("premiumActivatedAt", user.premiumActivatedAt());
            item.put("emailVerified", user.emailVerified());
            item.put("verificationToken", user.verificationToken());
            item.put("verificationSentAt", user.verificationSentAt());
            item.put("emailVerifiedAt", user.emailVerifiedAt());
            item.put("resetToken", user.resetToken());
            item.put("resetSentAt", user.resetSentAt());
            users.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("users", users);
        writeAtomically(usersFile, MiniJson.stringify(payload));
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
            throw new AppException(500, "Unable to persist authentication data.");
        }
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

    private boolean readBoolean(Map<String, Object> payload, String key, boolean fallback) {
        if (!payload.containsKey(key)) {
            return fallback;
        }
        return readBoolean(payload, key);
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

    private StoredUser requireStoredUser(String userId) {
        StoredUser user = usersById.get(userId);
        if (user == null) {
            throw new AppException(404, "Account not found.");
        }
        return user;
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
            usersById.put(normalized.id(), normalized);
            saveUsers();
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
        sessions.entrySet().removeIf((entry) -> userId.equals(entry.getValue().userId()));
    }

    private String todayString() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    private String dailyLimitExceededMessage(int freeDailyLimit) {
        return "Free plan limit reached for today. You can generate up to " + freeDailyLimit + " solutions per day. Upgrade to premium for unlimited solves.";
    }

    private record PasswordHash(String hash, String salt) {
    }

    private record SessionRecord(String userId, String createdAt) {
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
