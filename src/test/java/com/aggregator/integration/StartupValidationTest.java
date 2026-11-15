package com.aggregator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aggregator.config.ProxyConfiguration;
import com.aggregator.routing.InvalidProxyConfigurationException;
import com.aggregator.routing.WorkflowRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves that invalid workflow configuration stops the application context from starting. */
class StartupValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProxyConfiguration.class, WorkflowRegistry.class);

    @Test
    void startsWithAValidConfiguration() {
        runner.withPropertyValues(
                        "proxy.workflows.orders.match.path=/api/orders/**",
                        "proxy.workflows.orders.match.methods=POST",
                        "proxy.workflows.orders.async[0].name=billing",
                        "proxy.workflows.orders.async[0].url=http://billing/charge",
                        "proxy.workflows.orders.async[0].method=POST",
                        "proxy.workflows.orders.on-async-failure=fail",
                        "proxy.workflows.catch-all.match.path=/**",
                        "proxy.workflows.catch-all.sync[0].name=backend",
                        "proxy.workflows.catch-all.sync[0].url=http://backend",
                        "proxy.workflows.catch-all.sync[0].method=INHERIT",
                        "proxy.workflows.catch-all.sync[0].append-inbound-path=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(WorkflowRegistry.class).workflows()).hasSize(2);
                });
    }

    @Test
    void failsToStartWithoutACatchAll() {
        runner.withPropertyValues(
                        "proxy.workflows.orders.match.path=/api/orders/**",
                        "proxy.workflows.orders.sync[0].name=validate",
                        "proxy.workflows.orders.sync[0].url=http://validator/validate",
                        "proxy.workflows.orders.sync[0].method=POST")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(InvalidProxyConfigurationException.class)
                        .rootCause().hasMessageContaining("catch-all workflow is mandatory"));
    }

    @Test
    void failsToStartWhenTwoWorkflowsShareAPathAndMethod() {
        runner.withPropertyValues(
                        "proxy.workflows.a.match.path=/api/items",
                        "proxy.workflows.a.match.methods=GET,POST",
                        "proxy.workflows.a.sync[0].name=one",
                        "proxy.workflows.a.sync[0].url=http://one",
                        "proxy.workflows.a.sync[0].method=POST",
                        "proxy.workflows.b.match.path=/api/items",
                        "proxy.workflows.b.match.methods=POST",
                        "proxy.workflows.b.sync[0].name=two",
                        "proxy.workflows.b.sync[0].url=http://two",
                        "proxy.workflows.b.sync[0].method=POST",
                        "proxy.workflows.catch-all.match.path=/**",
                        "proxy.workflows.catch-all.sync[0].name=backend",
                        "proxy.workflows.catch-all.sync[0].url=http://backend",
                        "proxy.workflows.catch-all.sync[0].method=INHERIT")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(InvalidProxyConfigurationException.class)
                        .rootCause().hasMessageContaining("overlapping HTTP method"));
    }

    @Test
    void failsToStartWithAnEmptyWorkflow() {
        runner.withPropertyValues("proxy.workflows.catch-all.match.path=/**")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(InvalidProxyConfigurationException.class));
    }
}
