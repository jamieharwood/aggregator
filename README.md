# aggregator

A configurable API proxy built with Java 25 and Spring Boot 4. Each inbound request is matched to a
named workflow, and the workflow calls one or more downstream endpoints:

1. **Synchronous stage** (optional): endpoints called one at a time, in the configured order. The
   first receives the inbound request body; each later one receives the previous endpoint's
   response body.
2. **Asynchronous stage** (optional): endpoints called concurrently on virtual threads. Each
   receives the same input: the last synchronous response, or the inbound body if there is no
   synchronous stage. Their responses are merged into one JSON response.

## Requirements

- JDK 25
- Nothing else: the Gradle wrapper downloads Gradle 9.9.1.

## Build and run

```bash
./gradlew build
```

```bash
./gradlew bootRun
```

Or run the jar:

```bash
java -jar build/libs/aggregator-0.1.0.jar
```

The application listens on port 8080 (`SERVER_PORT` overrides it). The workflows in
`src/main/resources/application.yml` point at placeholder hosts (`*.internal`); replace them with
your own, or supply your own file:

```bash
java -jar build/libs/aggregator-0.1.0.jar --spring.config.additional-location=file:./my-workflows.yml
```

## Worked example

With the `order-flow` workflow from `application.yml`:

```bash
curl -i -X POST http://localhost:8080/api/orders/42 \
  -H 'Content-Type: application/json' \
  -d '{"orderId":42}'
```

1. `validate` receives `{"orderId":42}`.
2. `enrich` receives the response body of `validate`.
3. `billing` and `inventory` are called concurrently, each with the response body of `enrich`.
4. The caller receives:

```json
{
  "results": {
    "billing":   { "status": 200, "body": { "charged": true } },
    "inventory": { "status": 200, "body": { "reserved": 3 } }
  }
}
```

## Responses

| Situation | Response |
| --- | --- |
| Workflow has an async stage | `200` with `{"results": {"<endpoint>": {"status", "body"}}}`, in configured order |
| Workflow has only a sync stage | The last sync endpoint's status, content type and body, unchanged |
| A sync endpoint fails | `502`; nothing further is called |
| An async endpoint fails, `on-async-failure: partial` | `200`; the failed entry has `status` and `error` instead of `body` |
| An async endpoint fails, `on-async-failure: fail` | `502` with `failedEndpoints` and the `results` gathered so far |

A call fails if it returns a non-2xx status, exceeds its timeout, or cannot connect. A sync failure
looks like this (`reason` is `failure` or `timeout`; `downstreamStatus` is `null` when no response
was received):

```json
{
  "error": "Synchronous endpoint failed",
  "workflow": "order-flow",
  "endpoint": "enrich",
  "stage": "sync",
  "reason": "failure",
  "downstreamStatus": 500,
  "detail": "Downstream returned status 500"
}
```

Bodies are treated as JSON. A downstream body that is not valid JSON appears in the merged response
as a string; an empty body appears as `null`.

Every response carries an `X-Correlation-Id` header. The value is taken from the inbound request, or
generated if absent, and is sent to every downstream call.

## Configuration reference

All workflows live under `proxy.workflows` and are validated at startup; the application refuses to
start on invalid configuration.

```yaml
proxy:
  connect-timeout: 2s             # TCP connect timeout for all downstream calls (default 2s)
  workflows:
    order-flow:                   # workflow name (the key); must be unique
      match:
        path: /api/orders/**      # Spring path pattern
        methods: [POST]           # optional; omitted = all methods
      sync:                       # list order = call order
        - name: validate
          url: https://validator.internal/validate
          method: POST
          timeout: 2s
      async:
        - name: billing
          url: https://billing.internal/charge
          method: POST
          timeout: 5s
      on-async-failure: partial   # partial (default) | fail

    catch-all:                    # mandatory
      match:
        path: /**
      sync:
        - name: default-backend
          url: https://default.internal
          method: INHERIT
          append-inbound-path: true
          timeout: 5s
```

### Workflow

| Property | Required | Description |
| --- | --- | --- |
| `match.path` | yes | Path pattern, e.g. `/api/orders/**` or `/api/orders/{id}` |
| `match.methods` | no | HTTP methods matched. Omitted means all methods |
| `sync` | no | Ordered list of endpoints called one after another |
| `async` | no | Endpoints called concurrently |
| `on-async-failure` | no | `partial` (default) or `fail` |

A workflow needs at least one endpoint across `sync` and `async`.

### Endpoint

| Property | Required | Description |
| --- | --- | --- |
| `name` | yes | Unique within the workflow; the key in the merged response |
| `url` | yes | Absolute `http` or `https` URL |
| `method` | yes | `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`, or `INHERIT` to use the inbound request's method |
| `timeout` | no | Upper bound for the whole call (default `5s`) |
| `headers` | no | Static headers added to the call |
| `forward-headers` | no | Names of inbound headers copied to the call. Nothing is forwarded unless listed |
| `append-inbound-path` | no | `true` appends the inbound path and query string to `url` (default `false`) |

`GET`, `HEAD` and `DELETE` calls are sent without a body.

### Matching rules

- If several workflows match a request, the most specific path pattern wins.
- Two workflows with the same path pattern and an overlapping method are rejected at startup. The
  same path with disjoint methods (one `GET`, one `POST`) is allowed.
- Exactly one catch-all workflow (`match.path: /**`, no `match.methods`) is mandatory, so every
  request is served by a workflow. A request whose path matches a specific workflow but whose
  method does not falls through to the catch-all.
- Actuator endpoints (`/actuator/**`) are never proxied.

## Observability

The application is instrumented with OpenTelemetry through Spring Boot's OpenTelemetry starter and
the Micrometer Observation API, exporting over OTLP/HTTP.

### Traces

One trace per request, continuing the caller's trace if a `traceparent` header is sent:

```
POST /api/orders/**            server span: end-to-end time, attribute workflow.name
└─ workflow order-flow         time spent executing the workflow
   ├─ workflow.sync            time spent in the synchronous stage
   │  ├─ endpoint validate     client span, one per downstream call
   │  └─ endpoint enrich
   └─ workflow.async           time spent in the asynchronous stage
      ├─ endpoint billing      these overlap in time
      └─ endpoint inventory
```

Endpoint spans carry `workflow.name`, `endpoint.name`, `stage`, `sync.position` (sync only),
`url.full`, `http.request.method`, `http.response.status_code` and `outcome`
(`success`, `failure` or `timeout`). A failed call has span status `ERROR`, the recorded exception,
and an `endpoint.failed` or `endpoint.timeout` event. A W3C `traceparent` header is sent on every
downstream call.

### Metrics

| Timer | Tags |
| --- | --- |
| `proxy.workflow` | `workflow.name`, `outcome` |
| `proxy.workflow.stage` | `workflow.name`, `stage`, `outcome` |
| `proxy.endpoint.call` | `workflow.name`, `endpoint.name`, `stage`, `outcome`, `http.request.method`, `http.response.status_code` |
| `http.server.requests` | Spring's standard server timer |

### Logs

Every log line includes the trace ID, span ID and correlation ID. Each downstream call is logged at
`INFO` with workflow, endpoint, stage, status, outcome and duration.

### Settings

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `OTEL_SERVICE_NAME` | `aggregator` | Service name on traces and metrics |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP/HTTP traces endpoint |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | OTLP/HTTP metrics endpoint |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Fraction of requests traced |

If no collector is reachable the application still starts and serves requests; the exporters log an
export failure and carry on.

### Viewing a trace locally

Start an OpenTelemetry collector and Jaeger:

```bash
docker compose up -d
```

Start the application, send a request, then open <http://localhost:16686>, choose the `aggregator`
service and select **Find Traces**. Metrics are printed to the collector's log:

```bash
docker compose logs -f otel-collector
```

## Tests

```bash
./gradlew test
```

- `WorkflowRegistryTest`: matching, specificity and configuration validation.
- `WorkflowExecutorTest`: the executor against a fake downstream, with no web context.
- `ProxyIntegrationTest`: the running application against WireMock.
- `TracingIntegrationTest`: span structure, using an in-memory span exporter.
- `StartupValidationTest`: invalid configuration stops the application context from starting.

## Design notes

- Spring MVC on virtual threads (`spring.threads.virtual.enabled=true`); no WebFlux. The
  asynchronous stage uses a virtual-thread-per-task executor rather than `StructuredTaskScope`,
  which is still a preview API in Java 25.
- Downstream calls use `RestClient` over the JDK HTTP client. Each call runs on its own virtual
  thread and is abandoned when its timeout expires, so the timeout bounds the whole call, body
  included.
- `WorkflowExecutor` has no dependency on the servlet API.
