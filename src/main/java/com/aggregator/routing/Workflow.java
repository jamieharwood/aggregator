package com.aggregator.routing;

import java.util.List;
import java.util.Set;

import com.aggregator.config.ProxyProperties.AsyncFailureMode;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;

/**
 * A validated workflow.
 *
 * @param methods the HTTP methods this workflow matches; empty means all methods
 */
public record Workflow(
        String name,
        PathPattern pattern,
        Set<HttpMethod> methods,
        List<Endpoint> sync,
        List<Endpoint> async,
        AsyncFailureMode onAsyncFailure) {

    public boolean matchesMethod(HttpMethod method) {
        return methods.isEmpty() || methods.contains(method);
    }
}
