package com.aggregator.web;

import java.util.Collections;
import java.util.UUID;

import com.aggregator.routing.Workflow;
import com.aggregator.routing.WorkflowRegistry;
import com.aggregator.workflow.EndpointInvoker;
import com.aggregator.workflow.ProxyRequest;
import com.aggregator.workflow.ProxyResponse;
import com.aggregator.workflow.WorkflowExecutor;
import io.micrometer.common.KeyValue;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

/** Receives every inbound request, resolves its workflow and returns the workflow's response. */
@RestController
public class ProxyController {

    private static final String MDC_CORRELATION_ID = "correlationId";

    private final WorkflowRegistry registry;

    private final WorkflowExecutor executor;

    private final String actuatorBasePath;

    public ProxyController(WorkflowRegistry registry, WorkflowExecutor executor,
            @Value("${management.endpoints.web.base-path:/actuator}") String actuatorBasePath) {
        this.registry = registry;
        this.executor = executor;
        this.actuatorBasePath = actuatorBasePath;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();
        if (isActuatorPath(path)) {
            // Actuator endpoints are never proxied; an unknown one is simply not found.
            return ResponseEntity.notFound().build();
        }
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        Workflow workflow = registry.resolve(path, method);
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(context ->
                context.addHighCardinalityKeyValue(KeyValue.of("workflow.name", workflow.name())));

        String correlationId = request.getHeader(EndpointInvoker.CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        ProxyRequest inbound = new ProxyRequest(method, path, request.getQueryString(), headersOf(request),
                contentTypeOf(request), body == null ? new byte[0] : body, correlationId);

        MDC.put(MDC_CORRELATION_ID, correlationId);
        try {
            ProxyResponse response = executor.execute(workflow, inbound);
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                    .header(EndpointInvoker.CORRELATION_HEADER, correlationId);
            if (response.contentType() != null) {
                builder.contentType(response.contentType());
            }
            return builder.body(response.body());
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    private boolean isActuatorPath(String path) {
        return path.equals(actuatorBasePath) || path.startsWith(actuatorBasePath + "/");
    }

    private static HttpHeaders headersOf(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    private static MediaType contentTypeOf(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
