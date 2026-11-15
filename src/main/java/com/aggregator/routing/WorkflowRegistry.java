package com.aggregator.routing;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.aggregator.config.ProxyProperties;
import com.aggregator.config.ProxyProperties.EndpointProperties;
import com.aggregator.config.ProxyProperties.WorkflowProperties;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Validates the configured workflows at startup and resolves the workflow for an inbound request.
 * The most specific matching path pattern wins; the mandatory catch-all ({@code /**}) is always
 * the least specific, so every request resolves to a workflow.
 */
@Component
public class WorkflowRegistry {

    public static final String CATCH_ALL_PATH = "/**";

    private static final String INHERIT = "INHERIT";

    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    /** Sorted most specific first. */
    private final List<Workflow> workflows;

    public WorkflowRegistry(ProxyProperties properties) {
        List<Workflow> parsed = new ArrayList<>();
        properties.workflows().forEach((name, workflow) -> parsed.add(toWorkflow(name, workflow)));
        validateNoOverlaps(parsed);
        validateCatchAll(parsed);
        // Stable sort: equally specific patterns keep their declaration order.
        parsed.sort((a, b) -> PathPattern.SPECIFICITY_COMPARATOR.compare(a.pattern(), b.pattern()));
        this.workflows = List.copyOf(parsed);
    }

    public Workflow resolve(String path, HttpMethod method) {
        PathContainer container = PathContainer.parsePath(path);
        for (Workflow workflow : workflows) {
            if (workflow.matchesMethod(method) && workflow.pattern().matches(container)) {
                return workflow;
            }
        }
        // Unreachable: the catch-all matches every path and method.
        throw new IllegalStateException("No workflow matched " + method + " " + path);
    }

    public List<Workflow> workflows() {
        return workflows;
    }

    private static Workflow toWorkflow(String name, WorkflowProperties properties) {
        String prefix = "proxy.workflows." + name;
        if (properties.match() == null || isBlank(properties.match().path())) {
            throw new InvalidProxyConfigurationException(prefix + ".match.path is required");
        }
        String path = properties.match().path().trim();
        PathPattern pattern;
        try {
            pattern = PathPatternParser.defaultInstance.parse(path);
        } catch (RuntimeException ex) {
            throw new InvalidProxyConfigurationException(
                    prefix + ".match.path '" + path + "' is not a valid path pattern: " + ex.getMessage());
        }
        Set<HttpMethod> methods = new LinkedHashSet<>();
        for (String method : properties.match().methods()) {
            methods.add(parseMethod(prefix + ".match.methods", method));
        }
        if (properties.sync().isEmpty() && properties.async().isEmpty()) {
            throw new InvalidProxyConfigurationException(
                    prefix + " must define at least one endpoint in sync or async");
        }
        Set<String> names = new HashSet<>();
        List<Endpoint> sync = toEndpoints(prefix + ".sync", properties.sync(), names);
        List<Endpoint> async = toEndpoints(prefix + ".async", properties.async(), names);
        return new Workflow(name, pattern, Collections.unmodifiableSet(methods), sync, async,
                properties.onAsyncFailure());
    }

    private static List<Endpoint> toEndpoints(String prefix, List<EndpointProperties> endpoints, Set<String> names) {
        List<Endpoint> result = new ArrayList<>();
        for (int i = 0; i < endpoints.size(); i++) {
            EndpointProperties endpoint = endpoints.get(i);
            String at = prefix + "[" + i + "]";
            if (isBlank(endpoint.name())) {
                throw new InvalidProxyConfigurationException(at + ".name is required");
            }
            if (!names.add(endpoint.name())) {
                throw new InvalidProxyConfigurationException(
                        at + ".name '" + endpoint.name() + "' is used more than once in the workflow");
            }
            validateUrl(at, endpoint.url());
            if (isBlank(endpoint.method())) {
                throw new InvalidProxyConfigurationException(at + ".method is required (an HTTP method or INHERIT)");
            }
            HttpMethod method = INHERIT.equalsIgnoreCase(endpoint.method().trim())
                    ? null
                    : parseMethod(at + ".method", endpoint.method());
            if (endpoint.timeout().isZero() || endpoint.timeout().isNegative()) {
                throw new InvalidProxyConfigurationException(at + ".timeout must be positive");
            }
            result.add(new Endpoint(endpoint.name(), endpoint.url().trim(), method, endpoint.timeout(),
                    Map.copyOf(endpoint.headers()), List.copyOf(endpoint.forwardHeaders()),
                    endpoint.appendInboundPath()));
        }
        return List.copyOf(result);
    }

    private static void validateUrl(String at, String url) {
        if (isBlank(url)) {
            throw new InvalidProxyConfigurationException(at + ".url is required");
        }
        try {
            URI uri = URI.create(url.trim());
            boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            if (!http || uri.getHost() == null) {
                throw new IllegalArgumentException("must be an absolute http or https URL");
            }
        } catch (IllegalArgumentException ex) {
            throw new InvalidProxyConfigurationException(at + ".url '" + url + "' is invalid: " + ex.getMessage());
        }
    }

    private static HttpMethod parseMethod(String at, String value) {
        String method = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(method)) {
            throw new InvalidProxyConfigurationException(at + " contains unsupported HTTP method '" + value + "'");
        }
        return HttpMethod.valueOf(method);
    }

    private static void validateNoOverlaps(List<Workflow> workflows) {
        for (int i = 0; i < workflows.size(); i++) {
            for (int j = i + 1; j < workflows.size(); j++) {
                Workflow a = workflows.get(i);
                Workflow b = workflows.get(j);
                if (a.pattern().getPatternString().equals(b.pattern().getPatternString()) && methodsOverlap(a, b)) {
                    throw new InvalidProxyConfigurationException("Workflows '" + a.name() + "' and '" + b.name()
                            + "' both match path '" + a.pattern().getPatternString()
                            + "' with an overlapping HTTP method");
                }
            }
        }
    }

    private static boolean methodsOverlap(Workflow a, Workflow b) {
        if (a.methods().isEmpty() || b.methods().isEmpty()) {
            return true;
        }
        return !Collections.disjoint(a.methods(), b.methods());
    }

    private static void validateCatchAll(List<Workflow> workflows) {
        boolean present = workflows.stream().anyMatch(workflow ->
                CATCH_ALL_PATH.equals(workflow.pattern().getPatternString()) && workflow.methods().isEmpty());
        if (!present) {
            throw new InvalidProxyConfigurationException("A catch-all workflow is mandatory: exactly one workflow "
                    + "must have match.path '" + CATCH_ALL_PATH + "' and no match.methods");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
