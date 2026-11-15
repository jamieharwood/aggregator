package com.aggregator.integration;

import java.util.List;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.bson.Document;

/**
 * An in-memory MongoDB shared by the integration tests, seeded with the test workflows. Their
 * endpoints point at the WireMock server in {@link Downstream}.
 */
final class WorkflowStore {

    private static final MongoServer SERVER = new MongoServer(new MemoryBackend());

    private static final String URI;

    private static final MongoClient CLIENT;

    static {
        URI = "mongodb://localhost:" + SERVER.bind().getPort() + "/aggregator";
        CLIENT = MongoClients.create(URI);
        seed();
    }

    private WorkflowStore() {
    }

    static String uri() {
        return URI;
    }

    static MongoCollection<Document> workflows() {
        return CLIENT.getDatabase("aggregator").getCollection("workflows");
    }

    /** Replaces the stored workflows with the default test set. */
    static void seed() {
        workflows().deleteMany(new Document());
        workflows().insertMany(defaults());
    }

    private static List<Document> defaults() {
        String base = Downstream.baseUrl();
        return List.of(
                Document.parse("""
                        {
                          "_id": "order-flow",
                          "match": { "path": "/api/orders/**", "methods": ["POST"] },
                          "sync": [
                            { "name": "validate", "url": "%1$s/validate", "method": "POST", "timeout": "2s" },
                            { "name": "enrich", "url": "%1$s/enrich", "method": "POST", "timeout": "2s",
                              "headers": { "X-Static": "fixed" }, "forwardHeaders": ["Authorization"] }
                          ],
                          "async": [
                            { "name": "billing", "url": "%1$s/billing", "method": "POST", "timeout": "2s" },
                            { "name": "inventory", "url": "%1$s/inventory", "method": "POST", "timeout": 2000 }
                          ],
                          "onAsyncFailure": "partial"
                        }
                        """.formatted(base)),
                Document.parse("""
                        {
                          "_id": "strict-flow",
                          "match": { "path": "/api/strict", "methods": ["POST"] },
                          "async": [
                            { "name": "billing", "url": "%1$s/billing", "method": "POST", "timeout": "2s" },
                            { "name": "inventory", "url": "%1$s/inventory", "method": "POST", "timeout": "2s" }
                          ],
                          "onAsyncFailure": "fail"
                        }
                        """.formatted(base)),
                Document.parse("""
                        {
                          "_id": "customer-flow",
                          "match": { "path": "/api/customers/**", "methods": ["GET", "POST"] },
                          "sync": [
                            { "name": "lookup", "url": "%1$s/lookup", "method": "POST", "timeout": "500ms" }
                          ]
                        }
                        """.formatted(base)),
                Document.parse("""
                        {
                          "_id": "catch-all",
                          "match": { "path": "/**" },
                          "sync": [
                            { "name": "default-backend", "url": "%1$s/default", "method": "INHERIT",
                              "appendInboundPath": true, "timeout": "2s" }
                          ]
                        }
                        """.formatted(base)));
    }
}
