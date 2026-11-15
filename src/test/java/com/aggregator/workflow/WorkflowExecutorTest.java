package com.aggregator.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.aggregator.config.ProxyProperties.AsyncFailureMode;
import com.aggregator.routing.Endpoint;
import com.aggregator.routing.Workflow;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.util.pattern.PathPatternParser;

/** Unit tests for the workflow executor with a fake downstream; no web context involved. */
class WorkflowExecutorTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    private final FakeDownstream downstream = new FakeDownstream();

    private final WorkflowExecutor executor = new WorkflowExecutor(
            new EndpointInvoker(downstream, threads, ObservationRegistry.NOOP), threads, ObservationRegistry.NOOP,
            jsonMapper);

    @AfterEach
    void shutdown() {
        threads.shutdownNow();
    }

    @Test
    void syncOnlyChainsResponsesAndReturnsTheLastOneUnchanged() {
        downstream.reply("first", 200, "{\"step\":1}");
        downstream.reply("second", 201, "{\"step\":2}");

        ProxyResponse response = executor.execute(workflow(sync("first", "second"), List.of()), inbound("{\"in\":0}"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(text(response.body())).isEqualTo("{\"step\":2}");
        assertThat(downstream.calls).containsExactly("first", "second");
        assertThat(downstream.received("first")).isEqualTo("{\"in\":0}");
        assertThat(downstream.received("second")).isEqualTo("{\"step\":1}");
    }

    @Test
    void asyncOnlySendsTheInboundBodyToEveryEndpointAndMergesResponses() {
        downstream.reply("a", 200, "{\"from\":\"a\"}");
        downstream.reply("b", 202, "plain text");

        ProxyResponse response = executor.execute(workflow(List.of(), async("a", "b")), inbound("{\"in\":0}"));

        assertThat(response.status()).isEqualTo(200);
        JsonNode results = jsonMapper.readTree(response.body()).get("results");
        assertThat(results.get("a").get("status").asInt()).isEqualTo(200);
        assertThat(results.get("a").get("body").get("from").asString()).isEqualTo("a");
        assertThat(results.get("b").get("status").asInt()).isEqualTo(202);
        assertThat(results.get("b").get("body").asString()).isEqualTo("plain text");
        assertThat(downstream.received("a")).isEqualTo("{\"in\":0}");
        assertThat(downstream.received("b")).isEqualTo("{\"in\":0}");
    }

    @Test
    void syncThenAsyncPassesTheLastSyncResponseToEveryAsyncEndpoint() {
        downstream.reply("first", 200, "{\"step\":1}");
        downstream.reply("second", 200, "{\"step\":2}");
        downstream.reply("a", 200, "{\"from\":\"a\"}");
        downstream.reply("b", 200, "{\"from\":\"b\"}");

        ProxyResponse response = executor.execute(workflow(sync("first", "second"), async("a", "b")),
                inbound("{\"in\":0}"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(downstream.calls.subList(0, 2)).containsExactly("first", "second");
        assertThat(downstream.received("a")).isEqualTo("{\"step\":2}");
        assertThat(downstream.received("b")).isEqualTo("{\"step\":2}");
        JsonNode results = jsonMapper.readTree(response.body()).get("results");
        assertThat(results.propertyNames()).containsExactly("a", "b");
    }

    @Test
    void syncFailureMidChainStopsTheWorkflowWith502() {
        downstream.reply("first", 200, "{\"step\":1}");
        downstream.reply("second", 500, "boom");
        downstream.reply("third", 200, "{}");
        downstream.reply("a", 200, "{}");

        ProxyResponse response = executor.execute(workflow(sync("first", "second", "third"), async("a")),
                inbound("{}"));

        assertThat(response.status()).isEqualTo(502);
        JsonNode error = jsonMapper.readTree(response.body());
        assertThat(error.get("endpoint").asString()).isEqualTo("second");
        assertThat(error.get("downstreamStatus").asInt()).isEqualTo(500);
        assertThat(error.get("reason").asString()).isEqualTo("failure");
        assertThat(downstream.calls).containsExactly("first", "second");
    }

    @Test
    void syncConnectionFailureReturns502WithoutDownstreamStatus() {
        downstream.fail("first", new ConnectException("refused"));

        ProxyResponse response = executor.execute(workflow(sync("first"), List.of()), inbound("{}"));

        assertThat(response.status()).isEqualTo(502);
        JsonNode error = jsonMapper.readTree(response.body());
        assertThat(error.get("downstreamStatus").isNull()).isTrue();
        assertThat(error.get("detail").asString()).contains("refused");
    }

    @Test
    void asyncPartialFailureReturns200WithTheErrorInTheMergedResponse() {
        downstream.reply("a", 200, "{\"from\":\"a\"}");
        downstream.reply("b", 503, "unavailable");

        ProxyResponse response = executor.execute(
                workflow(List.of(), async("a", "b"), AsyncFailureMode.PARTIAL), inbound("{}"));

        assertThat(response.status()).isEqualTo(200);
        JsonNode results = jsonMapper.readTree(response.body()).get("results");
        assertThat(results.get("a").get("body").get("from").asString()).isEqualTo("a");
        assertThat(results.get("b").get("status").asInt()).isEqualTo(503);
        assertThat(results.get("b").get("error").asString()).contains("503");
        assertThat(results.get("b").has("body")).isFalse();
    }

    @Test
    void asyncFailureInFailModeReturns502() {
        downstream.reply("a", 200, "{\"from\":\"a\"}");
        downstream.reply("b", 503, "unavailable");

        ProxyResponse response = executor.execute(
                workflow(List.of(), async("a", "b"), AsyncFailureMode.FAIL), inbound("{}"));

        assertThat(response.status()).isEqualTo(502);
        JsonNode body = jsonMapper.readTree(response.body());
        assertThat(body.get("failedEndpoints").get(0).asString()).isEqualTo("b");
        assertThat(body.get("results").get("a").get("status").asInt()).isEqualTo(200);
    }

    @Test
    void syncTimeoutReturns502WithTimeoutReason() {
        downstream.reply("slow", 200, "{}");
        downstream.delay("slow", Duration.ofSeconds(5));

        long started = System.nanoTime();
        ProxyResponse response = executor.execute(
                workflow(List.of(endpoint("slow", Duration.ofMillis(200))), List.of()), inbound("{}"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.status()).isEqualTo(502);
        assertThat(jsonMapper.readTree(response.body()).get("reason").asString()).isEqualTo("timeout");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void asyncTimeoutIsReportedPerEndpoint() {
        downstream.reply("fast", 200, "{}");
        downstream.reply("slow", 200, "{}");
        downstream.delay("slow", Duration.ofSeconds(5));

        ProxyResponse response = executor.execute(workflow(List.of(),
                List.of(endpoint("fast", Duration.ofSeconds(2)), endpoint("slow", Duration.ofMillis(200)))),
                inbound("{}"));

        assertThat(response.status()).isEqualTo(200);
        JsonNode results = jsonMapper.readTree(response.body()).get("results");
        assertThat(results.get("fast").get("status").asInt()).isEqualTo(200);
        assertThat(results.get("slow").get("status").isNull()).isTrue();
        assertThat(results.get("slow").get("error").asString()).contains("Timed out");
    }

    @Test
    void asyncEndpointsRunConcurrently() {
        for (String name : List.of("a", "b", "c", "d")) {
            downstream.reply(name, 200, "{}");
            downstream.delay(name, Duration.ofMillis(400));
        }

        long started = System.nanoTime();
        ProxyResponse response = executor.execute(workflow(List.of(), async("a", "b", "c", "d")), inbound("{}"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.status()).isEqualTo(200);
        // Sequential execution would take 1600 ms; concurrent execution takes about 400 ms.
        assertThat(elapsed).isBetween(Duration.ofMillis(400), Duration.ofMillis(1000));
    }

    @Test
    void bodilessMethodsSendNoBodyAndInheritUsesTheInboundMethod() {
        downstream.reply("backend", 200, "{}");
        Endpoint inherit = new Endpoint("backend", "http://host/backend/", null, Duration.ofSeconds(1), Map.of(),
                List.of(), true);
        ProxyRequest get = new ProxyRequest(HttpMethod.GET, "/api/things/1", "a=1&b=2", new HttpHeaders(), null,
                "ignored".getBytes(StandardCharsets.UTF_8), "corr-1");

        executor.execute(workflow(List.of(inherit), List.of()), get);

        DownstreamRequest sent = downstream.requests.get("backend");
        assertThat(sent.method()).isEqualTo(HttpMethod.GET);
        assertThat(sent.body()).isNull();
        assertThat(sent.uri().toString()).isEqualTo("http://host/backend/api/things/1?a=1&b=2");
        assertThat(sent.headers().getFirst("X-Correlation-Id")).isEqualTo("corr-1");
    }

    @Test
    void staticAndForwardedHeadersAreSent() {
        downstream.reply("backend", 200, "{}");
        Endpoint endpoint = new Endpoint("backend", "http://host/backend", HttpMethod.POST, Duration.ofSeconds(1),
                Map.of("X-Static", "fixed"), List.of("Authorization", "X-Absent"), false);
        HttpHeaders inboundHeaders = new HttpHeaders();
        inboundHeaders.set("Authorization", "Bearer token");
        inboundHeaders.set("X-Not-Listed", "secret");
        ProxyRequest request = new ProxyRequest(HttpMethod.POST, "/x", null, inboundHeaders,
                MediaType.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8), "corr-2");

        executor.execute(workflow(List.of(endpoint), List.of()), request);

        HttpHeaders sent = downstream.requests.get("backend").headers();
        assertThat(sent.getFirst("X-Static")).isEqualTo("fixed");
        assertThat(sent.getFirst("Authorization")).isEqualTo("Bearer token");
        assertThat(sent.containsHeader("X-Not-Listed")).isFalse();
        assertThat(sent.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    private static List<Endpoint> sync(String... names) {
        return endpoints(names);
    }

    private static List<Endpoint> async(String... names) {
        return endpoints(names);
    }

    private static List<Endpoint> endpoints(String... names) {
        return java.util.Arrays.stream(names).map(name -> endpoint(name, Duration.ofSeconds(2))).toList();
    }

    private static Endpoint endpoint(String name, Duration timeout) {
        return new Endpoint(name, "http://host/" + name, HttpMethod.POST, timeout, Map.of(), List.of(), false);
    }

    private static Workflow workflow(List<Endpoint> sync, List<Endpoint> async) {
        return workflow(sync, async, AsyncFailureMode.PARTIAL);
    }

    private static Workflow workflow(List<Endpoint> sync, List<Endpoint> async, AsyncFailureMode mode) {
        return new Workflow("test-flow", PathPatternParser.defaultInstance.parse("/**"), Set.of(), sync, async, mode);
    }

    private static ProxyRequest inbound(String body) {
        return new ProxyRequest(HttpMethod.POST, "/api/test", null, new HttpHeaders(), MediaType.APPLICATION_JSON,
                body.getBytes(StandardCharsets.UTF_8), "corr");
    }

    private static String text(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Fake downstream keyed by the last path segment of the endpoint URL. */
    private static final class FakeDownstream implements DownstreamClient {

        private final Map<String, DownstreamResponse> replies = new ConcurrentHashMap<>();

        private final Map<String, Exception> failures = new ConcurrentHashMap<>();

        private final Map<String, Duration> delays = new ConcurrentHashMap<>();

        private final Map<String, DownstreamRequest> requests = new ConcurrentHashMap<>();

        private final List<String> calls = new CopyOnWriteArrayList<>();

        void reply(String name, int status, String body) {
            replies.put(name, new DownstreamResponse(status, MediaType.APPLICATION_JSON,
                    body.getBytes(StandardCharsets.UTF_8)));
        }

        void fail(String name, Exception exception) {
            failures.put(name, exception);
        }

        void delay(String name, Duration delay) {
            delays.put(name, delay);
        }

        String received(String name) {
            byte[] body = requests.get(name).body();
            return body == null ? null : new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public DownstreamResponse exchange(DownstreamRequest request) throws Exception {
            String path = request.uri().getPath();
            String name = path.split("/")[1];
            calls.add(name);
            requests.put(name, request);
            Duration delay = delays.get(name);
            if (delay != null) {
                Thread.sleep(delay);
            }
            Exception failure = failures.get(name);
            if (failure != null) {
                throw failure;
            }
            return replies.get(name);
        }
    }
}
