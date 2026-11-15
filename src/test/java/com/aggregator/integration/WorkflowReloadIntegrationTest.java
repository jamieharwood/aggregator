package com.aggregator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.aggregator.routing.WorkflowRegistry;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Proves that changes made in MongoDB take effect in the running application on reload. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WorkflowReloadIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WorkflowRegistry registry;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", WorkflowStore::uri);
    }

    @BeforeEach
    void stubs() {
        Downstream.SERVER.resetAll();
        Downstream.SERVER.stubFor(get(urlEqualTo("/default/api/reports/7")).willReturn(okJson("{\"from\":\"default\"}")));
        Downstream.SERVER.stubFor(get(urlEqualTo("/reports")).willReturn(okJson("{\"from\":\"reports\"}")));
        Downstream.SERVER.stubFor(get(urlEqualTo("/reports-v2")).willReturn(okJson("{\"from\":\"reports-v2\"}")));
    }

    @AfterEach
    void restore() {
        WorkflowStore.seed();
        registry.refresh();
    }

    @Test
    void addedChangedAndRemovedWorkflowsTakeEffectOnReload() throws Exception {
        assertThat(getReport()).isEqualTo("{\"from\":\"default\"}");

        WorkflowStore.workflows().insertOne(Document.parse("""
                { "_id": "report-flow", "match": { "path": "/api/reports/**", "methods": ["GET"] },
                  "sync": [ { "name": "reports", "url": "%s/reports", "method": "GET", "timeout": "2s" } ] }
                """.formatted(Downstream.baseUrl())));
        assertThat(getReport()).as("before reload").isEqualTo("{\"from\":\"default\"}");
        assertThat(registry.refresh()).isTrue();
        assertThat(getReport()).isEqualTo("{\"from\":\"reports\"}");

        WorkflowStore.workflows().updateOne(Filters.eq("_id", "report-flow"),
                Updates.set("sync.0.url", Downstream.baseUrl() + "/reports-v2"));
        assertThat(registry.refresh()).isTrue();
        assertThat(getReport()).isEqualTo("{\"from\":\"reports-v2\"}");

        WorkflowStore.workflows().deleteOne(Filters.eq("_id", "report-flow"));
        assertThat(registry.refresh()).isTrue();
        assertThat(getReport()).isEqualTo("{\"from\":\"default\"}");
    }

    @Test
    void anInvalidChangeIsRejectedAndThePreviousWorkflowsKeepServing() throws Exception {
        WorkflowStore.workflows().deleteOne(Filters.eq("_id", "catch-all"));

        assertThat(registry.refresh()).isFalse();

        assertThat(registry.workflows()).hasSize(4);
        assertThat(getReport()).isEqualTo("{\"from\":\"default\"}");
    }

    private String getReport() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/reports/7")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
