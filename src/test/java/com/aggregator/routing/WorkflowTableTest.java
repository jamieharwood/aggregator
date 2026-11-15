package com.aggregator.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aggregator.routing.WorkflowDefinition.EndpointDefinition;
import com.aggregator.routing.WorkflowDefinition.MatchDefinition;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;

class WorkflowTableTest {

    @Test
    void specificWorkflowWinsOverCatchAll() {
        WorkflowTable table = table(Map.of(
                "orders", workflow("/api/orders/**", List.of("POST")),
                "catch-all", workflow("/**", List.of())));

        assertThat(table.resolve("/api/orders/42", HttpMethod.POST).name()).isEqualTo("orders");
    }

    @Test
    void unmatchedPathOrMethodGoesToCatchAll() {
        WorkflowTable table = table(Map.of(
                "orders", workflow("/api/orders/**", List.of("POST")),
                "catch-all", workflow("/**", List.of())));

        assertThat(table.resolve("/something/else", HttpMethod.POST).name()).isEqualTo("catch-all");
        assertThat(table.resolve("/api/orders/42", HttpMethod.GET).name()).isEqualTo("catch-all");
    }

    @Test
    void mostSpecificPatternWinsWhateverTheDeclarationOrder() {
        Map<String, WorkflowDefinition> workflows = new LinkedHashMap<>();
        workflows.put("catch-all", workflow("/**", List.of()));
        workflows.put("api", workflow("/api/**", List.of()));
        workflows.put("orders", workflow("/api/orders/**", List.of()));
        workflows.put("order-status", workflow("/api/orders/{id}/status", List.of()));
        WorkflowTable table = table(workflows);

        assertThat(table.resolve("/api/orders/42/status", HttpMethod.GET).name()).isEqualTo("order-status");
        assertThat(table.resolve("/api/orders/42", HttpMethod.GET).name()).isEqualTo("orders");
        assertThat(table.resolve("/api/customers", HttpMethod.GET).name()).isEqualTo("api");
        assertThat(table.resolve("/other", HttpMethod.GET).name()).isEqualTo("catch-all");
    }

    @Test
    void samePathWithDisjointMethodsIsAllowed() {
        WorkflowTable table = table(Map.of(
                "read", workflow("/api/items", List.of("GET")),
                "write", workflow("/api/items", List.of("POST", "PUT")),
                "catch-all", workflow("/**", List.of())));

        assertThat(table.resolve("/api/items", HttpMethod.GET).name()).isEqualTo("read");
        assertThat(table.resolve("/api/items", HttpMethod.PUT).name()).isEqualTo("write");
        assertThat(table.resolve("/api/items", HttpMethod.DELETE).name()).isEqualTo("catch-all");
    }

    @Test
    void missingCatchAllIsRejected() {
        assertThatThrownBy(() -> table(Map.of("orders", workflow("/api/orders/**", List.of("POST")))))
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .hasMessageContaining("catch-all workflow is mandatory");
    }

    @Test
    void catchAllRestrictedToSomeMethodsDoesNotCount() {
        assertThatThrownBy(() -> table(Map.of("almost", workflow("/**", List.of("GET")))))
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .hasMessageContaining("catch-all workflow is mandatory");
    }

    @Test
    void samePathWithOverlappingMethodIsRejected() {
        assertThatThrownBy(() -> table(Map.of(
                "a", workflow("/api/items", List.of("GET", "POST")),
                "b", workflow("/api/items", List.of("POST")),
                "catch-all", workflow("/**", List.of()))))
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .hasMessageContaining("overlapping HTTP method");
    }

    @Test
    void samePathWithUnrestrictedMethodsIsRejected() {
        assertThatThrownBy(() -> table(Map.of(
                "a", workflow("/api/items", List.of()),
                "b", workflow("/api/items", List.of("POST")),
                "catch-all", workflow("/**", List.of()))))
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .hasMessageContaining("overlapping HTTP method");
    }

    @Test
    void workflowWithoutEndpointsIsRejected() {
        WorkflowDefinition empty = new WorkflowDefinition(new MatchDefinition("/**", List.of()), List.of(),
                List.of(), null);

        assertThatThrownBy(() -> table(Map.of("catch-all", empty)))
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .hasMessageContaining("at least one endpoint");
    }

    @Test
    void duplicateEndpointNameAcrossStagesIsRejected() {
        WorkflowDefinition duplicate = new WorkflowDefinition(new MatchDefinition("/**", List.of()),
                List.of(endpoint("same")), List.of(endpoint("same")), null);

        assertThatThrownBy(() -> table(Map.of("catch-all", duplicate)))
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .hasMessageContaining("used more than once");
    }

    @Test
    void invalidUrlAndMethodAreRejected() {
        WorkflowDefinition badUrl = new WorkflowDefinition(new MatchDefinition("/**", List.of()),
                List.of(new EndpointDefinition("a", "not-a-url", "POST", null, null, null, false)), null, null);
        WorkflowDefinition badMethod = new WorkflowDefinition(new MatchDefinition("/**", List.of()),
                List.of(new EndpointDefinition("a", "http://host/a", "FETCH", null, null, null, false)), null, null);

        assertThatThrownBy(() -> table(Map.of("catch-all", badUrl)))
                .isInstanceOf(InvalidWorkflowDefinitionException.class).hasMessageContaining("url");
        assertThatThrownBy(() -> table(Map.of("catch-all", badMethod)))
                .isInstanceOf(InvalidWorkflowDefinitionException.class).hasMessageContaining("FETCH");
    }

    @Test
    void inheritMethodAndDefaultsAreApplied() {
        WorkflowTable table = table(Map.of("catch-all", new WorkflowDefinition(
                new MatchDefinition("/**", null),
                List.of(new EndpointDefinition("backend", "http://host", "inherit", null, null, null, true)),
                null, null)));

        Endpoint endpoint = table.resolve("/x", HttpMethod.GET).sync().getFirst();
        assertThat(endpoint.inheritsMethod()).isTrue();
        assertThat(endpoint.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(endpoint.appendInboundPath()).isTrue();
    }

    private static WorkflowTable table(Map<String, WorkflowDefinition> workflows) {
        return new WorkflowTable(workflows);
    }

    private static WorkflowDefinition workflow(String path, List<String> methods) {
        return new WorkflowDefinition(new MatchDefinition(path, methods), List.of(endpoint("backend")), List.of(),
                null);
    }

    private static EndpointDefinition endpoint(String name) {
        return new EndpointDefinition(name, "http://host/" + name, "POST", Duration.ofSeconds(1), null, null,
                false);
    }
}
