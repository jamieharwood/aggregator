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
- MongoDB, which holds the workflow definitions
- The Gradle wrapper downloads Gradle 9.9.1; the tests use an in-memory MongoDB and need no
  running database.

## Build and run

```bash
./gradlew build
```

Start MongoDB with the example workflows (also starts the tracing stack described below):

```bash
docker compose up -d
```

Then start the application:

```bash
./gradlew bootRun
```

Or run the jar:

```bash
java -jar build/libs/aggregator-0.1.0.jar
```

The application listens on port 8080 (`SERVER_PORT` overrides it) and connects to
`mongodb://localhost:27017/aggregator` (`MONGODB_URI` overrides it). The example workflows in
`mongo/init-workflows.js` point at placeholder hosts (`*.internal`); replace them with your own.

To load the example workflows into a MongoDB you run yourself:

```bash
mongosh "mongodb://localhost:27017/aggregator" mongo/init-workflows.js
```

## Worked example

With the `order-flow` workflow from `mongo/init-workflows.js`:

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
| An async endpoint fails, `onAsyncFailure: "partial"` | `200`; the failed entry has `status` and `error` instead of `body` |
| An async endpoint fails, `onAsyncFailure: "fail"` | `502` with `failedEndpoints` and the `results` gathered so far |

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

## Workflow definitions

Workflows are stored in MongoDB, in the `workflows` collection of the database named in the
connection string. Each document is one workflow, and its `_id` is the workflow name.

```javascript
{
  _id: "order-flow",                // workflow name; unique by construction
  match: {
    path: "/api/orders/**",         // Spring path pattern
    methods: ["POST"]               // optional; omitted = all methods
  },
  sync: [                           // array order = call order
    { name: "validate", url: "https://validator.internal/validate", method: "POST", timeout: "2s" }
  ],
  async: [
    { name: "billing", url: "https://billing.internal/charge", method: "POST", timeout: "5s" }
  ],
  onAsyncFailure: "partial"         // partial (default) | fail
}
```

The mandatory catch-all:

```javascript
{
  _id: "catch-all",
  match: { path: "/**" },
  sync: [
    { name: "default-backend", url: "https://default.internal", method: "INHERIT",
      appendInboundPath: true, timeout: "5s" }
  ]
}
```

### Loading and reloading

- **Startup:** all workflows are read and validated. If the collection is empty, a workflow is
  invalid, the catch-all is missing, or MongoDB cannot be reached, the application does not start.
- **Reload:** the collection is re-read every 30 seconds (`proxy.workflow-store.refresh-interval`),
  so added, changed and removed workflows take effect without a restart. Requests already in
  progress finish on the workflow they started with.
- **Invalid change:** the set of workflows is validated as a whole. If a reload finds anything
  invalid, the whole reload is rejected, an error is logged, and the previously loaded workflows
  stay in force until the data is fixed. The same applies if MongoDB is unreachable at reload time.

Change a workflow with any MongoDB client, for example:

```bash
mongosh "mongodb://localhost:27017/aggregator" --eval \
  'db.workflows.updateOne({_id: "order-flow"}, {$set: {"async.0.timeout": "10s"}})'
```

### Workflow

| Field | Required | Description |
| --- | --- | --- |
| `_id` | yes | The workflow name; a non-empty string |
| `match.path` | yes | Path pattern, e.g. `/api/orders/**` or `/api/orders/{id}` |
| `match.methods` | no | HTTP methods matched. Omitted means all methods |
| `sync` | no | Ordered list of endpoints called one after another |
| `async` | no | Endpoints called concurrently |
| `onAsyncFailure` | no | `partial` (default) or `fail` |

A workflow needs at least one endpoint across `sync` and `async`.

### Endpoint

| Field | Required | Description |
| --- | --- | --- |
| `name` | yes | Unique within the workflow; the key in the merged response |
| `url` | yes | Absolute `http` or `https` URL |
| `method` | yes | `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`, or `INHERIT` to use the inbound request's method |
| `timeout` | no | Upper bound for the whole call: a string such as `"2s"` or `"500ms"`, or a number of milliseconds (default `5s`) |
| `headers` | no | Static headers added to the call |
| `forwardHeaders` | no | Names of inbound headers copied to the call. Nothing is forwarded unless listed |
| `appendInboundPath` | no | `true` appends the inbound path and query string to `url` (default `false`) |

`GET`, `HEAD` and `DELETE` calls are sent without a body.

### Matching rules

- If several workflows match a request, the most specific path pattern wins.
- Two workflows with the same path pattern and an overlapping method are rejected. The
  same path with disjoint methods (one `GET`, one `POST`) is allowed.
- Exactly one catch-all workflow (`match.path: /**`, no `match.methods`) is mandatory, so every
  request is served by a workflow. A request whose path matches a specific workflow but whose
  method does not falls through to the catch-all.
- Actuator endpoints (`/actuator/**`) are never proxied.

## Application settings

Set in `application.yml` or by environment variable.

| Setting | Environment variable | Default | Purpose |
| --- | --- | --- | --- |
| `spring.mongodb.uri` | `MONGODB_URI` | `mongodb://localhost:27017/aggregator` | MongoDB connection string, including the database |
| `proxy.workflow-store.collection` | | `workflows` | Collection holding the workflows |
| `proxy.workflow-store.refresh-interval` | `WORKFLOW_REFRESH_INTERVAL` | `30s` | How often workflows are reloaded; `0` reads them once at startup |
| `proxy.connect-timeout` | | `2s` | TCP connect timeout for all downstream calls |
| `otel.enabled` | `OTEL_ENABLED` | `false` | Export traces and metrics over OTLP; see Observability |
| `server.port` | `SERVER_PORT` | `8080` | HTTP port |

`/actuator/health` includes MongoDB. If MongoDB becomes unreachable the application keeps serving
with the workflows it has loaded, but health reports `DOWN`.

## Observability

The application is instrumented with OpenTelemetry through Spring Boot's OpenTelemetry starter and
the Micrometer Observation API, exporting over OTLP/HTTP.

### Switching it on

Export is controlled by one switch in `application.yml`, and it is **off by default**:

```yaml
otel:
  enabled: ${OTEL_ENABLED:false}
```

Set `OTEL_ENABLED=true` (or `otel.enabled: true`) to export traces and metrics. When the switch is
off, nothing is exported and no collector is contacted. Trace and span IDs still appear in the logs,
and `traceparent` is still sent downstream, so a caller's trace is not broken by the proxy.

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
| `OTEL_ENABLED` | `false` | Switch for exporting traces and metrics |
| `OTEL_SERVICE_NAME` | `aggregator` | Service name on traces and metrics |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP/HTTP traces endpoint |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | OTLP/HTTP metrics endpoint |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Fraction of requests traced |

If the switch is on and no collector is reachable, the application still starts and serves requests;
the exporters log an export failure and carry on.

### Viewing a trace locally

`docker compose up -d` also starts an OpenTelemetry collector and Jaeger. Start the application
with export switched on:

```bash
OTEL_ENABLED=true ./gradlew bootRun
```

Then send a request, then open <http://localhost:16686>, choose the `aggregator`
service and select **Find Traces**. Metrics are printed to the collector's log:

```bash
docker compose logs -f otel-collector
```

## Tests

```bash
./gradlew test
```

- `WorkflowTableTest`: matching, specificity and validation of workflow definitions.
- `WorkflowRegistryTest`, `WorkflowRefresherTest`: loading, reloading and rejecting invalid changes.
- `MongoWorkflowDefinitionRepositoryTest`: reading workflow documents from MongoDB.
- `WorkflowExecutorTest`: the executor against a fake downstream, with no web context.
- `ProxyIntegrationTest`: the running application against WireMock.
- `TracingIntegrationTest`: span structure, using an in-memory span exporter.
- `WorkflowReloadIntegrationTest`: changes in MongoDB take effect in the running application.
- `OtelSwitchTest`: the `otel.enabled` switch turns trace and metric export on and off.
- `StartupValidationTest`: invalid stored workflows stop the application from starting.

The MongoDB tests run against an in-memory MongoDB (`mongo-java-server`), not a real server.

## Design notes

- Spring MVC on virtual threads (`spring.threads.virtual.enabled=true`); no WebFlux. The
  asynchronous stage uses a virtual-thread-per-task executor rather than `StructuredTaskScope`,
  which is still a preview API in Java 25.
- Downstream calls use `RestClient` over the JDK HTTP client. Each call runs on its own virtual
  thread and is abandoned when its timeout expires, so the timeout bounds the whole call, body
  included.
- `WorkflowExecutor` has no dependency on the servlet API.

## Specification

`PROMPT.md` holds the full specification this application was built from, written as a prompt for a
coding agent.
