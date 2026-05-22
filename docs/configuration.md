# Configuration — rest-datasonnet connector

The connector is configured entirely through `<camunda:inputParameter>` and
`<camunda:outputParameter>` on the service task. Nothing is configured in Java.
The Modeler element template pre-fills these (see [bpmn-modeling.md](bpmn-modeling.md)).

---

## 1. Input parameters

| Param | Type | Notes |
|---|---|---|
| `source` | Object | The input data — any process variable or expression holding the payload. |
| `url` | String | Target URL. Must be expression-driven (e.g. `${restBaseUrl}/orders`). |
| `method` | String | `GET` / `POST` / `PUT` / `PATCH` / `DELETE`. |
| `headers` | Map | Static headers (auth, content-type). |
| `query` | Map | Static query parameters. Optional. The request envelope can override these. |
| `requestMapping` | String | DataSonnet script — inline (default) or `classpath:`. Optional. |
| `responseMapping` | String | DataSonnet script for the 2xx body. Optional. |
| `errorMapping` | String | DataSonnet script for the error context. Optional. |
| `businessErrorStatuses` | String | Comma-separated non-2xx statuses that are business outcomes for this call (e.g. `404,409`). **Default empty** — every non-2xx is a system fault until you opt it in. See [error-handling.md](error-handling.md) §1. |
| `readTimeout` | Integer (ms) | Per-request response timeout. Default `30000`. |
| `retryCount` | Integer | In-task retry attempts. Default `3`. |
| `retryDelay` | Integer (ms) | Delay between in-task retries. Default `2000`. |
| `retryableStatuses` | String | Comma-separated. Default `408,429,500,502,503,504`. |
| `retryNonIdempotent` | Boolean | Allow `POST`/`PATCH` to be retried on a read-timeout. Default `false` (safe). See §3. |
| `maxResponseBytes` | Integer | Maximum response body size. Default `10485760` (10 MB). A larger response is a system fault — see [error-handling.md](error-handling.md) §2. |

**v1 is JSON-only.** The connector sends and expects `application/json`. A 2xx
response that is not JSON fails `responseMapping` and takes the
response-mapping-failure path. Other content types (XML, CSV) are a roadmap item
(see [roadmap.md](roadmap.md)).

### Mapping scripts: inline vs classpath, and omission

- **Inline** (default, recommended) — the script is written into the
  `<camunda:inputParameter>` value. The integration is fully self-contained: it
  deploys with the BPMN, nothing is compiled or shipped in a jar.
- **`classpath:` reference** — e.g. `classpath:maps/foo-request.ds`. Cleaner XML,
  but the script ships in a jar, which reintroduces a build and deploy step.

All three mapping params are **optional**. Omitted means identity passthrough: no
`requestMapping` sends `source` as the body unchanged; no `responseMapping` puts
the raw 2xx body into `result`; no `errorMapping` produces the default error
object.

---

## 2. Output parameters

The connector returns these **only when it returns data** — outcomes `SUCCESS`,
`BUSINESS_ERROR`, `RESPONSE_MAPPING_FAILED`. A system fault throws instead, so
there is no response and no output variables (the engine raises an incident).

| Param | When | Notes |
|---|---|---|
| `restOutcome` | always returned | `SUCCESS` \| `BUSINESS_ERROR` \| `RESPONSE_MAPPING_FAILED`. |
| `result` | SUCCESS | The response-mapped object. |
| `statusCode` | always returned | HTTP status code. |
| `responseHeaders` | always returned | Response headers map. |
| `restErrorCode` | BUSINESS_ERROR, RESPONSE_MAPPING_FAILED | `REST_ERROR_<status>` (e.g. `REST_ERROR_404`) or `REST_ERROR_RESPONSE_MAPPING`. |
| `restError` | BUSINESS_ERROR, RESPONSE_MAPPING_FAILED | The mapped error object, or the mapping error detail. |
| `restRawResponse` | RESPONSE_MAPPING_FAILED | The raw 2xx body, preserved for manual recovery. |

The **When** column is when a parameter carries a meaningful value. Every row is
always present in the connector's response — the ones that do not apply to the
current outcome are `null`. So a `<camunda:outputParameter>` can map any of them
without the modeler knowing the outcome in advance; it just resolves to `null`
when not applicable.

---

## 3. Retry & timeout model

Per-task knobs: `readTimeout` (the per-request response timeout), `retryDelay`,
and `retryCount`. The connect timeout is connector-level, not per task — see
below.

### Connect timeout

Apache HttpClient 5.x sets the TCP-connect timeout on the connection manager,
not per request. So the connector applies a single connect timeout to its whole
pooled client; it is **not** a per-task input parameter. The default is 5000 ms,
overridable engine-wide via a JVM system property on the connector module.
`readTimeout` — the timeout that genuinely varies per call — stays per task.

### What is retried

In-task retry fires on a retryable status (`retryableStatuses`) or a transport
failure. It is **idempotency-aware** — retrying a non-idempotent call after a
timeout can double-submit (create two orders):

- `GET` / `PUT` / `DELETE` / `HEAD` / `OPTIONS` — retried on everything.
- `POST` / `PATCH` — retried only on a **connect timeout** (provably before the
  request body was sent) and on a retryable *status response* (the server
  answered, so the request was not processed twice). **Not** retried on a
  read-timeout, where the server may already have processed the request.
- `retryNonIdempotent=true` opts a `POST`/`PATCH` into full retry — use it only
  when the endpoint carries an idempotency key.

A status not in `retryableStatuses` is terminal on the first response, including
a non-retryable 5xx such as `501`.

### Retry budget

Worst-case budget = `retryCount × (readTimeout + retryDelay)`. With the defaults
that is `3 × (30000 + 2000)` = 96 s, and it holds a job-executor thread the whole
time. The worst case only hits when every attempt times out; a fast response
consumes far less. For latency-sensitive calls, lower `retryCount` /
`readTimeout` and size the job-executor pool for the concurrent connector tasks
you expect.

### After retries are exhausted

The final outcome is classified normally: a declared business status →
BUSINESS_ERROR; anything else (undeclared status, transport failure) → SYSTEM
fault → incident.
