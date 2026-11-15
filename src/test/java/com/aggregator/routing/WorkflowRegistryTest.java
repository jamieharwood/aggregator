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

class WorkflowRegistryTest {

    private final StubRepository repository = new StubRepository();

    @Test
    void invalidDefinitionsAtStartupAreRejected() {
        repository.definitions = Map.of("orders", workflow("/api/orders/**"));

        assertThatThrownBy(() -> new WorkflowRegistry(repository))
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .hasMessageContaining("catch-all workflow is mandatory");
    }

    @Test
    void anEmptyStoreAtStartupIsRejected() {
        repository.definitions = Map.of();

        assertThatThrownBy(() -> new WorkflowRegistry(repository))
                .isInstanceOf(InvalidWorkflowDefinitionException.class);
    }

    @Test
    void refreshPicksUpAddedChangedAndRemovedWorkflows() {
        repository.definitions = Map.of("catch-all", workflow("/**"));
        WorkflowRegistry registry = new WorkflowRegistry(repository);
        assertThat(registry.resolve("/api/orders/1", HttpMethod.POST).name()).isEqualTo("catch-all");

        repository.definitions = Map.of("catch-all", workflow("/**"), "orders", workflow("/api/orders/**"));
        assertThat(registry.refresh()).isTrue();
        assertThat(registry.resolve("/api/orders/1", HttpMethod.POST).name()).isEqualTo("orders");

        repository.definitions = Map.of("catch-all", workflow("/**"), "orders", workflow("/api/v2/orders/**"));
        assertThat(registry.refresh()).isTrue();
        assertThat(registry.resolve("/api/orders/1", HttpMethod.POST).name()).isEqualTo("catch-all");
        assertThat(registry.resolve("/api/v2/orders/1", HttpMethod.POST).name()).isEqualTo("orders");

        repository.definitions = Map.of("catch-all", workflow("/**"));
        assertThat(registry.refresh()).isTrue();
        assertThat(registry.workflows()).hasSize(1);
    }

    @Test
    void refreshWithoutChangesReportsNoChange() {
        repository.definitions = new LinkedHashMap<>(Map.of("catch-all", workflow("/**")));
        WorkflowRegistry registry = new WorkflowRegistry(repository);

        repository.definitions = new LinkedHashMap<>(Map.of("catch-all", workflow("/**")));

        assertThat(registry.refresh()).isFalse();
    }

    @Test
    void invalidDefinitionsOnRefreshKeepThePreviousWorkflows() {
        repository.definitions = Map.of("catch-all", workflow("/**"), "orders", workflow("/api/orders/**"));
        WorkflowRegistry registry = new WorkflowRegistry(repository);

        repository.definitions = Map.of("orders", workflow("/api/orders/**"));

        assertThat(registry.refresh()).isFalse();
        assertThat(registry.workflows()).hasSize(2);
        assertThat(registry.resolve("/other", HttpMethod.GET).name()).isEqualTo("catch-all");
    }

    @Test
    void aStoreFailureOnRefreshKeepsThePreviousWorkflows() {
        repository.definitions = Map.of("catch-all", workflow("/**"));
        WorkflowRegistry registry = new WorkflowRegistry(repository);

        repository.failure = new IllegalStateException("store unavailable");

        assertThat(registry.refresh()).isFalse();
        assertThat(registry.resolve("/other", HttpMethod.GET).name()).isEqualTo("catch-all");
    }

    private static WorkflowDefinition workflow(String path) {
        return new WorkflowDefinition(new MatchDefinition(path, List.of()),
                List.of(new EndpointDefinition("backend", "http://host/backend", "POST", Duration.ofSeconds(1), null,
                        null, false)),
                List.of(), null);
    }

    private static final class StubRepository implements WorkflowDefinitionRepository {

        private Map<String, WorkflowDefinition> definitions = Map.of();

        private RuntimeException failure;

        @Override
        public Map<String, WorkflowDefinition> findAll() {
            if (failure != null) {
                throw failure;
            }
            return definitions;
        }
    }
}
