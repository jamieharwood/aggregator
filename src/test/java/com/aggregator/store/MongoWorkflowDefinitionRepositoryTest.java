package com.aggregator.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.aggregator.config.ProxyProperties;
import com.aggregator.routing.InvalidWorkflowDefinitionException;
import com.aggregator.routing.WorkflowDefinition;
import com.aggregator.routing.WorkflowDefinition.AsyncFailureMode;
import com.aggregator.routing.WorkflowDefinition.EndpointDefinition;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MongoWorkflowDefinitionRepositoryTest {

    private static MongoServer server;

    private static MongoClient client;

    private MongoCollection<Document> collection;

    private MongoWorkflowDefinitionRepository repository;

    @BeforeAll
    static void startMongo() {
        server = new MongoServer(new MemoryBackend());
        client = MongoClients.create("mongodb://localhost:" + server.bind().getPort());
    }

    @AfterAll
    static void stopMongo() {
        client.close();
        server.shutdown();
    }

    @BeforeEach
    void setUp() {
        MongoDatabase database = client.getDatabase("repository-test");
        collection = database.getCollection("custom-workflows");
        collection.deleteMany(new Document());
        repository = new MongoWorkflowDefinitionRepository(database,
                new ProxyProperties(null, new ProxyProperties.WorkflowStore("custom-workflows", null)));
    }

    @Test
    void readsACompleteWorkflowDocument() {
        collection.insertOne(Document.parse("""
                {
                  "_id": "order-flow",
                  "match": { "path": "/api/orders/**", "methods": ["POST", "PUT"] },
                  "sync": [
                    { "name": "validate", "url": "https://validator.internal/validate", "method": "POST",
                      "timeout": "2s", "headers": { "X-Api-Key": "abc" }, "forwardHeaders": ["Authorization"] }
                  ],
                  "async": [
                    { "name": "billing", "url": "https://billing.internal/charge", "method": "INHERIT",
                      "timeout": 1500, "appendInboundPath": true }
                  ],
                  "onAsyncFailure": "fail"
                }
                """));

        WorkflowDefinition workflow = repository.findAll().get("order-flow");

        assertThat(workflow.match().path()).isEqualTo("/api/orders/**");
        assertThat(workflow.match().methods()).containsExactly("POST", "PUT");
        assertThat(workflow.onAsyncFailure()).isEqualTo(AsyncFailureMode.FAIL);
        assertThat(workflow.sync()).containsExactly(new EndpointDefinition("validate",
                "https://validator.internal/validate", "POST", Duration.ofSeconds(2), Map.of("X-Api-Key", "abc"),
                List.of("Authorization"), false));
        assertThat(workflow.async()).containsExactly(new EndpointDefinition("billing",
                "https://billing.internal/charge", "INHERIT", Duration.ofMillis(1500), Map.of(), List.of(), true));
    }

    @Test
    void appliesDefaultsToAMinimalDocument() {
        collection.insertOne(Document.parse("""
                { "_id": "catch-all", "match": { "path": "/**" },
                  "sync": [ { "name": "backend", "url": "http://backend", "method": "INHERIT" } ] }
                """));

        WorkflowDefinition workflow = repository.findAll().get("catch-all");

        assertThat(workflow.match().methods()).isEmpty();
        assertThat(workflow.async()).isEmpty();
        assertThat(workflow.onAsyncFailure()).isEqualTo(AsyncFailureMode.PARTIAL);
        assertThat(workflow.sync().getFirst().timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void returnsWorkflowsInNameOrder() {
        for (String name : List.of("zeta", "alpha", "middle")) {
            collection.insertOne(new Document("_id", name).append("match", new Document("path", "/" + name)));
        }

        assertThat(repository.findAll().keySet()).containsExactly("alpha", "middle", "zeta");
    }

    @Test
    void anEmptyCollectionYieldsNoWorkflows() {
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void rejectsDocumentsWithTheWrongShape() {
        assertInvalid("{ \"_id\": 42, \"match\": { \"path\": \"/**\" } }", "_id must be a non-empty string");
        assertInvalid("{ \"_id\": \"a\", \"match\": \"/**\" }", "match must be an object");
        assertInvalid("{ \"_id\": \"a\", \"sync\": { \"name\": \"x\" } }", "sync must be an array");
        assertInvalid("{ \"_id\": \"a\", \"sync\": [ \"x\" ] }", "sync[0] must be an object");
        assertInvalid("{ \"_id\": \"a\", \"sync\": [ { \"name\": \"x\", \"timeout\": \"soon\" } ] }", "timeout");
        assertInvalid("{ \"_id\": \"a\", \"sync\": [ { \"name\": \"x\", \"appendInboundPath\": \"yes\" } ] }",
                "appendInboundPath must be true or false");
        assertInvalid("{ \"_id\": \"a\", \"onAsyncFailure\": \"sometimes\" }", "onAsyncFailure");
    }

    private void assertInvalid(String json, String message) {
        collection.deleteMany(new Document());
        collection.insertOne(Document.parse(json));
        assertThatThrownBy(repository::findAll)
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .hasMessageContaining(message);
    }
}
