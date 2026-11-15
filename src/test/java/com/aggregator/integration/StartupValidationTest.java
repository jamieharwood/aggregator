package com.aggregator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aggregator.AggregatorApplication;
import com.aggregator.routing.InvalidWorkflowDefinitionException;
import com.aggregator.routing.WorkflowRegistry;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Proves that the application only starts when the workflows stored in MongoDB are valid. */
class StartupValidationTest {

    private static final String CATCH_ALL = """
            { "_id": "catch-all", "match": { "path": "/**" },
              "sync": [ { "name": "backend", "url": "http://backend", "method": "INHERIT" } ] }
            """;

    private static final String ORDERS = """
            { "_id": "orders", "match": { "path": "/api/orders/**", "methods": ["POST"] },
              "async": [ { "name": "billing", "url": "http://billing/charge", "method": "POST" } ] }
            """;

    private static MongoServer server;

    private static MongoClient client;

    private static String uri;

    @BeforeAll
    static void startMongo() {
        server = new MongoServer(new MemoryBackend());
        uri = "mongodb://localhost:" + server.bind().getPort() + "/startup-test";
        client = MongoClients.create(uri);
    }

    @AfterAll
    static void stopMongo() {
        client.close();
        server.shutdown();
    }

    @BeforeEach
    void clear() {
        workflows().deleteMany(new Document());
    }

    @Test
    void startsWithValidWorkflows() {
        workflows().insertOne(Document.parse(CATCH_ALL));
        workflows().insertOne(Document.parse(ORDERS));

        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getBean(WorkflowRegistry.class).workflows()).hasSize(2);
        }
    }

    @Test
    void failsToStartWithoutACatchAll() {
        workflows().insertOne(Document.parse(ORDERS));

        assertStartupFailsWith("catch-all workflow is mandatory");
    }

    @Test
    void failsToStartWithAnEmptyCollection() {
        assertStartupFailsWith("catch-all workflow is mandatory");
    }

    @Test
    void failsToStartWhenTwoWorkflowsShareAPathAndMethod() {
        workflows().insertOne(Document.parse(CATCH_ALL));
        workflows().insertOne(Document.parse(ORDERS));
        workflows().insertOne(Document.parse(ORDERS.replace("\"orders\"", "\"orders-again\"")));

        assertStartupFailsWith("overlapping HTTP method");
    }

    @Test
    void failsToStartWithAWorkflowThatHasNoEndpoints() {
        workflows().insertOne(Document.parse("{ \"_id\": \"catch-all\", \"match\": { \"path\": \"/**\" } }"));

        assertStartupFailsWith("at least one endpoint");
    }

    private static void assertStartupFailsWith(String message) {
        Throwable failure = null;
        try (ConfigurableApplicationContext context = start()) {
            // Starting must not succeed.
        } catch (RuntimeException ex) {
            failure = ex;
        }
        assertThat(failure).as("application startup").isNotNull();
        assertThat(failure).hasRootCauseInstanceOf(InvalidWorkflowDefinitionException.class);
        assertThat(failure).rootCause().hasMessageContaining(message);
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(AggregatorApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                // A command-line argument, so that it overrides the URI in application.yml.
                .run("--spring.mongodb.uri=" + uri);
    }

    private static MongoCollection<Document> workflows() {
        return client.getDatabase("startup-test").getCollection("workflows");
    }
}
