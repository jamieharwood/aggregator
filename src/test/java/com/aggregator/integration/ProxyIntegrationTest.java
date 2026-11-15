package com.aggregator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ProxyIntegrationTest {

    private static final String VALIDATED = "{\"step\":\"validated\"}";

    private static final String ENRICHED = "{\"step\":\"enriched\"}";

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("downstream.base-url", Downstream::baseUrl);
    }

    @BeforeEach
    void reset() {
        Downstream.SERVER.resetAll();
    }

    @Test
    void syncEndpointsRunInOrderAndFeedTheAsyncStage() throws Exception {
        stubOrderFlow();

        HttpResponse<String> response = send("/api/orders/42", "{\"order\":42}", "Authorization", "Bearer abc");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode results = jsonMapper.readTree(response.body()).get("results");
        assertThat(results.propertyNames()).containsExactly("billing", "inventory");
        assertThat(results.get("billing").get("status").asInt()).isEqualTo(200);
        assertThat(results.get("billing").get("body").get("charged").asBoolean()).isTrue();
        assertThat(results.get("inventory").get("status").asInt()).isEqualTo(201);
        assertThat(results.get("inventory").get("body").get("reserved").asInt()).isEqualTo(3);

        // Each endpoint received the previous response; both async endpoints received the last sync response.
        Downstream.SERVER.verify(1, postRequestedFor(urlEqualTo("/validate")).withRequestBody(equalToJson("{\"order\":42}")));
        Downstream.SERVER.verify(1, postRequestedFor(urlEqualTo("/enrich")).withRequestBody(equalToJson(VALIDATED))
                .withHeader("X-Static", equalTo("fixed"))
                .withHeader("Authorization", equalTo("Bearer abc")));
        Downstream.SERVER.verify(1, postRequestedFor(urlEqualTo("/billing")).withRequestBody(equalToJson(ENRICHED)));
        Downstream.SERVER.verify(1, postRequestedFor(urlEqualTo("/inventory")).withRequestBody(equalToJson(ENRICHED)));

        // Sync endpoints were called in the configured order, before any async endpoint.
        List<String> order = Downstream.SERVER.getAllServeEvents().stream()
                .sorted((a, b) -> a.getRequest().getLoggedDate().compareTo(b.getRequest().getLoggedDate()))
                .map(event -> event.getRequest().getUrl())
                .toList();
        assertThat(order.subList(0, 2)).containsExactly("/validate", "/enrich");
        assertThat(order.subList(2, 4)).containsExactlyInAnyOrder("/billing", "/inventory");
    }

    @Test
    void correlationIdIsPropagatedToEveryEndpointAndReturned() throws Exception {
        stubOrderFlow();

        HttpResponse<String> response = send("/api/orders/42", "{\"order\":42}", "X-Correlation-Id", "corr-123");

        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-123");
        for (ServeEvent event : Downstream.SERVER.getAllServeEvents()) {
            assertThat(event.getRequest().getHeader("X-Correlation-Id")).isEqualTo("corr-123");
        }
        assertThat(Downstream.SERVER.getAllServeEvents()).hasSize(4);
    }

    @Test
    void correlationIdIsGeneratedWhenAbsent() throws Exception {
        stubOrderFlow();

        HttpResponse<String> response = send("/api/orders/42", "{\"order\":42}");

        String generated = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(generated).isNotBlank();
        Downstream.SERVER.verify(postRequestedFor(urlEqualTo("/billing")).withHeader("X-Correlation-Id", equalTo(generated)));
    }

    @Test
    void syncFailureStopsTheChainWith502() throws Exception {
        stubOrderFlow();
        Downstream.SERVER.stubFor(post(urlEqualTo("/enrich")).willReturn(aResponse().withStatus(500).withBody("boom")));

        HttpResponse<String> response = send("/api/orders/42", "{\"order\":42}");

        assertThat(response.statusCode()).isEqualTo(502);
        JsonNode error = jsonMapper.readTree(response.body());
        assertThat(error.get("endpoint").asString()).isEqualTo("enrich");
        assertThat(error.get("downstreamStatus").asInt()).isEqualTo(500);
        Downstream.SERVER.verify(0, postRequestedFor(urlEqualTo("/billing")));
        Downstream.SERVER.verify(0, postRequestedFor(urlEqualTo("/inventory")));
    }

    @Test
    void asyncPartialFailureStillReturns200() throws Exception {
        stubOrderFlow();
        Downstream.SERVER.stubFor(post(urlEqualTo("/inventory")).willReturn(aResponse().withStatus(503)));

        HttpResponse<String> response = send("/api/orders/42", "{\"order\":42}");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode results = jsonMapper.readTree(response.body()).get("results");
        assertThat(results.get("billing").get("body").get("charged").asBoolean()).isTrue();
        assertThat(results.get("inventory").get("status").asInt()).isEqualTo(503);
        assertThat(results.get("inventory").has("error")).isTrue();
    }

    @Test
    void asyncFailureInFailModeReturns502() throws Exception {
        Downstream.SERVER.stubFor(post(urlEqualTo("/billing")).willReturn(okJson("{\"charged\":true}")));
        Downstream.SERVER.stubFor(post(urlEqualTo("/inventory")).willReturn(aResponse().withStatus(503)));

        HttpResponse<String> response = send("/api/strict", "{\"order\":42}");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(jsonMapper.readTree(response.body()).get("failedEndpoints").get(0).asString()).isEqualTo("inventory");
    }

    @Test
    void syncOnlyWorkflowReturnsTheLastResponseUnchanged() throws Exception {
        Downstream.SERVER.stubFor(post(urlEqualTo("/lookup")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"customer\":7}")));

        HttpResponse<String> response = send("/api/customers/7", "{\"id\":7}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("{\"customer\":7}");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
    }

    @Test
    void timeoutReturns502() throws Exception {
        Downstream.SERVER.stubFor(post(urlEqualTo("/lookup")).willReturn(okJson("{}").withFixedDelay(3000)));

        long started = System.nanoTime();
        HttpResponse<String> response = send("/api/customers/7", "{\"id\":7}");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(jsonMapper.readTree(response.body()).get("reason").asString()).isEqualTo("timeout");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void asyncEndpointsAreCalledConcurrently() throws Exception {
        stubOrderFlow();
        Downstream.SERVER.stubFor(post(urlEqualTo("/billing")).willReturn(okJson("{\"charged\":true}").withFixedDelay(600)));
        Downstream.SERVER.stubFor(post(urlEqualTo("/inventory")).willReturn(okJson("{\"reserved\":3}").withFixedDelay(600)));

        long started = System.nanoTime();
        HttpResponse<String> response = send("/api/orders/42", "{\"order\":42}");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(200);
        // Sequential calls would take at least 1200 ms.
        assertThat(elapsed).isBetween(Duration.ofMillis(600), Duration.ofMillis(1100));
    }

    @Test
    void unmatchedRequestGoesToTheCatchAllWithPathAndQueryPreserved() throws Exception {
        Downstream.SERVER.stubFor(get(urlPathEqualTo("/default/unknown/thing"))
                .willReturn(okJson("{\"handledBy\":\"default\"}")));

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                uri("/unknown/thing?a=1&b=two%20words")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"handledBy\":\"default\"}");
        Downstream.SERVER.verify(getRequestedFor(urlEqualTo("/default/unknown/thing?a=1&b=two%20words")));
    }

    @Test
    void methodNotMatchedBySpecificWorkflowGoesToTheCatchAll() throws Exception {
        Downstream.SERVER.stubFor(get(urlEqualTo("/default/api/orders/42")).willReturn(okJson("{\"handledBy\":\"default\"}")));

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/orders/42")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        Downstream.SERVER.verify(0, postRequestedFor(urlEqualTo("/validate")));
    }

    @Test
    void specificWorkflowDoesNotGoToTheCatchAll() throws Exception {
        stubOrderFlow();

        send("/api/orders/42", "{\"order\":42}");

        assertThat(Downstream.SERVER.getAllServeEvents())
                .noneMatch(event -> event.getRequest().getUrl().startsWith("/default"));
    }

    @Test
    void actuatorIsNotProxied() throws Exception {
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> unknown = http.send(HttpRequest.newBuilder(uri("/actuator/nope")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(Downstream.SERVER.getAllServeEvents()).isEmpty();
    }

    @Test
    void timersAreRecordedPerEndpointAndPerWorkflow() throws Exception {
        stubOrderFlow();

        send("/api/orders/42", "{\"order\":42}");

        assertThat(meterRegistry.find("proxy.endpoint.call")
                .tags("workflow.name", "order-flow", "endpoint.name", "billing", "stage", "async", "outcome", "success")
                .timer()).isNotNull();
        assertThat(meterRegistry.find("proxy.workflow")
                .tags("workflow.name", "order-flow", "outcome", "success").timer()).isNotNull();
    }

    private void stubOrderFlow() {
        Downstream.SERVER.stubFor(post(urlEqualTo("/validate")).willReturn(okJson(VALIDATED)));
        Downstream.SERVER.stubFor(post(urlEqualTo("/enrich")).willReturn(okJson(ENRICHED)));
        Downstream.SERVER.stubFor(post(urlEqualTo("/billing")).willReturn(okJson("{\"charged\":true}")));
        Downstream.SERVER.stubFor(post(urlEqualTo("/inventory")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"reserved\":3}")));
    }

    private HttpResponse<String> send(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
