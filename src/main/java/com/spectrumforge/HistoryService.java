package com.spectrumforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HistoryService {
    private static final int MAX_ENTRIES_PER_USER = 30;

    private final Path historyFile;
    private final List<StoredHistory> entries = new ArrayList<>();

    HistoryService(Path dataDir) {
        this.historyFile = dataDir.resolve("history.json");
        try {
            Files.createDirectories(dataDir);
        } catch (IOException error) {
            throw new AppException(500, "Unable to prepare history storage.");
        }
        loadHistory();
    }

    synchronized HistorySnapshot save(AuthUser user, GenerateRequest request, Map<String, Object> result) {
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        Map<String, Object> requestCopy = asJsonMap(request.toMap());
        Map<String, Object> resultCopy = asJsonMap(result);
        resultCopy.remove("historyId");
        resultCopy.remove("savedAt");

        entries.add(new StoredHistory(id, user.id(), createdAt, false, requestCopy, resultCopy));
        pruneUserHistory(user.id());
        saveHistory();
        return new HistorySnapshot(id, createdAt);
    }

    synchronized List<Object> listForUser(String userId) {
        List<Object> items = new ArrayList<>();
        userEntries(userId).stream()
            .sorted(historyComparator())
            .forEach(entry -> items.add(toSummary(entry)));
        return items;
    }

    synchronized Map<String, Object> getForUser(String userId, String id) {
        StoredHistory entry = entries.stream()
            .filter(item -> item.userId().equals(userId) && item.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AppException(404, "Saved chat not found."));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", entry.id());
        payload.put("createdAt", entry.createdAt());
        payload.put("pinned", entry.pinned());
        payload.put("request", asJsonMap(entry.request()));
        payload.put("result", asJsonMap(entry.result()));
        return payload;
    }

    synchronized Map<String, Object> pinForUser(String userId, String id, boolean pinned) {
        StoredHistory entry = findUserEntry(userId, id);
        entries.remove(entry);
        StoredHistory updated = new StoredHistory(entry.id(), entry.userId(), entry.createdAt(), pinned, entry.request(), entry.result());
        entries.add(updated);
        saveHistory();
        return toSummary(updated);
    }

    synchronized void deleteForUser(String userId, String id) {
        StoredHistory entry = findUserEntry(userId, id);
        entries.remove(entry);
        saveHistory();
    }

    private synchronized void loadHistory() {
        entries.clear();
        if (!Files.exists(historyFile)) {
            return;
        }

        try {
            String json = Files.readString(historyFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }

            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(json), "Invalid history store.");
            for (Object entry : MiniJson.asList(root.get("entries"))) {
                if (!(entry instanceof Map<?, ?>)) {
                    continue;
                }

                Map<String, Object> item = MiniJson.asObject(entry, "Invalid history store.");
                String id = readString(item, "id");
                String userId = readString(item, "userId");
                String createdAt = readString(item, "createdAt");
                Object request = item.get("request");
                Object result = item.get("result");

                if (id.isBlank() || userId.isBlank() || createdAt.isBlank() || !(request instanceof Map<?, ?>) || !(result instanceof Map<?, ?>)) {
                    continue;
                }

                entries.add(new StoredHistory(
                    id,
                    userId,
                    createdAt,
                    readBoolean(item, "pinned"),
                    MiniJson.asObject(request, "Invalid history request."),
                    MiniJson.asObject(result, "Invalid history result.")
                ));
            }
        } catch (IOException error) {
            throw new AppException(500, "Unable to read saved history.");
        }
    }

    private synchronized void saveHistory() {
        List<Object> serializedEntries = new ArrayList<>();
        for (StoredHistory entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.id());
            item.put("userId", entry.userId());
            item.put("createdAt", entry.createdAt());
            item.put("pinned", entry.pinned());
            item.put("request", entry.request());
            item.put("result", entry.result());
            serializedEntries.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entries", serializedEntries);
        writeAtomically(historyFile, MiniJson.stringify(payload));
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
            throw new AppException(500, "Unable to persist saved history.");
        }
    }

    private void pruneUserHistory(String userId) {
        List<StoredHistory> userEntries = userEntries(userId).stream()
            .sorted(historyComparator())
            .toList();

        if (userEntries.size() <= MAX_ENTRIES_PER_USER) {
            return;
        }

        for (int index = MAX_ENTRIES_PER_USER; index < userEntries.size(); index++) {
            entries.remove(userEntries.get(index));
        }
    }

    private List<StoredHistory> userEntries(String userId) {
        return entries.stream()
            .filter(entry -> entry.userId().equals(userId))
            .toList();
    }

    private StoredHistory findUserEntry(String userId, String id) {
        return entries.stream()
            .filter(item -> item.userId().equals(userId) && item.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AppException(404, "Saved chat not found."));
    }

    private Map<String, Object> toSummary(StoredHistory entry) {
        Map<String, Object> request = entry.request();
        Map<String, Object> result = entry.result();

        String title = readString(result, "title");
        if (title.isBlank()) {
            title = "Saved coding chat";
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", entry.id());
        summary.put("title", title);
        summary.put("preview", truncate(readString(request, "problemStatement"), 160));
        summary.put("language", readString(request, "primaryLanguage"));
        summary.put("createdAt", entry.createdAt());
        summary.put("pinned", entry.pinned());
        return summary;
    }

    private Map<String, Object> asJsonMap(Object value) {
        Object clone = MiniJson.parse(MiniJson.stringify(value));
        return MiniJson.asObject(clone, "Invalid JSON object.");
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

    private Comparator<StoredHistory> historyComparator() {
        return Comparator.comparing(StoredHistory::pinned).reversed()
            .thenComparing(StoredHistory::createdAt, Comparator.reverseOrder());
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength).trim() + "...";
    }

    record HistorySnapshot(String id, String createdAt) {
    }

    private record StoredHistory(
        String id,
        String userId,
        String createdAt,
        boolean pinned,
        Map<String, Object> request,
        Map<String, Object> result
    ) {
    }
}
