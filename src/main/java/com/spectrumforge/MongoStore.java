package com.spectrumforge;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bson.Document;

import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;

final class MongoStore implements AutoCloseable {
    private final MongoClient client;
    private final MongoDatabase database;

    private MongoStore(MongoClient client, MongoDatabase database) {
        this.client = client;
        this.database = database;
        ensureIndexes();
    }

    static MongoStore connect(AppConfig config) {
        if (config.mongodbUri().isBlank()) {
            throw new AppException(500, "MONGODB_URI is missing.");
        }
        if (looksLikePlaceholder(config.mongodbUri())) {
            throw new AppException(500, "MONGODB_URI still contains a placeholder value.");
        }

        try {
            ConnectionString connectionString = new ConnectionString(config.mongodbUri());
            MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .build();
            MongoClient client = MongoClients.create(settings);
            MongoDatabase database = client.getDatabase(config.mongodbDatabase());
            database.runCommand(new Document("ping", 1));
            return new MongoStore(client, database);
        } catch (Exception error) {
            throw new AppException(500, connectionFailureMessage(error));
        }
    }

    MongoCollection<Document> users() {
        return database.getCollection("users");
    }

    MongoCollection<Document> sessions() {
        return database.getCollection("sessions");
    }

    MongoCollection<Document> history() {
        return database.getCollection("history");
    }

    MongoCollection<Document> payments() {
        return database.getCollection("payments");
    }

    Document toDocument(Map<String, Object> value) {
        return Document.parse(MiniJson.stringify(value));
    }

    Map<String, Object> toMap(Document value) {
        if (value == null) {
            return Map.of();
        }
        Document copy = new Document(value);
        copy.remove("_id");
        return MiniJson.asObject(MiniJson.parse(copy.toJson()), "Invalid MongoDB document.");
    }

    Date nowPlusDays(long days) {
        return Date.from(java.time.Instant.now().plus(days, java.time.temporal.ChronoUnit.DAYS));
    }

    private void ensureIndexes() {
        users().createIndex(ascending("email"), new IndexOptions().unique(true));
        users().createIndex(ascending("verificationToken"));
        users().createIndex(ascending("resetToken"));
        history().createIndex(compoundIndex(ascending("userId"), descending("pinned"), descending("createdAt")));
        payments().createIndex(ascending("transactionReference"), new IndexOptions().unique(true));
        payments().createIndex(compoundIndex(ascending("userId"), ascending("status"), descending("createdAt")));
        payments().createIndex(compoundIndex(ascending("status"), descending("createdAt")));
        sessions().createIndex(ascending("userId"));
        sessions().createIndex(
            ascending("expiresAt"),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)
        );
    }

    @Override
    public void close() {
        client.close();
    }

    private static String connectionFailureMessage(Exception error) {
        String detail = sanitize(error.getMessage());
        String base = "Unable to connect to MongoDB. Check MONGODB_URI, database credentials, and Atlas network access.";
        if (detail.isBlank()) {
            return base;
        }
        return base + " Cause: " + detail;
    }

    private static boolean looksLikePlaceholder(String uri) {
        return uri.contains("YOUR_URL_ENCODED_PASSWORD") || uri.contains("<") || uri.contains(">");
    }

    private static String sanitize(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }

        String masked = detail
            .replaceAll("(mongodb(?:\\+srv)?://[^:/\\s]+:)[^@\\s]+@", "$1****@")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim();

        return masked.length() > 240 ? masked.substring(0, 240) + "..." : masked;
    }
}
