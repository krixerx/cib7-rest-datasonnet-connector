# Design — rest-datasonnet connector

> Custom CIB seven (Camunda 7 fork) Connect SPI connector for calling external
> REST APIs with DataSonnet request / response / error mapping and a
> business-vs-system error model. A shareable open-source community extension
> for CIB seven.

| | |
|---|---|
| Status | Draft / Design — reviewed (office hours + eng review, 2026-05-22) |
| Date | 2026-05-22 |
| Target engine | CIB seven (Camunda 7 fork) — verified against the Connect SPI |
| Connector ID | `rest-datasonnet` |
| Maven module | `rest-datasonnet-connector` |
| Java package | `org.cibseven.community.connector.rest` |
| Maven groupId | `org.cibseven.community` *(provisional — see [roadmap.md](roadmap.md))* |

---

## 1. Purpose

The connector exists for one reason: **so nobody writes Java to call a REST API
from a CIB seven process.**

Build the connector once. From then on, every REST integration is pure BPMN — a
service task carrying `<camunda:connector>`, inline DataSonnet scripts for
request / response / error mapping, and BPMN error routing. Nothing is compiled,
nothing is deployed on the CIB7 engine side. A modeler ships an integration
without opening an IDE.

---

## 2. Locked design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Implementation form | True Connect SPI connector | Used via `<camunda:connector>` with a `connectorId`. A JavaDelegate was weighed in the eng review and Connect SPI was kept. |
| Error model | **Business vs system** — the modeler declares which non-2xx statuses are business outcomes; everything else is a system fault | The HTTP status alone cannot tell a business outcome from a defect. See [error-handling.md](error-handling.md). |
| Error surfacing | Business errors returned as **data**, converted to catchable BPMN errors via Error End Events in a wrapper subprocess; system faults **thrown** → incident | A connector cannot throw a catchable `BpmnError` (§5); it can throw a plain exception → incident. |
| HTTP client | The connector **instance** owns its `CloseableHttpClient` (Apache HttpClient 5.x) | Delegating to the shared built-in `HttpConnector` cannot honour per-task timeouts. See §4. |
| Redirects | Cross-host redirects are **not** followed | A blind redirect is an SSRF vector and corrupts `statusCode`. See §9. |
| Body format | **JSON only** for v1 | DataSonnet handles other formats, but v1 scopes to `application/json`. XML/CSV is a roadmap item. |
| Mapping-script storage | **Inline** by default; `classpath:` ref optional | A classpath script ships in a jar — a build and deploy, the thing this connector removes. |
| Primary retry tier | **In-task retry**, idempotency-aware | Keeps every failure classifiable; never silently double-submits a POST. |
| Mapping failures | Request-mapping failure → incident; response-mapping failure → returned as data (preserves the raw body) | See [error-handling.md](error-handling.md) §4. |
| Multi-endpoint orchestration | **Out of scope** — orchestrate in BPMN | In-connector orchestration breaks retry safety and engine visibility. |
| Test mocking | **External (WireMock)** — no mock mode in the connector | Production code must not carry test logic. See [testing.md](testing.md). |
| Apache Camel | **Considered and rejected** | Same underlying HTTP engine, extra weight. See §6. |

---

## 3. Architecture

```
 +------------------ rest-datasonnet connector (Connect SPI) -----------------------+
 |  validate -> [request DataSonnet] -> [HTTP invoke + idempotency-aware retry]      |
 |                                              |                                   |
 |                                       [classify result]                          |
 |             2xx -> [response DS] -> SUCCESS ──┤                                   |
 |   declared business status -> [error DS] -> BUSINESS_ERROR ─┤  (returned as DATA) |
 |   response-DS failed (2xx) -> RESPONSE_MAPPING_FAILED ───────┘                    |
 |   anything else (401/403/5xx/network/oversize/bad config/request-DS) -> THROW     |
 +----------------------------------------------------------------------------------+
        returned data: restOutcome, result, statusCode, responseHeaders,
                       restErrorCode, restError, restRawResponse
        thrown        : RestConfigurationException / RestMappingException /
                        ConnectorException  ->  CIB7 incident
                                          |
   BPMN:  service task -> XOR gateway on ${restOutcome}
                           |- SUCCESS ----------------------> continue
                           |- BUSINESS_ERROR -> XOR on ${restErrorCode} -> Error End Event
                           +- RESPONSE_MAPPING_FAILED ------> Error End Event (manual recovery)
          ...wrapped in an embedded subprocess with error boundary events.
```

The connector returns data for the three modeler-facing outcomes and throws for
system faults. The wrapper subprocess converts the returned business-error data
into catchable BPMN errors; system faults need no wrapper, they throw straight to
an incident.

---

## 4. The HTTP client — why the connector owns it

CIB seven's `connect` module is built on Apache HttpClient 5.x (`httpclient5` 5.5
+ `httpcore5` 5.3.x), already on the CIB7 engine classpath. That library provides
connection pooling, TLS 1.2/1.3, and full timeout control.

**The connector instance builds and owns its own `CloseableHttpClient`** rather
than delegating to the built-in `HttpConnector` facade. The facade is a shared,
engine-managed singleton with a fixed client; it cannot honour the per-task
`connectTimeout` / `readTimeout` parameters that [configuration.md](configuration.md)
exposes. Owning the client is cheap: `httpclient5` is already on the classpath,
so it is a `provided`-scope compile dependency, **zero new runtime jars**.

- **Ownership.** The **connector instance** holds the client, not the provider.
  The connector instance is the long-lived object the Connect registry keeps and
  reuses for every execution; the provider is a short-lived factory. The client
  is lazily initialised on first `execute()` with a thread-safe guard.
- **Pool sizing.** The connector builds the client with engine-appropriate pool
  defaults (`maxTotal` ≈ 100, `maxPerRoute` ≈ 20), not HttpClient's low defaults,
  overridable by a system property. Idle- and expired-connection eviction is
  enabled so the pool self-heals.
- **Per-call config.** Each invocation gets a `RequestConfig` carrying that
  task's `connectTimeout` / `readTimeout`.
- **Redirects.** Cross-host redirects are not followed (see §9).
- **Lifecycle.** The pooled client is engine-lifetime scoped. Explicit shutdown
  is a minor open item ([roadmap.md](roadmap.md)).

---

## 5. The connector form & the wrapper subprocess (the SPI constraint)

What routes a `BpmnError` to an error boundary event is
`ErrorPropagation.propagateError(...)`, called by `ClassDelegateActivityBehavior`
(the behavior behind a `camunda:class` JavaDelegate). A `<camunda:connector>`
runs under `ServiceTaskConnectorActivityBehavior`, which does **not** wrap
execution that way. Consequences:

- A `BpmnError` thrown inside a connector is **not** caught by boundary events.
- The connector's `execute(request)` receives only a parameter `Map` — never the
  `DelegateExecution` — so it cannot read the job retry count or set process
  variables beyond the mapped outputs.
- A connector communicates by **either returning data or throwing, never both.**

Hence the model: **business errors are returned as data**, and a BPMN wrapper
subprocess re-throws them via Error End Events so boundary events can catch them.
**System faults are thrown** as plain exceptions, which become incidents directly
with no wrapper involved. The one subtlety this forces — a response-mapping
failure cannot both preserve the raw body and throw — is handled in
[error-handling.md](error-handling.md) §4.

> A JavaDelegate (`camunda:class`) would remove the wrapper subprocess (it can
> throw a catchable `BpmnError`) and grant `DelegateExecution`. This was raised
> in the eng review; the Connect SPI form was kept deliberately. If that choice
> is ever revisited, §5 and the wrapper subprocess are what change.

---

## 6. Alternatives considered

**Apache Camel** — considered and rejected. Camel's `camel-http` is built on the
same Apache HttpClient that `cibseven-connect-http-client` already uses, so Camel
would be a heavier wrapper over the identical engine, plus a `CamelContext`
lifecycle the connector has no hooks for. The connector stays on Apache HttpClient
5.x + `datasonnet-mapper`.

---

## 7. Connect SPI classes

| Class | Extends / implements | Role |
|---|---|---|
| `RestDataSonnetConnector` | `AbstractConnector<RestDataSonnetRequest, RestDataSonnetResponse>` | Holds the connector ID; runs `execute()`; owns the `CloseableHttpClient`. |
| `RestDataSonnetRequest` | `AbstractConnectorRequest<RestDataSonnetResponse>` | Carries the input parameters. |
| `RestDataSonnetResponse` | `AbstractConnectorResponse` | Carries the output parameters. |
| `RestDataSonnetConnectorProvider` | `ConnectorProvider` | Factory — returns the connector ID and a connector instance. Does **not** hold the client. |
| `RestMappingException` | `ConnectorException` (runtime) | Thrown on a **request**-mapping failure → incident. |
| `RestConfigurationException` | `ConnectorException` (runtime) | Thrown on an input-validation failure → incident, naming the bad parameter. |

A non-2xx system fault or a post-retry transport failure throws a
`ConnectorException` with a descriptive message → incident.

**Internal helpers** (not SPI — plain classes, independently unit-testable):

| Helper | Role |
|---|---|
| `DataSonnetMapper` | Wraps the DataSonnet `Mapper`; runs a request / response / error script. Holds a **bounded, thread-safe** compiled-`Mapper` cache (a size-capped `ConcurrentHashMap` keyed on script text, including the version header). Handles the input type defensively: `String` used as-is, Spin `SpinJsonNode` serialized, any other type → explicit error. |
| `HttpInvoker` | Executes one HTTP attempt against the owned client with a per-call `RequestConfig`; enforces `maxResponseBytes`; no cross-host redirects. |
| `StatusClassifier` | Maps the HTTP result to SUCCESS / BUSINESS_ERROR / SYSTEM fault given `businessErrorStatuses`, and decides retryability. |

**Service-loader registration:**

```
META-INF/services/org.camunda.connect.spi.ConnectorProvider
  -> org.cibseven.community.connector.rest.RestDataSonnetConnectorProvider
```

The `cibseven-engine-plugin-connect` process-engine plugin must be active for
`<camunda:connector>` parsing.

> **SPI namespace caveat:** CIB seven 1.x keeps the SPI under
> `org.camunda.connect.spi`. On 2.x, verify whether it moved to
> `org.cibseven.connect.spi` — only the `import`s change.

**Concurrency:** the Connect registry creates one connector instance and reuses
it across all job-executor threads. The `CloseableHttpClient` is thread-safe; the
`DataSonnetMapper` cache is a `ConcurrentHashMap`; concurrent `Mapper.transform()`
safety is confirmed by a concurrency test (if a single `Mapper` is not
concurrent-safe, the cache holds the compiled script and a `Mapper` is built per
call).

---

## 8. Execution flow (`RestDataSonnetConnector.execute`)

1. **Validate inputs** — `url`, `method`, timeouts, retry counts. Failure →
   `RestConfigurationException` → incident.
2. **Request mapping** — run `requestMapping` with `source` as `payload`
   (`DataSonnetMapper` handles the input type defensively). The script returns a
   bare body or an envelope `{ url, method, query, headers, body }`; envelope
   fields override the corresponding input parameters, omitted fields fall back.
   Failure → `RestMappingException` → incident.
3. **Invoke HTTP** — `HttpInvoker` executes the call against the owned client
   with a per-call `RequestConfig`, the `maxResponseBytes` cap, and no cross-host
   redirects.
4. **In-task retry loop** — idempotency-aware (see [configuration.md](configuration.md) §3).
5. **Classify** via `StatusClassifier`:
   - **2xx** → run `responseMapping` → `result`; `restOutcome = SUCCESS`.
     `responseMapping` failure → `restOutcome = RESPONSE_MAPPING_FAILED`, raw body
     preserved in `restRawResponse` (returned, not thrown).
   - **non-2xx in `businessErrorStatuses`** → run `errorMapping` → `restError`;
     `restErrorCode = REST_ERROR_<status>`; `restOutcome = BUSINESS_ERROR`.
   - **anything else** (undeclared non-2xx, transport failure post-retry,
     oversize response) → SYSTEM fault → throw → incident.
6. Return `RestDataSonnetResponse` for the SUCCESS / BUSINESS_ERROR /
   RESPONSE_MAPPING_FAILED cases.

---

## 9. Trust model & security

The connector calls whatever URL **resolves** at runtime. That URL is
expression-driven (`${restBaseUrl}/orders`) and the request-mapping script can
override it from `payload` data. So "the URL is in the BPMN" does not mean the
URL is a fixed, trusted literal — its value can come from a process variable set
by whoever started the instance, or be computed by a script.

Consequence: on a shared engine, or whenever the URL is built from
externally-supplied data, the connector is a **server-side request forgery
(SSRF)** surface — it can be steered at internal services or a cloud metadata
endpoint. Two mitigations are in v1:

- **No cross-host redirects.** The connector does not follow a redirect to a
  different host. This closes the redirect-SSRF vector (a compromised endpoint
  302-ing the connector to `169.254.169.254`) and keeps `statusCode` honest.
- **This documented trust model.** If you build the URL from data an untrusted
  party controls, that is an SSRF path; keep the URL author-controlled, or scope
  the connector to a trusted engine.

A full engine-side URL allowlist is a roadmap item ([roadmap.md](roadmap.md)),
for operators of shared or internet-exposed engines.

---

## 10. Packaging & distribution

A standalone Maven module, `rest-datasonnet-connector`:

- The Connect SPI implementations + the `META-INF/services` provider file + the
  internal helpers.
- The Camunda Modeler element template (`.json`) and the sample wrapper BPMN
  fragment ship with the module.
- **Dependencies:** the CIB seven Connect **SPI** module (confirm the exact
  artifact id for the target CIB7 version); `org.apache.httpcomponents.client5:httpclient5`
  — **`provided`** scope, already on the engine classpath; `com.datasonnet:datasonnet-mapper`
  — pin the version explicitly, it brings the Scala runtime + sjsonnet (an
  accepted footprint cost).
- Must sit on Connect's classloader — a dependency for an embedded engine, the
  shared lib for a shared engine.

CI/CD and the publishing channel are tracked in [roadmap.md](roadmap.md).

---

## 11. Risks & caveats

1. **No `DelegateExecution`** — the connector returns data or throws, never both;
   this shapes the whole error model (§5).
2. **DataSonnet dependency weight** — Scala + sjsonnet + Jackson on the engine
   classpath. A Jackson clash with the engine / Spin is the concrete danger.
   Mitigation: a Maven Enforcer `dependencyConvergence` check in CI, a pinned
   tested Jackson version; shade DataSonnet's Jackson if convergence fails.
3. **Spin ↔ DataSonnet boundary** — `DataSonnetMapper` serializes a
   `SpinJsonNode` input to String; the input-resolution shape is confirmed
   against a CIB7 engine (open item, [roadmap.md](roadmap.md)).
4. **Type mismatches do not throw** in DataSonnet — write defensive scripts with
   explicit `error()` calls (see [error-handling.md](error-handling.md) §6).
5. **Modeling boilerplate** — the wrapper subprocess is per call site, but with
   the business-vs-system model it only routes the declared business statuses
   (usually one or two). An element template + copy-paste fragment cover it in
   v1; a Call Activity wrapper removes it in milestone 2.
6. **SPI package namespace** depends on the exact CIB7 version (§7).
7. **SSRF surface** — mitigated by no-cross-host-redirects + the documented trust
   model; a full allowlist is deferred (§9, [roadmap.md](roadmap.md)).
8. **In-task retry holds a job thread** for the whole retry budget (worst case
   ~96 s on defaults) — size the job-executor pool accordingly.
