package com.aggregator.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aggregator.config.ProxyProperties;
import com.aggregator.config.ProxyProperties.EndpointProperties;
import com.aggregator.config.ProxyProperties.MatchProperties;
import com.aggregator.config.ProxyProperties.WorkflowProperties;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;

class WorkflowRegistryTest {

    @Test
    void specificWorkflowWinsOverCatchAll() {
        WorkflowRegistry registry = registry(Map.of(
                "orders", workflow("/api/orders/**", List.of("POST")),
                "catch-all", workflow("/**", List.of())));

        assertThat(registry.resolve("/api/orders/42", HttpMethod.POST).name()).isEqualTo("orders");
    }

    @Test
    void unmatchedPathOrMethodGoesToCatchAll() {
        WorkflowRegistry registry = registry(Map.of(
                "orders", workflow("/api/orders/**", List.of("POST")),
                "catch-all", workflow("/**", List.of())));

        assertThat(registry.resolve("/something/else", HttpMethod.POST).name()).isEqualTo("catch-all");
        assertThat(registry.resolve("/api/orders/42", HttpMethod.GET).name()).isEqualTo("catch-all");
    }

    @Test
    void mostSpecificPatternWinsWhateverTheDeclarationOrder() {
        Map<String, WorkflowProperties> workflows = new LinkedHashMap<>();
        workflows.put("catch-all", workflow("/**", List.of()));
        workflows.put("api", workflow("/api/**", List.of()));
        workflows.put("orders", workflow("/api/orders/**", List.of()));
        workflows.put("order-status", workflow("/api/orders/{id}/status", List.of()));
        WorkflowRegistry registry = registry(workflows);

        assertThat(registry.resolve("/api/orders/42/status", HttpMethod.GET).name()).isEqualTo("order-status");
        assertThat(registry.resolve("/api/orders/42", HttpMethod.GET).name()).isEqualTo("orders");
        assertThat(registry.resolve("/api/customers", HttpMethod.GET).name()).isEqualTo("api");
        assertThat(registry.resolve("/other", HttpMethod.GET).name()).isEqualTo("catch-all");
    }

    @Test
    void samePathWithDisjointMethodsIsAllowed() {
        WorkflowRegistry registry = registry(Map.of(
                "read", workflow("/api/items", List.of("GET")),
                "write", workflow("/api/items", List.of("POST", "PUT")),
                "catch-all", workflow("/**", List.of())));

        assertThat(registry.resolve("/api/items", HttpMethod.GET).name()).isEqualTo("read");
        assertThat(registry.resolve("/api/items", HttpMethod.PUT).name()).isEqualTo("write");
        assertThat(registry.resolve("/api/items", HttpMethod.DELETE).name()).isEqualTo("catch-all");
    }

    @Test
    void missingCatchAllIsRejected() {
        assertThatThrownBy(() -> registry(Map.of("orders", workflow("/api/orders/**", List.of("POST")))))
                .isInstanceOf(InvalidProxyConfigurationException.class)
                .hasMessageContaining("catch-all workflow is mandatory");
    }

    @Test
    void catchAllRestrictedToSomeMethodsDoesNotCount() {
        assertThatThrownBy(() -> registry(Map.of("almost", workflow("/**", List.of("GET")))))
                .isInstanceOf(InvalidProxyConfigurationException.class)
                .hasMessageContaining("catch-all workflow is mandatory");
    }

    @Test
    void samePathWithOverlappingMethodIsRejected() {
        assertThatThrownBy(() -> registry(Map.of(
                "a", workflow("/api/items", List.of("GET", "POST")),
                "b", workflow("/api/items", List.of("POST")),
                "catch-all", workflow("/**", List.of()))))
                .isInstanceOf(InvalidProxyConfigurationException.class)
                .hasMessageContaining("overlapping HTTP method");
    }

    @Test
    void samePathWithUnrestrictedMethodsIsRejected() {
        assertThatThrownBy(() -> registry(Map.of(
                "a", workflow("/api/items", List.of()),
                "b", workflow("/api/items", List.of("POST")),
                "catch-all", workflow("/**", List.of()))))
                .isInstanceOf(InvalidProxyConfigurationException.class)
                .hasMessageContaining("overlapping HTTP method");
    }

    @Test
    void workflowWithoutEndpointsIsRejected() {
        WorkflowProperties empty = new WorkflowProperties(new MatchProperties("/**", List.of()), List.of(),
                List.of(), null);

        assertThatThrownBy(() -> registry(Map.of("catch-all", empty)))
                .isInstanceOf(InvalidProxyConfigurationException.class)
                .hasMessageContaining("at least one endpoint");
    }

    @Test
    void duplicateEndpointNameAcrossStagesIsRejected() {
        WorkflowProperties duplicate = new WorkflowProperties(new MatchProperties("/**", List.of()),
                List.of(endpoint("same")), List.of(endpoint("same")), null);

        assertThatThrownBy(() -> registry(Map.of("catch-all", duplicate)))
                .isInstanceOf(InvalidProxyConfigurationException.class)
                .hasMessageContaining("used more than once");
    }

    @Test
    void invalidUrlAndMethodAreRejected() {
        WorkflowProperties badUrl = new WorkflowProperties(new MatchProperties("/**", List.of()),
                List.of(new EndpointProperties("a", "not-a-url", "POST", null, null, null, false)), null, null);
        WorkflowProperties badMethod = new WorkflowProperties(new MatchProperties("/**", List.of()),
                List.of(new EndpointProperties("a", "http://host/a", "FETCH", null, null, null, false)), null, null);

        assertThatThrownBy(() -> registry(Map.of("catch-all", badUrl)))
                .isInstanceOf(InvalidProxyConfigurationException.class).hasMessageContaining("url");
        assertThatThrownBy(() -> registry(Map.of("catch-all", badMethod)))
                .isInstanceOf(InvalidProxyConfigurationException.class).hasMessageContaining("FETCH");
    }

    @Test
    void inheritMethodAndDefaultsAreApplied() {
        WorkflowRegistry registry = registry(Map.of("catch-all", new WorkflowProperties(
                new MatchProperties("/**", null),
                List.of(new EndpointProperties("backend", "http://host", "inherit", null, null, null, true)),
                null, null)));

        Endpoint endpoint = registry.resolve("/x", HttpMethod.GET).sync().getFirst();
        assertThat(endpoint.inheritsMethod()).isTrue();
        assertThat(endpoint.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(endpoint.appendInboundPath()).isTrue();
    }

    private static WorkflowRegistry registry(Map<String, WorkflowProperties> workflows) {
        return new WorkflowRegistry(new ProxyProperties(workflows));
    }

    private static WorkflowProperties workflow(String path, List<String> methods) {
        return new WorkflowProperties(new MatchProperties(path, methods), List.of(endpoint("backend")), List.of(),
                null);
    }

    private static EndpointProperties endpoint(String name) {
        return new EndpointProperties(name, "http://host/" + name, "POST", Duration.ofSeconds(1), null, null,
                false);
    }
}
