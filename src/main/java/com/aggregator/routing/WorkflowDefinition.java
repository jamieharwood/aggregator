package com.aggregator.routing;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A workflow as stored, before validation. {@link WorkflowTable} validates definitions and turns
 * them into the runtime {@link Workflow} model.
 */
public record WorkflowDefinition(
        MatchDefinition match,
        List<EndpointDefinition> sync,
        List<EndpointDefinition> async,
        AsyncFailureMode onAsyncFailure) {

    public WorkflowDefinition {
        sync = sync == null ? List.of() : sync;
        async = async == null ? List.of() : async;
        onAsyncFailure = onAsyncFailure == null ? AsyncFailureMode.PARTIAL : onAsyncFailure;
    }

    public record MatchDefinition(String path, List<String> methods) {

        public MatchDefinition {
            methods = methods == null ? List.of() : methods;
        }
    }

    public record EndpointDefinition(
            String name,
            String url,
            String method,
            Duration timeout,
            Map<String, String> headers,
            List<String> forwardHeaders,
            boolean appendInboundPath) {

        public EndpointDefinition {
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
            headers = headers == null ? Map.of() : headers;
            forwardHeaders = forwardHeaders == null ? List.of() : forwardHeaders;
        }
    }

    public enum AsyncFailureMode {
        /** Return 200 with every endpoint in the merged response; failed ones carry an error. */
        PARTIAL,
        /** Return 502 if any asynchronous endpoint fails. */
        FAIL
    }
}
