package com.aggregator.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.aggregator.config.ProxyProperties;
import com.aggregator.routing.WorkflowDefinition.EndpointDefinition;
import com.aggregator.routing.WorkflowDefinition.MatchDefinition;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;

class WorkflowRefresherTest {

    private final AtomicReference<Map<String, WorkflowDefinition>> stored =
            new AtomicReference<>(Map.of("catch-all", workflow("/**")));

    private final AtomicInteger reads = new AtomicInteger();

    private final WorkflowRegistry registry = new WorkflowRegistry(() -> {
        reads.incrementAndGet();
        return stored.get();
    });

    @Test
    void reloadsChangedWorkflowsOnTheConfiguredInterval() throws Exception {
        WorkflowRefresher refresher = refresher(Duration.ofMillis(50));
        refresher.afterPropertiesSet();
        try {
            stored.set(Map.of("catch-all", workflow("/**"), "orders", workflow("/api/orders/**")));

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (registry.workflows().size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            assertThat(registry.resolve("/api/orders/1", HttpMethod.GET).name()).isEqualTo("orders");
        } finally {
            refresher.destroy();
        }
    }

    @Test
    void aZeroIntervalDisablesReloading() throws Exception {
        WorkflowRefresher refresher = refresher(Duration.ZERO);
        refresher.afterPropertiesSet();
        try {
            int readsAfterStartup = reads.get();
            Thread.sleep(200);

            assertThat(reads.get()).isEqualTo(readsAfterStartup);
        } finally {
            refresher.destroy();
        }
    }

    private WorkflowRefresher refresher(Duration interval) {
        return new WorkflowRefresher(registry,
                new ProxyProperties(null, new ProxyProperties.WorkflowStore(null, interval)));
    }

    private static WorkflowDefinition workflow(String path) {
        return new WorkflowDefinition(new MatchDefinition(path, List.of()),
                List.of(new EndpointDefinition("backend", "http://host/backend", "POST", null, null, null, false)),
                List.of(), null);
    }
}
