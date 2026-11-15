# Build prompt

The specification this application was built from, written as a prompt for a coding agent. Keep it
in step with the code when behaviour changes.

```text
Build a Java 25 / Spring Boot 4 application, built with Gradle, that acts as a
configurable API proxy. An inbound request is matched to a named workflow, and
the workflow fans the request out to one or more downstream endpoints. Workflow
definitions are stored in MongoDB.

## Workflow model

A workflow has two stages, executed in this order:

1. Synchronous stage (optional): an ordered list of endpoints, called one at a
   time in the stored order. The first endpoint receives the inbound request
   body. Each later endpoint receives the response body of the endpoint before
   it.
2. Asynchronous stage (optional): a set of endpoints called concurrently. Every
   one of them receives the same input: the response of the last synchronous
   endpoint, or the inbound request body if there is no synchronous stage.

A workflow must have at least one endpoint in total. Either stage may be
empty.

## Response

- If the workflow has an asynchronous stage, wait for all async endpoints to
  finish and merge their responses into one JSON response, keyed by endpoint
  name, in the stored order:
  { "results": { "<endpointName>": { "status": 200, "body": ... }, ... } }
- If the workflow has only a synchronous stage, return the last synchronous
  endpoint's response (status, content type and body) unchanged.

## Workflow storage (MongoDB)

Workflows are NOT defined in application.yml. They are stored in MongoDB, in a
collection named "workflows" (configurable) in the database named by the
connection string. Each document is one workflow, and its _id is the workflow
name. Use the MongoDB sync driver through Spring Boot's MongoDB starter and
read the documents explicitly, so that a malformed document produces a clear
error naming the workflow and field. Shape:

{
  _id: "order-flow",
  match: { path: "/api/orders/**", methods: ["POST"] },
  sync: [                                   // array order = call order
    { name: "validate", url: "https://validator.internal/validate",
      method: "POST", timeout: "2s" },
    { name: "enrich", url: "https://enricher.internal/enrich",
      method: "POST", timeout: "2s" }
  ],
  async: [
    { name: "billing", url: "https://billing.internal/charge",
      method: "POST", timeout: "5s" },
    { name: "inventory", url: "https://inventory.internal/reserve",
      method: "POST", timeout: "5s" }
  ],
  onAsyncFailure: "partial"                 // partial (default) | fail
}

{
  _id: "catch-all",
  match: { path: "/**" },                   // methods omitted = all methods
  sync: [
    { name: "default-backend", url: "https://default.internal",
      method: "INHERIT", appendInboundPath: true, timeout: "5s" }
  ]
}

Workflows:
- match.path is a Spring path pattern. match.methods is optional; when omitted
  the workflow matches all HTTP methods.
- If more than one workflow matches an inbound request, the most specific path
  pattern wins.
- Two workflows with the same path pattern and an overlapping method are
  invalid. The same path with disjoint methods is allowed.
- A catch-all workflow is mandatory: exactly one workflow must have match.path
  of /** with no methods restriction. It has the lowest priority and handles
  any request that no other workflow matches, so every inbound call is served
  by a workflow.

Endpoints:
- name (unique within the workflow, across both stages), url (absolute http or
  https), method, timeout, optional static headers (object), and
  forwardHeaders (array of inbound header names to copy). No inbound header is
  forwarded unless listed.
- timeout is a duration string such as "2s" or "500ms", or a number of
  milliseconds. Default 5s. It bounds the whole call, body included.
- method: INHERIT uses the inbound request's HTTP method for the downstream
  call.
- GET, DELETE and HEAD calls are sent without a body.
- appendInboundPath: true (default false) appends the inbound request path and
  query string to the endpoint url.

Loading and reloading:
- At startup, read and validate all workflows. If the collection is empty, any
  workflow is invalid, the catch-all is missing, or MongoDB cannot be reached,
  the application must fail to start.
- Re-read the collection on a fixed interval (default 30s, configurable; 0
  disables reloading) so that added, changed and removed workflows take effect
  without a restart. Requests in progress finish on the workflow they started
  with.
- Validate the set of workflows as a whole on every reload. If a reload finds
  anything invalid, or MongoDB is unreachable, reject the whole reload, log an
  error, and keep serving with the previously loaded workflows.
- The application only reads workflows. Do not build an API for editing them.
- Keep the storage behind an interface so the routing and validation logic can
  be unit tested without MongoDB.

Provide a seed script (mongo/init-workflows.js) holding example workflows,
idempotent, runnable with mongosh and run automatically by docker compose on
first start.

## Application settings (application.yml)

Each overridable by environment variable:
- spring.mongodb.uri (MONGODB_URI), default
  mongodb://localhost:27017/aggregator
- proxy.workflow-store.collection, default "workflows"
- proxy.workflow-store.refresh-interval (WORKFLOW_REFRESH_INTERVAL), default
  30s
- proxy.connect-timeout, default 2s, the TCP connect timeout for all
  downstream calls
- otel.enabled (OTEL_ENABLED), default false; see Observability
- server.port (SERVER_PORT), default 8080

Bind application settings with @ConfigurationProperties records.

## Behaviour and error handling

- Every inbound request matches a workflow, because the catch-all is
  mandatory. There is no "no workflow matched" response. Actuator endpoints
  are excluded from workflow matching; an unknown actuator path returns 404.
- Synchronous stage: if an endpoint returns a non-2xx status, times out, or
  fails to connect, stop the workflow, call nothing further, and return 502
  with a JSON error naming the workflow, the failed endpoint, the reason
  (failure | timeout) and the downstream status (null if no response).
- Asynchronous stage: controlled by onAsyncFailure.
  - partial (default): return 200 with every endpoint in the merged response;
    failed ones carry their status and an "error" field instead of "body".
  - fail: return 502 if any async endpoint fails, listing the failed endpoints
    alongside the results gathered.
- Each endpoint call is bounded by its own timeout.
- Bodies are treated as JSON. A non-JSON downstream body is included in the
  merged response as a string; an empty body as null.
- Propagate a correlation ID header (X-Correlation-Id, generated if absent) to
  every downstream call, return it on the response, and include it in logs.

## Observability (OpenTelemetry)

Instrument the application with OpenTelemetry, using Spring Boot 4's
OpenTelemetry starter and the Micrometer Observation API, exporting over
OTLP/HTTP. Check the Spring Boot 4 reference documentation for the current
starter and property names rather than relying on Spring Boot 3 conventions.

Switch:
- A single switch in application.yml, otel.enabled, controls export. It is
  false by default and overridable with OTEL_ENABLED.
- When false, no traces or metrics are exported and no collector is contacted.
  Trace and span IDs must still appear in logs and trace context must still be
  propagated downstream, so a caller's trace is not broken by the proxy.
- When true, traces and metrics are exported over OTLP.
- Wire the switch to Spring Boot's own OTLP export properties in
  application.yml; do not write custom auto-configuration for it. Switch off
  the OTLP exporters specifically: disabling tracing export as a whole also
  disables trace context propagation.

Traces:
- One server span per inbound request, covering the full end-to-end time from
  request received to response sent, with the attribute workflow.name.
- One child span per workflow execution, named "workflow <name>".
- One child span per stage that runs: "workflow.sync" and "workflow.async",
  so the time spent in each stage is visible.
- One client span per downstream endpoint call, named "endpoint <name>", as a
  child of its stage span. Attributes: workflow.name, endpoint.name, stage
  (sync | async), sync.position (sync endpoints), url.full,
  http.request.method, http.response.status_code, outcome
  (success | failure | timeout). Produce exactly one span per call: do not let
  the HTTP client's own instrumentation add a second.
- A failed or timed-out call sets the span status to ERROR, records the
  exception, and adds a span event ("endpoint.timeout" or "endpoint.failed").
- Async endpoint spans must be children of the same request trace. Trace
  context must propagate correctly onto the virtual threads that run the
  async stage; do not let those calls start new, disconnected traces.
- Propagate W3C trace context (traceparent header) on every downstream call,
  and continue an inbound trace if the caller sends one.
- The periodic workflow reload must not produce traces.

Metrics:
- A timer for workflow execution (proxy.workflow), tagged with workflow.name
  and outcome.
- A timer for each stage (proxy.workflow.stage), tagged with workflow.name,
  stage and outcome.
- A timer for each downstream call (proxy.endpoint.call), tagged with
  workflow.name, endpoint.name, stage, outcome, http.request.method and
  http.response.status_code.
- Do not tag metrics with the raw path or full url (unbounded cardinality).

Logs:
- Log each downstream call at INFO: workflow, endpoint, stage, status,
  outcome, duration.
- Include trace ID, span ID and correlation ID in every log line.

Configuration:
- OTLP trace endpoint, OTLP metrics endpoint, service name and sampling
  probability are set in application.yml and overridable by environment
  variables. Default sampling is 100%.
- With the switch on and no collector reachable, the application must still
  start and serve requests normally.

## Technical requirements

- Java 25, Spring Boot 4, Spring MVC with virtual threads enabled
  (spring.threads.virtual.enabled=true). Do not use WebFlux.
- Gradle build with the Gradle wrapper committed, using the Groovy DSL and a
  Java 25 toolchain. Do not use Maven.
- Use RestClient for downstream calls. Run the asynchronous stage on a
  virtual-thread-per-task executor; no blocking platform-thread pools. Do not
  use StructuredTaskScope or any other preview API.
- Records for settings and DTOs, constructor injection, no Lombok.
- Keep workflow execution in a service with no dependency on the servlet API,
  so it can be unit tested without a web context.
- Expose Actuator health.

## Tests

- Unit tests for the workflow executor covering: sync only, async only, sync
  then async, sync failure mid-chain, async partial failure in both
  onAsyncFailure modes, timeout, and headers.
- Unit tests for matching and validation: a specific workflow wins over the
  catch-all, an unmatched path or method goes to the catch-all, the most
  specific pattern wins, and each invalid definition is rejected.
- Unit tests for loading: a changed, added or removed workflow takes effect on
  reload; an invalid reload or a store failure keeps the previous workflows;
  reloading runs on the configured interval and is disabled by 0.
- Tests for reading workflow documents from MongoDB, including defaults and
  malformed documents.
- Integration tests with @SpringBootTest and WireMock that prove: sync
  endpoints are called in the stored order, each receives the previous
  response body, every async endpoint receives the last sync response, the
  merged response contains all async results, the catch-all preserves the
  path and query string, and a change made in MongoDB takes effect in the
  running application after a reload.
- A test that proves the async calls run concurrently (total time is close to
  the slowest endpoint, not the sum).
- Tests that the application fails to start with no workflows, without a
  catch-all, or with two workflows on the same path and method.
- A test using an in-memory span exporter that proves, for a sync-then-async
  workflow: all spans share one trace ID, each endpoint span has the correct
  parent stage span, async endpoint spans overlap in time, and a failed call
  produces a span with ERROR status.
- A test that proves the otel.enabled switch: no span or metric exporters when
  it is false or unset, exporters present when it is true.
- Tests must not need a running MongoDB or Docker; use an in-memory MongoDB
  (de.bwaldvogel:mongo-java-server).

## Deliverables

- A working project that passes `./gradlew build`.
- A README covering how to run it, the workflow document reference, the
  application settings, and one worked example with curl.
- A docker-compose file that runs MongoDB (seeded with the example
  workflows), an OpenTelemetry collector and a trace viewer (Jaeger or Grafana
  Tempo) locally, with README instructions for viewing a trace.

Before writing code, state any assumption you make where this spec is silent.
```
