# BPMN modeling — rest-datasonnet connector

How to use the connector in a hand-modeled process: the service task, the
DataSonnet scripts, the wrapper subprocess that turns business-error data back
into catchable BPMN errors, and the Modeler element template.

---

## 1. The DataSonnet scripts

Three scripts per call site. `payload` is the DataSonnet input variable. They go
**inline** in the `<camunda:inputParameter>` values by default — see
[configuration.md](configuration.md). All three are optional.

**`*-request.ds`** — `payload` is the `source` input variable; the output is the
request body. Note the defensive type handling (see
[error-handling.md](error-handling.md) §6):

```jsonnet
/** DataSonnet version=2.0 */
local amount =
  if std.isNumber(payload.amount) then payload.amount
  else if std.isString(payload.amount) then std.parseInt(payload.amount)
  else error "amount must be numeric, got " + std.type(payload.amount);
{
  customerId: payload.applicant.id,
  amount: amount,
  items: payload.lineItems map (i) { sku: i.code, qty: i.quantity }
}
```

**`*-response.ds`** — `payload` is the 2xx body; the output becomes `result`:

```jsonnet
/** DataSonnet version=2.0 */
payload + { syncedAt: ds.datetime.now() }
```

**`*-error.ds`** — `payload` is the error context (see
[error-handling.md](error-handling.md) §3); the output becomes `restError`. It
runs only for **business errors** (a declared `businessErrorStatuses` status):

```jsonnet
/** DataSonnet version=2.0 */
{
  failed: true,
  status: payload.status,
  errorCode: payload.errorCode,
  reason:
    if payload.status == 404 then "Resource not found: " + payload.request.url
    else if payload.status == 409 then "Conflict — already exists"
    else "Request rejected (" + payload.status + ")",
  detail: payload.body,
  failedAt: ds.datetime.now()
}
```

> `DataSonnetMapper` owns the Spin boundary: a `SpinJsonNode` input is serialized
> to String before the `Mapper` runs. Re-wrap `result` with `S(...)` in an output
> parameter expression if downstream BPMN expressions expect Spin.

---

## 2. The wrapper subprocess pattern

The connector returns **business errors** as data and **throws** system faults.
A thrown system fault becomes an incident directly — it needs no modeling. Only
the returned outcomes need the wrapper, which converts business-error data into
catchable BPMN errors. Each REST call is modeled as an **embedded subprocess**:

```
+- Embedded subprocess "Call X API" ----------------------------------+
|  (start) -> [Service Task: <camunda:connector> rest-datasonnet]      |
|                          |                                          |
|                  (XOR gateway on ${restOutcome})                     |
|                   |- SUCCESS ----------------------------> (end)      |
|                   |- BUSINESS_ERROR -> (XOR on ${restErrorCode})     |
|                   |                     |- REST_ERROR_404 -> (Error End: 404) |
|                   |                     +- REST_ERROR_409 -> (Error End: 409) |
|                   +- RESPONSE_MAPPING_FAILED -> (Error End: RESP_MAP) |
+----------------------------------------------------------------------+
   < error boundary event REST_ERROR_404         -> [handle not found]
   < error boundary event REST_ERROR_409         -> [handle conflict]
   < error boundary event REST_ERROR_RESPONSE_MAPPING -> [manual recovery,
                                                          restRawResponse holds
                                                          the raw 2xx body]
```

- The wrapper only fans out the statuses the modeler **declared** in
  `businessErrorStatuses` — usually one or two, not seven. Undeclared failures
  (401, 403, 5xx, network) never reach the wrapper; they are incidents.
- An Error End Event `errorRef` is **static** — one end event per declared code
  plus one for `REST_ERROR_RESPONSE_MAPPING`.
- On every boundary path, `restError` carries the reason / detail; on the
  `REST_ERROR_RESPONSE_MAPPING` path, `restRawResponse` carries the raw body.

---

## 3. The element template

The connector ships a Camunda Modeler **element template** (`.json`) — a
first-class deliverable, not a footnote. It pre-fills `connectorId` and every
input / output parameter so the service task is configured in the Modeler's
properties panel, not by hand-editing XML.

The template also sets, on the connector service task:

- `asyncBefore="true"` — so the connector runs as a job; without it a system
  fault would not become an incident (see [error-handling.md](error-handling.md) §7).
- `failedJobRetryTimeCycle="R0/PT0S"` — so a system fault surfaces as an
  **immediate** incident with no wasted job retries.

A copy-paste **wrapper-subprocess fragment** ships alongside it: a ready-made
embedded subprocess with the gateway and Error End Events to drop in and
retarget. This is the v1 (Approach A) answer to the boilerplate. Milestone 2
replaces the copy-paste fragment with a reusable Call Activity — see
[roadmap.md](roadmap.md).

---

## 4. Scope boundary — no orchestration in the connector

Calling **multiple endpoints in one service task is out of scope.** Orchestration
belongs in BPMN — sequence flows, parallel gateways, compensation:

- In-connector orchestration breaks retry safety — a retry re-runs *all* calls,
  duplicating non-idempotent side effects.
- It breaks the single-outcome error model.
- It hides per-call progress, history, and incidents from Cockpit.

The connector's unit is **one logical REST interaction**. Multiple HTTP requests
are acceptable only as mechanics of one logical call (auth / token refresh,
pagination, a mandatory preflight), and even then are better modeled as named
connector features than freeform orchestration.
