# Error handling & taxonomy — rest-datasonnet connector

The connector sorts every execution into one of three outcomes: SUCCESS, a
BUSINESS error, or a SYSTEM fault. The split between the last two is the heart
of the error model, and the modeler draws the line.

---

## 1. Business errors vs system faults

Not every non-2xx response means the same thing. A 404 when you look up an order
by ID is a real business outcome: the order does not exist, and the process
should branch on it. A 404 because the base URL is wrong is a defect. A 401 is a
bad credential an operator must fix. A 500 means the remote system is broken. The
HTTP status alone cannot tell a business outcome from a defect — only the
modeler, who knows what each call means, can.

So the modeler declares it, per task, with the `businessErrorStatuses` input
parameter (see [configuration.md](configuration.md)). The connector then
classifies every result:

- **SUCCESS** — HTTP 2xx, `responseMapping` ran cleanly.
- **BUSINESS error** — a non-2xx status the modeler listed in
  `businessErrorStatuses`. An expected business outcome. Returned as data, routed
  by the wrapper subprocess to a boundary event, the process branches. No
  incident.
- **SYSTEM fault** — everything else. A technical defect or an operational
  failure. The connector throws; the job fails; CIB7 raises an **incident** an
  operator resolves. Not routed as a business error.

---

## 2. What is a SYSTEM fault

- A non-2xx status **not** in `businessErrorStatuses` (401, 403, 500, an
  undeclared 404).
- A transport failure after retries are exhausted (connect failure, read
  timeout, TLS failure, DNS failure, connection refused).
- A request-mapping script failure — before any HTTP call (§4).
- An input-validation failure (§5).
- A response body larger than `maxResponseBytes` ([configuration.md](configuration.md)).

Every system fault throws and becomes an incident. The incident message names the
cause honestly: a DNS failure says "could not resolve host", not "timeout".

System faults do **not** need any wrapper-subprocess routing — they throw, and
the engine raises the incident directly.

---

## 3. Business errors — codes and the error context

A business error gets a `restErrorCode` of the form `REST_ERROR_<status>` —
`REST_ERROR_404`, `REST_ERROR_409`, `REST_ERROR_422` — one per declared status.
The connector runs `errorMapping` over the error context and returns:

- `restOutcome = BUSINESS_ERROR`
- `restErrorCode = REST_ERROR_<status>`
- `restError` — the `errorMapping` result
- `statusCode`, `responseHeaders`

Each declared business status needs a matching `<bpmn:error>` and Error End Event
in the wrapper subprocess (see [bpmn-modeling.md](bpmn-modeling.md)). Because the
modeler declares only the statuses that matter for a given call, that is usually
one or two, not seven.

The error context handed to `errorMapping` as the DataSonnet `payload`:

```json
{
  "status": 404,
  "statusText": "Not Found",
  "errorCode": "REST_ERROR_404",
  "body": { "...": "raw response body" },
  "headers": { "...": "response headers" },
  "request": { "url": "...", "method": "POST", "attempts": 1 }
}
```

`request.attempts` is the total number of HTTP invocations made (the first
attempt plus any retries); it is `1` for a business error that was not retried.

---

## 4. Mapping failures

**Request-mapping failure** — the `requestMapping` script throws or calls
`error()` before any HTTP call. No side effect happened. It is a SYSTEM fault:
the connector throws `RestMappingException`, an incident forms. The exception
carries which script failed, the DataSonnet line and message, and a snippet of
the offending input, so the Cockpit incident is actionable.

**Response-mapping failure — the one exception to "system fault throws".** The
HTTP call already returned 2xx, so the remote side effect *has happened*. If the
connector threw here, the raw response body would be lost, and an operator
re-running the task would cause a double submission. Under the Connect SPI form
the connector communicates by *either* returning data *or* throwing, never both
(see [design.md](design.md) §5) — so it cannot throw and also preserve the body.

Therefore a response-mapping failure does **not** throw. It returns:

- `restOutcome = RESPONSE_MAPPING_FAILED`
- `restErrorCode = REST_ERROR_RESPONSE_MAPPING`
- `restRawResponse` — the raw 2xx body, untouched
- `restError` — the mapping error detail (which script, line, message)

The wrapper subprocess routes `RESPONSE_MAPPING_FAILED` to its own boundary path
for manual recovery. This is the single documented case where a system-class
failure comes back as data instead of an incident, and it exists so the raw body
survives.

**Error-mapping failure** — if `errorMapping` itself fails, the connector falls
back to a default error object and the business error still routes. The failure
is logged loudly.

---

## 5. Input validation

Before any mapping or HTTP call, the connector validates its inputs: `url`
non-empty, `method` an allowed verb, timeouts and retry counts non-negative. A
validation failure is a SYSTEM fault: the connector throws
`RestConfigurationException`, an incident forms, and the message names the bad
parameter. This turns a forgotten `${restBaseUrl}` variable into a clear "url is
required" incident instead of a NullPointerException or a misrouted network
error.

---

## 6. Type mismatches in DataSonnet do not throw

DataSonnet (Jsonnet-based) is dynamically typed; `"5" + 1` evaluates to `"51"`
rather than failing. A string-where-integer-expected can silently corrupt data.
Defenses:

1. **Anticipated** variation — handle it inside the script with explicit type
   checks and coercion (see the `*-request.ds` example in
   [bpmn-modeling.md](bpmn-modeling.md)).
2. **Unanticipated** mismatch — the script must `error("...")` explicitly, so the
   failure is loud and deterministic → `RestMappingException` → incident.

---

## 7. The incident model depends on async configuration

A system fault becomes an *incident* only if the connector service task runs as a
job. A connector service task runs synchronously by default; an exception there
propagates to whoever started the process and rolls the transaction back, with no
incident. So the element template ships the connector service task with
`asyncBefore="true"` and `failedJobRetryTimeCycle="R0/PT0S"` — which makes a
system fault surface as an immediate incident with no wasted job retries (see
[bpmn-modeling.md](bpmn-modeling.md) §3).
