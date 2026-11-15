package com.aggregator.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Raw binding of the {@code proxy.*} configuration. Validation and conversion to the runtime
 * model happens in {@link com.aggregator.routing.WorkflowRegistry}.
 */
@ConfigurationProperties("proxy")
public record ProxyProperties(Map<String, WorkflowProperties> workflows) {

    public ProxyProperties {
        workflows = workflows == null ? Map.of() : workflows;
    }

    public record WorkflowProperties(
            MatchProperties match,
            List<EndpointProperties> sync,
            List<EndpointProperties> async,
            AsyncFailureMode onAsyncFailure) {

        public WorkflowProperties {
            sync = sync == null ? List.of() : sync;
            async = async == null ? List.of() : async;
            onAsyncFailure = onAsyncFailure == null ? AsyncFailureMode.PARTIAL : onAsyncFailure;
        }
    }

    public record MatchProperties(String path, List<String> methods) {

        public MatchProperties {
            methods = methods == null ? List.of() : methods;
        }
    }

    public record EndpointProperties(
            String name,
            String url,
            String method,
            Duration timeout,
            Map<String, String> headers,
            List<String> forwardHeaders,
            boolean appendInboundPath) {

        public EndpointProperties {
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
