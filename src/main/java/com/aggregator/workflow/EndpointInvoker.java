package com.aggregator.workflow;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.aggregator.routing.Endpoint;
import com.aggregator.routing.Workflow;
import com.aggregator.workflow.EndpointResult.Outcome;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.RequestReplySenderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Calls one downstream endpoint, bounded by the endpoint's timeout, and records one client
 * observation (span + timer) for it. Never throws: every failure becomes an {@link EndpointResult}.
 */
@Component
public class EndpointInvoker {

    public static final String OBSERVATION_NAME = "proxy.endpoint.call";

    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(EndpointInvoker.class);

    private static final Set<HttpMethod> BODILESS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.DELETE);

    /** Headers the HTTP client manages itself, or that this class sets explicitly. */
    private static final Set<String> NOT_FORWARDABLE =
            Set.of("host", "content-length", "content-type", "connection", "upgrade", "expect", "transfer-encoding");

    private final DownstreamClient client;

    private final ExecutorService executor;

    private final ObservationRegistry observationRegistry;

    public EndpointInvoker(DownstreamClient client, ExecutorService proxyExecutor,
            ObservationRegistry observationRegistry) {
        this.client = client;
        this.executor = proxyExecutor;
        this.observationRegistry = observationRegistry;
    }

    /**
     * @param position 1-based position within the synchronous stage, or 0 for asynchronous endpoints
     * @param body the payload to send: the inbound body or the previous synchronous response
     * @param parent the stage observation this call belongs to; the caller must also have it open
     *     as the current scope on the calling thread
     */
    public EndpointResult invoke(Workflow workflow, Endpoint endpoint, Stage stage, int position,
            ProxyRequest inbound, byte[] body, MediaType contentType, Observation parent) {
        HttpMethod method = endpoint.inheritsMethod() ? inbound.method() : endpoint.method();
        boolean sendBody = !BODILESS.contains(method) && body != null && body.length > 0;
        HttpHeaders headers = buildHeaders(endpoint, inbound, sendBody ? contentType : null);

        RequestReplySenderContext<HttpHeaders, DownstreamResponse> context =
                new RequestReplySenderContext<>((carrier, key, value) -> carrier.set(key, value));
        context.setCarrier(headers);
        context.setRemoteServiceName(endpoint.name());

        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, () -> context, observationRegistry)
                .contextualName("endpoint " + endpoint.name())
                .parentObservation(parent)
                .lowCardinalityKeyValue("workflow.name", workflow.name())
                .lowCardinalityKeyValue("endpoint.name", endpoint.name())
                .lowCardinalityKeyValue("stage", stage.tag())
                .lowCardinalityKeyValue("http.request.method", method.name());
        if (stage == Stage.SYNC) {
            observation.highCardinalityKeyValue("sync.position", Integer.toString(position));
        }

        long started = System.nanoTime();
        // Starting the observation injects the trace context (traceparent) into the headers.
        observation.start();
        EndpointResult result;
        try (Observation.Scope scope = observation.openScope()) {
            result = call(endpoint, method, inbound, headers, sendBody ? body : null, observation);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            log.info("Downstream call workflow={} endpoint={} stage={} status={} outcome={} durationMs={} "
                            + "correlationId={}",
                    workflow.name(), endpoint.name(), stage.tag(),
                    result.status() == null ? "none" : result.status(), result.outcome().tag(), durationMs,
                    inbound.correlationId());
        } finally {
            observation.stop();
        }
        return result;
    }

    private EndpointResult call(Endpoint endpoint, HttpMethod method, ProxyRequest inbound, HttpHeaders headers,
            byte[] body, Observation observation) {
        EndpointResult result;
        try {
            URI uri = targetUri(endpoint, inbound);
            observation.highCardinalityKeyValue("url.full", uri.toString());
            result = exchange(endpoint, new DownstreamRequest(method, uri, headers, body), observation);
        } catch (RuntimeException ex) {
            observation.error(ex);
            observation.event(Observation.Event.of("endpoint.failed"));
            result = failure(endpoint, Outcome.FAILURE, null, "Request failed: " + describe(ex));
        }
        observation.lowCardinalityKeyValue("outcome", result.outcome().tag());
        observation.lowCardinalityKeyValue("http.response.status_code",
                result.status() == null ? "none" : result.status().toString());
        return result;
    }

    private EndpointResult exchange(Endpoint endpoint, DownstreamRequest request, Observation observation) {
        Future<DownstreamResponse> future = executor.submit(() -> client.exchange(request));
        try {
            DownstreamResponse response = future.get(endpoint.timeout().toNanos(), TimeUnit.NANOSECONDS);
            if (response.status() >= 200 && response.status() < 300) {
                return new EndpointResult(endpoint, Outcome.SUCCESS, response.status(), response.contentType(),
                        response.body(), null);
            }
            String error = "Downstream returned status " + response.status();
            observation.error(new DownstreamFailureException(error));
            observation.event(Observation.Event.of("endpoint.failed"));
            return new EndpointResult(endpoint, Outcome.FAILURE, response.status(), response.contentType(),
                    response.body(), error);
        } catch (TimeoutException ex) {
            future.cancel(true);
            String error = "Timed out after " + endpoint.timeout().toMillis() + " ms";
            observation.error(new DownstreamFailureException(error));
            observation.event(Observation.Event.of("endpoint.timeout"));
            return failure(endpoint, Outcome.TIMEOUT, null, error);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            observation.error(cause);
            observation.event(Observation.Event.of("endpoint.failed"));
            return failure(endpoint, Outcome.FAILURE, null, "Request failed: " + describe(cause));
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            observation.error(ex);
            observation.event(Observation.Event.of("endpoint.failed"));
            return failure(endpoint, Outcome.FAILURE, null, "Interrupted while waiting for the response");
        }
    }

    private static EndpointResult failure(Endpoint endpoint, Outcome outcome, Integer status, String error) {
        return new EndpointResult(endpoint, outcome, status, null, null, error);
    }

    private static HttpHeaders buildHeaders(Endpoint endpoint, ProxyRequest inbound, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        for (String name : endpoint.forwardHeaders()) {
            if (NOT_FORWARDABLE.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<String> values = inbound.headers().get(name);
            if (values != null) {
                headers.put(name, List.copyOf(values));
            }
        }
        endpoint.headers().forEach(headers::set);
        headers.set(CORRELATION_HEADER, inbound.correlationId());
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        return headers;
    }

    static URI targetUri(Endpoint endpoint, ProxyRequest inbound) {
        if (!endpoint.appendInboundPath()) {
            return URI.create(endpoint.url());
        }
        StringBuilder target = new StringBuilder(endpoint.url());
        while (target.length() > 0 && target.charAt(target.length() - 1) == '/') {
            target.setLength(target.length() - 1);
        }
        target.append(inbound.path());
        if (inbound.query() != null && !inbound.query().isEmpty()) {
            target.append('?').append(inbound.query());
        }
        return URI.create(target.toString());
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName() + ": " + message;
    }

    /** Marks a span as failed when the downstream call produced no exception of its own. */
    static final class DownstreamFailureException extends RuntimeException {

        DownstreamFailureException(String message) {
            super(message, null, false, false);
        }
    }
}
