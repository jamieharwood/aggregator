package com.aggregator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Proves the span structure of a sync-then-async workflow using an in-memory span exporter. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTracing
class TracingIntegrationTest {

    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @TestConfiguration(proxyBeanMethods = false)
    static class SpanCapture {

        @Bean
        SpanProcessor inMemorySpanProcessor() {
            return SimpleSpanProcessor.create(SPANS);
        }
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("downstream.base-url", Downstream::baseUrl);
    }

    @BeforeEach
    void reset() {
        Downstream.SERVER.resetAll();
        SPANS.reset();
    }

    @Test
    void syncThenAsyncWorkflowProducesOneTraceWithStageAndEndpointSpans() throws Exception {
        Downstream.SERVER.stubFor(post(urlEqualTo("/validate")).willReturn(okJson("{\"step\":\"validated\"}")));
        Downstream.SERVER.stubFor(post(urlEqualTo("/enrich")).willReturn(okJson("{\"step\":\"enriched\"}")));
        Downstream.SERVER.stubFor(post(urlEqualTo("/billing")).willReturn(okJson("{}").withFixedDelay(400)));
        Downstream.SERVER.stubFor(post(urlEqualTo("/inventory")).willReturn(okJson("{}").withFixedDelay(400)));

        assertThat(postOrder().statusCode()).isEqualTo(200);
        List<SpanData> spans = awaitServerSpan();

        SpanData server = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
        SpanData workflow = span(spans, "workflow order-flow");
        SpanData syncStage = span(spans, "workflow.sync");
        SpanData asyncStage = span(spans, "workflow.async");
        SpanData validate = span(spans, "endpoint validate");
        SpanData enrich = span(spans, "endpoint enrich");
        SpanData billing = span(spans, "endpoint billing");
        SpanData inventory = span(spans, "endpoint inventory");

        // One trace for the whole request.
        assertThat(spans).hasSize(8);
        assertThat(spans).allMatch(span -> span.getTraceId().equals(server.getTraceId()));

        // Server span -> workflow -> stage -> endpoint.
        assertThat(server.getAttributes().get(AttributeKey.stringKey("workflow.name"))).isEqualTo("order-flow");
        assertThat(workflow.getParentSpanId()).isEqualTo(server.getSpanId());
        assertThat(syncStage.getParentSpanId()).isEqualTo(workflow.getSpanId());
        assertThat(asyncStage.getParentSpanId()).isEqualTo(workflow.getSpanId());
        assertThat(validate.getParentSpanId()).isEqualTo(syncStage.getSpanId());
        assertThat(enrich.getParentSpanId()).isEqualTo(syncStage.getSpanId());
        assertThat(billing.getParentSpanId()).isEqualTo(asyncStage.getSpanId());
        assertThat(inventory.getParentSpanId()).isEqualTo(asyncStage.getSpanId());

        // Endpoint spans are client spans carrying the endpoint attributes.
        assertThat(List.of(validate, enrich, billing, inventory)).allMatch(span -> span.getKind() == SpanKind.CLIENT);
        assertThat(attribute(enrich, "workflow.name")).isEqualTo("order-flow");
        assertThat(attribute(enrich, "endpoint.name")).isEqualTo("enrich");
        assertThat(attribute(enrich, "stage")).isEqualTo("sync");
        assertThat(attribute(enrich, "sync.position")).isEqualTo("2");
        assertThat(attribute(enrich, "url.full")).isEqualTo(Downstream.baseUrl() + "/enrich");
        assertThat(attribute(enrich, "http.request.method")).isEqualTo("POST");
        assertThat(attribute(enrich, "http.response.status_code")).isEqualTo("200");
        assertThat(attribute(billing, "stage")).isEqualTo("async");

        // Sync spans run one after the other; async spans overlap in time.
        assertThat(validate.getEndEpochNanos()).isLessThanOrEqualTo(enrich.getStartEpochNanos());
        assertThat(billing.getStartEpochNanos()).isLessThan(inventory.getEndEpochNanos());
        assertThat(inventory.getStartEpochNanos()).isLessThan(billing.getEndEpochNanos());
        long asyncStageNanos = asyncStage.getEndEpochNanos() - asyncStage.getStartEpochNanos();
        assertThat(Duration.ofNanos(asyncStageNanos)).isLessThan(Duration.ofMillis(750));

        // W3C trace context reached every downstream call, with the endpoint span as the parent.
        for (ServeEvent event : Downstream.SERVER.getAllServeEvents()) {
            assertThat(event.getRequest().getHeader("traceparent")).startsWith("00-" + server.getTraceId() + "-");
        }
        ServeEvent billingCall = Downstream.SERVER.getAllServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().equals("/billing")).findFirst().orElseThrow();
        assertThat(billingCall.getRequest().getHeader("traceparent")).contains("-" + billing.getSpanId() + "-");
    }

    @Test
    void inboundTraceContextIsContinued() throws Exception {
        Downstream.SERVER.stubFor(post(urlEqualTo("/lookup")).willReturn(okJson("{}")));
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/customers/1"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        List<SpanData> spans = awaitServerSpan();
        assertThat(spans).allMatch(span -> span.getTraceId().equals(traceId));
        assertThat(Downstream.SERVER.getAllServeEvents().getFirst().getRequest().getHeader("traceparent"))
                .startsWith("00-" + traceId + "-");
    }

    @Test
    void failedAndTimedOutCallsProduceErrorSpansWithEvents() throws Exception {
        Downstream.SERVER.stubFor(post(urlEqualTo("/validate")).willReturn(okJson("{}")));
        Downstream.SERVER.stubFor(post(urlEqualTo("/enrich")).willReturn(okJson("{}")));
        Downstream.SERVER.stubFor(post(urlEqualTo("/billing")).willReturn(aResponse().withStatus(500)));
        Downstream.SERVER.stubFor(post(urlEqualTo("/inventory")).willReturn(okJson("{}").withFixedDelay(4000)));

        assertThat(postOrder().statusCode()).isEqualTo(200);
        List<SpanData> spans = awaitServerSpan();

        SpanData billing = span(spans, "endpoint billing");
        assertThat(billing.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(attribute(billing, "outcome")).isEqualTo("failure");
        assertThat(attribute(billing, "http.response.status_code")).isEqualTo("500");
        assertThat(billing.getEvents()).anyMatch(event -> event.getName().equals("endpoint.failed"));
        assertThat(billing.getEvents()).anyMatch(event -> event.getName().equals("exception"));

        SpanData inventory = span(spans, "endpoint inventory");
        assertThat(inventory.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(attribute(inventory, "outcome")).isEqualTo("timeout");
        assertThat(inventory.getEvents()).anyMatch(event -> event.getName().equals("endpoint.timeout"));

        assertThat(span(spans, "endpoint validate").getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(spans).allMatch(span -> span.getTraceId().equals(billing.getTraceId()));
    }

    private HttpResponse<String> postOrder() throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/orders/42"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"order\":42}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The server span ends just after the response is written, so wait for it to be exported. */
    private static List<SpanData> awaitServerSpan() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<SpanData> spans = SPANS.getFinishedSpanItems();
            if (spans.stream().anyMatch(span -> span.getKind() == SpanKind.SERVER)) {
                return spans;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("No server span exported; got " + SPANS.getFinishedSpanItems());
    }

    private static SpanData span(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No span named '" + name + "' in "
                        + spans.stream().map(SpanData::getName).toList()));
    }

    private static String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
