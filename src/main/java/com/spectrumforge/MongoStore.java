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
            throw new AppException(500, "MongoDB configuration is missing.");
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
            throw new AppException(500, "Unable to connect to MongoDB.");
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
}
