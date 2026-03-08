package com.spectrumforge;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;

import static com.mongodb.client.model.Filters.eq;

final class HistoryService {
    private static final int MAX_ENTRIES_PER_USER = 30;

    private final MongoCollection<Document> collection;
    private final MongoStore store;

    HistoryService(MongoStore store) {
        this.store = store;
        this.collection = store.history();
    }

    synchronized HistorySnapshot save(AuthUser user, GenerateRequest request, Map<String, Object> result) {
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        Map<String, Object> resultCopy = asJsonMap(result);
        resultCopy.remove("historyId");
        resultCopy.remove("savedAt");

        Document entry = new Document("_id", id)
            .append("userId", user.id())
            .append("createdAt", createdAt)
            .append("pinned", false)
            .append("request", store.toDocument(request.toMap()))
            .append("result", store.toDocument(resultCopy));
        collection.insertOne(entry);
        pruneUserHistory(user.id());
        return new HistorySnapshot(id, createdAt);
    }

    synchronized List<Object> listForUser(String userId) {
        List<Object> items = new ArrayList<>();
        for (Document entry : collection.find(eq("userId", userId))
            .sort(Sorts.orderBy(Sorts.descending("pinned"), Sorts.descending("createdAt")))) {
            items.add(toSummary(entry));
        }
        return items;
    }

    synchronized Map<String, Object> getForUser(String userId, String id) {
        Document entry = findUserEntry(userId, id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", entry.getString("_id"));
        payload.put("createdAt", entry.getString("createdAt"));
        payload.put("pinned", readBoolean(entry, "pinned"));
        payload.put("request", store.toMap(entry.get("request", Document.class)));
        payload.put("result", store.toMap(entry.get("result", Document.class)));
        return payload;
    }

    synchronized Map<String, Object> pinForUser(String userId, String id, boolean pinned) {
        Document entry = findUserEntry(userId, id);
        collection.updateOne(eq("_id", id), new Document("$set", new Document("pinned", pinned)));
        entry.put("pinned", pinned);
        return toSummary(entry);
    }

    synchronized void deleteForUser(String userId, String id) {
        Document entry = findUserEntry(userId, id);
        collection.deleteOne(eq("_id", entry.getString("_id")));
    }

    private void pruneUserHistory(String userId) {
        List<String> extraIds = new ArrayList<>();
        int index = 0;
        for (Document entry : collection.find(eq("userId", userId))
            .sort(Sorts.orderBy(Sorts.descending("pinned"), Sorts.descending("createdAt")))) {
            if (index >= MAX_ENTRIES_PER_USER) {
                extraIds.add(entry.getString("_id"));
            }
            index++;
        }

        for (String extraId : extraIds) {
            collection.deleteOne(eq("_id", extraId));
        }
    }

    private Document findUserEntry(String userId, String id) {
        Document entry = collection.find(new Document("_id", id).append("userId", userId)).first();
        if (entry == null) {
            throw new AppException(404, "Saved chat not found.");
        }
        return entry;
    }

    private Map<String, Object> toSummary(Document entry) {
        Map<String, Object> request = store.toMap(entry.get("request", Document.class));
        Map<String, Object> result = store.toMap(entry.get("result", Document.class));

        String title = readString(result, "title");
        if (title.isBlank()) {
            title = "Saved coding chat";
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", entry.getString("_id"));
        summary.put("title", title);
        summary.put("preview", truncate(readString(request, "problemStatement"), 160));
        summary.put("language", readString(request, "primaryLanguage"));
        summary.put("createdAt", entry.getString("createdAt"));
        summary.put("pinned", readBoolean(entry, "pinned"));
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

    private boolean readBoolean(Document payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
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
}
