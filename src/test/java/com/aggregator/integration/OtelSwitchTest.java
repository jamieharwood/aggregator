package com.aggregator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves the {@code otel.enabled} switch in application.yml. The application is started the way it
 * starts in production, so Spring Boot's test-only export defaults do not apply.
 */
class OtelSwitchTest {

    @Test
    void exportIsOffByDefault() {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
            assertThat(otlpMeterRegistries(context)).isZero();
        }
    }

    @Test
    void exportIsOffWhenSwitchedOff() {
        try (ConfigurableApplicationContext context = start("--otel.enabled=false")) {
            assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
            assertThat(otlpMeterRegistries(context)).isZero();
        }
    }

    @Test
    void tracesAndMetricsAreExportedWhenSwitchedOn() {
        try (ConfigurableApplicationContext context = start("--otel.enabled=true")) {
            assertThat(context.getBeansOfType(SpanExporter.class)).hasSize(1);
            assertThat(otlpMeterRegistries(context)).isEqualTo(1);
        }
    }

    @Test
    void traceContextIsStillCreatedAndPropagatedWhenSwitchedOff() {
        try (ConfigurableApplicationContext context = start("--otel.enabled=false")) {
            Tracer tracer = context.getBean(Tracer.class);
            Propagator propagator = context.getBean(Propagator.class);
            var span = tracer.nextSpan().name("probe").start();
            try {
                assertThat(span.context().traceId()).hasSize(32).isNotEqualTo("0".repeat(32));

                Map<String, String> headers = new HashMap<>();
                propagator.inject(span.context(), headers, Map::put);
                assertThat(headers.get("traceparent")).startsWith("00-" + span.context().traceId() + "-");
            } finally {
                span.end();
            }
        }
    }

    private static long otlpMeterRegistries(ConfigurableApplicationContext context) {
        return context.getBeansOfType(MeterRegistry.class).values().stream()
                .filter(registry -> registry.getClass().getSimpleName().equals("OtlpMeterRegistry"))
                .count();
    }

    private static ConfigurableApplicationContext start(String... arguments) {
        String[] all = new String[arguments.length + 1];
        all[0] = "--spring.mongodb.uri=" + WorkflowStore.uri();
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return new SpringApplicationBuilder(com.aggregator.AggregatorApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run(all);
    }
}
