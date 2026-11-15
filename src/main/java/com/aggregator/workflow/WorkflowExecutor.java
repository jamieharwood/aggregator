package com.aggregator.workflow;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.aggregator.config.ProxyProperties.AsyncFailureMode;
import com.aggregator.routing.Endpoint;
import com.aggregator.routing.Workflow;
import com.aggregator.workflow.EndpointResult.Outcome;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * Runs a workflow: the synchronous endpoints in order, each fed the previous response, then the
 * asynchronous endpoints concurrently, all fed the same payload, and merges the asynchronous
 * responses into one.
 */
@Service
public class WorkflowExecutor {

    public static final String WORKFLOW_OBSERVATION = "proxy.workflow";

    public static final String STAGE_OBSERVATION = "proxy.workflow.stage";

    private static final int BAD_GATEWAY = 502;

    private final EndpointInvoker invoker;

    private final ExecutorService executor;

    private final ObservationRegistry observationRegistry;

    private final JsonMapper jsonMapper;

    private final ObjectReader strictReader;

    public WorkflowExecutor(EndpointInvoker invoker, ExecutorService proxyExecutor,
            ObservationRegistry observationRegistry, JsonMapper jsonMapper) {
        this.invoker = invoker;
        this.executor = proxyExecutor;
        this.observationRegistry = observationRegistry;
        this.jsonMapper = jsonMapper;
        this.strictReader = jsonMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public ProxyResponse execute(Workflow workflow, ProxyRequest inbound) {
        Observation observation = Observation.createNotStarted(WORKFLOW_OBSERVATION, observationRegistry)
                .contextualName("workflow " + workflow.name())
                .lowCardinalityKeyValue("workflow.name", workflow.name())
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            ProxyResponse response = run(workflow, inbound, observation);
            observation.lowCardinalityKeyValue("outcome", response.status() < 400 ? "success" : "failure");
            return response;
        } catch (RuntimeException ex) {
            observation.lowCardinalityKeyValue("outcome", "failure");
            observation.error(ex);
            throw ex;
        } finally {
            observation.stop();
        }
    }

    private ProxyResponse run(Workflow workflow, ProxyRequest inbound, Observation parent) {
        byte[] payload = inbound.body();
        MediaType payloadType = inbound.contentType();
        EndpointResult lastSync = null;

        if (!workflow.sync().isEmpty()) {
            Observation stage = startStage(workflow, Stage.SYNC, parent);
            boolean succeeded = false;
            // The stage must be the current observation of the calling thread, otherwise the
            // tracer parents endpoint spans to whatever span is current instead.
            try (Observation.Scope scope = stage.openScope()) {
                int position = 0;
                for (Endpoint endpoint : workflow.sync()) {
                    position++;
                    EndpointResult result = invoker.invoke(workflow, endpoint, Stage.SYNC, position, inbound,
                            payload, payloadType, stage);
                    if (!result.succeeded()) {
                        return syncFailure(workflow, result);
                    }
                    payload = result.body();
                    payloadType = result.contentType();
                    lastSync = result;
                }
                succeeded = true;
            } finally {
                stopStage(stage, succeeded);
            }
        }

        if (workflow.async().isEmpty()) {
            return new ProxyResponse(lastSync.status(), lastSync.contentType(), lastSync.body());
        }

        Observation stage = startStage(workflow, Stage.ASYNC, parent);
        List<EndpointResult> results = List.of();
        try {
            results = runAsync(workflow, inbound, payload, payloadType, stage);
        } finally {
            stopStage(stage, !results.isEmpty() && results.stream().allMatch(EndpointResult::succeeded));
        }
        return merge(workflow, results);
    }

    private List<EndpointResult> runAsync(Workflow workflow, ProxyRequest inbound, byte[] payload,
            MediaType payloadType, Observation stage) {
        List<Future<EndpointResult>> futures = new ArrayList<>();
        for (Endpoint endpoint : workflow.async()) {
            futures.add(executor.submit(() -> {
                // Make the stage current on the virtual thread so the call joins the request trace.
                try (Observation.Scope scope = stage.openScope()) {
                    return invoker.invoke(workflow, endpoint, Stage.ASYNC, 0, inbound, payload, payloadType,
                            stage);
                }
            }));
        }
        List<EndpointResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Endpoint endpoint = workflow.async().get(i);
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                results.add(new EndpointResult(endpoint, Outcome.FAILURE, null, null, null,
                        "Request failed: " + cause));
            } catch (InterruptedException ex) {
                futures.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for asynchronous endpoints", ex);
            }
        }
        return results;
    }

    private Observation startStage(Workflow workflow, Stage stage, Observation parent) {
        return Observation.createNotStarted(STAGE_OBSERVATION, observationRegistry)
                .contextualName("workflow." + stage.tag())
                .parentObservation(parent)
                .lowCardinalityKeyValue("workflow.name", workflow.name())
                .lowCardinalityKeyValue("stage", stage.tag())
                .start();
    }

    private static void stopStage(Observation stage, boolean succeeded) {
        stage.lowCardinalityKeyValue("outcome", succeeded ? "success" : "failure");
        stage.stop();
    }

    private ProxyResponse syncFailure(Workflow workflow, EndpointResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Synchronous endpoint failed");
        body.put("workflow", workflow.name());
        body.put("endpoint", result.endpoint().name());
        body.put("stage", Stage.SYNC.tag());
        body.put("reason", result.outcome().tag());
        body.put("downstreamStatus", result.status());
        body.put("detail", result.error());
        return json(BAD_GATEWAY, body);
    }

    private ProxyResponse merge(Workflow workflow, List<EndpointResult> results) {
        Map<String, Object> merged = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        for (EndpointResult result : results) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", result.status());
            if (result.succeeded()) {
                entry.put("body", toJsonValue(result.body()));
            } else {
                entry.put("error", result.error());
                failed.add(result.endpoint().name());
            }
            merged.put(result.endpoint().name(), entry);
        }
        if (!failed.isEmpty() && workflow.onAsyncFailure() == AsyncFailureMode.FAIL) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "One or more asynchronous endpoints failed");
            body.put("workflow", workflow.name());
            body.put("stage", Stage.ASYNC.tag());
            body.put("failedEndpoints", failed);
            body.put("results", merged);
            return json(BAD_GATEWAY, body);
        }
        return json(200, Map.of("results", merged));
    }

    /** Parses a downstream body as JSON; a body that is not JSON is carried as a string. */
    private Object toJsonValue(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return strictReader.readTree(body);
        } catch (JacksonException ex) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private ProxyResponse json(int status, Object body) {
        return new ProxyResponse(status, MediaType.APPLICATION_JSON, jsonMapper.writeValueAsBytes(body));
    }
}
