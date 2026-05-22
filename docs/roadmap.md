# Roadmap — rest-datasonnet connector

Three approaches were weighed in the 2026-05-22 office-hours session. All three
ship the same connector; they differ in how a modeler avoids rebuilding the
error-routing wrapper for every REST call. The 2026-05-22 eng review then
reshaped the error model and locked the v1 details below.

---

## v1 — Approach A: connector + element template + copy-paste fragment

The smallest release that is genuinely complete and honours every locked decision
in [design.md](design.md) §2.

**Ships:**

- The Connect SPI connector — owns its `CloseableHttpClient` (Apache HttpClient
  5.x), idempotency-aware in-task retry, the business-vs-system error model,
  input validation. Six SPI classes + three helpers (`DataSonnetMapper`,
  `HttpInvoker`, `StatusClassifier`), see [design.md](design.md) §7.
- The `META-INF/services` provider registration.
- A Camunda Modeler **element template** (`.json`) — pre-fills `connectorId` and
  the input / output parameters, and sets `asyncBefore="true"` +
  `failedJobRetryTimeCycle="R0/PT0S"` on the service task. First-class scope.
- A copy-paste **wrapper-subprocess BPMN fragment** — the embedded subprocess
  with the `restOutcome` gateway and an Error End Event per declared business
  status plus one for `REST_ERROR_RESPONSE_MAPPING`.
- A runnable **sample project** — one BPMN calling a WireMock endpoint, routing
  SUCCESS / a declared business 404 / a `RESPONSE_MAPPING_FAILED`, and showing an
  undeclared 401 raising an incident.
- The four test tiers ([testing.md](testing.md)).

**Build order — error model first, then proof:**

1. **The business-vs-system error model** — the three-outcome shape
   (`StatusClassifier` and `execute()` both depend on it). Settle it before
   wiring anything.
2. `StatusClassifier` + pure unit tests.
3. `DataSonnetMapper` + pure script tests (cache, concurrency, input-type branches).
4. `HttpInvoker` (owned client, pool defaults, redirect policy, `maxResponseBytes`)
   + `RestDataSonnetConnector.execute()` + validation + the SPI classes.
5. The load-bearing tests — `shouldReturn404AsBusinessErrorData()` and
   `shouldRaiseIncidentForUndeclared401()` against WireMock.
6. The element template + wrapper fragment + sample + process tests.

---

## Milestone 2 — Approach B: Call Activity wrapper

Replace the copy-paste fragment with a reusable, separately-deployed BPMN **Call
Activity**. The wrapper exists once; a call site shrinks to a single Call Activity
node plus boundary events. Designed after the connector exists, because the
variable-mapping ergonomics are only designable well against a real connector.

---

## Milestone 3 — Approach C: OpenAPI-to-BPMN kit

A generator: feed it an OpenAPI spec, it emits the element template, the wrapper
fragment, the declared `businessErrorStatuses`, and starter inline DataSonnet
scripts per operation. The north star — a second artifact on top of the
connector, not a replacement for it.

---

## Open items

- [ ] **Full URL allowlist.** An engine-side allowlist of permitted host+scheme
      patterns, for operators of shared or internet-exposed engines. v1 ships the
      no-cross-host-redirect mitigation and the documented trust model
      ([design.md](design.md) §9); the allowlist is built if a multi-tenant
      adopter needs it.
- [ ] **Maven publishing namespace.** `org.cibseven.community` needs CIB seven
      community-hub coordination; otherwise an owned `io.github.<user>` namespace.
- [ ] **Confirm Spin input resolution** — verify against a CIB7 engine whether
      `<camunda:inputParameter>` hands the connector a `SpinJsonNode` or a String,
      so `DataSonnetMapper`'s defensive handling is built for the real shape.
- [ ] **HttpClient shutdown** — the owned client is engine-lifetime scoped with
      idle eviction; decide whether to also close it on engine shutdown. Low
      priority.
- [ ] **Publish to Maven Central** — a release workflow that GPG-signs and
      publishes the jar on a version tag. Needs a Central Portal account and a
      signing key configured as repository secrets. (Build + test CI is done —
      `.github/workflows/ci.yml`, a Java 11 + 17 matrix.)
- [ ] **Multi-format request/response bodies (XML, CSV)** — v1 is JSON-only;
      captured with full context in `TODOS.md`.

---

## Resolved — locked by the office-hours and eng-review sessions (2026-05-22)

- ~~Confirm the CIB seven version + Connect SPI namespace~~ → **CIB seven 2.1.0**;
  the SPI is the `org.cibseven.connect.*` namespace (the 2.x line moved off
  `org.camunda.connect.*`).
- ~~Confirm the Connect SPI artifact coordinates~~ →
  **`org.cibseven.connect:cibseven-connect-core:2.1.0`** (on Maven Central).
- ~~Pin the `datasonnet-mapper` version~~ → **2.5.2**, the latest stable in the
  javax-namespace 2.x line (matching CIB seven 2.x's javax engine), via the
  `datasonnet.version` property. The process-level test confirmed its Jackson
  coexists with the engine's — the broader convergence check stays its own item.
- ~~Pin the `httpclient5` version~~ → **5.5**, via the `httpclient5.version`
  property. Verified as the version CIB seven 2.1.0 ships — `cibseven-parent`
  2.1.0 sets `version.httpclient5` = 5.5. `provided` scope: relies on
  httpclient5 being on the engine classpath, where the connect modules put it.
- ~~Jackson dependency convergence~~ → Jackson **pinned to 2.15.0** via an
  imported `jackson-bom` (the `jackson.version` property); a maven-enforcer
  `dependencyConvergence` rule fails the build on any version split, with
  DataSonnet's third-party Scala / sjsonnet tree excluded. Shading DataSonnet's
  Jackson stays an unused fallback.
- ~~Inline vs `classpath:` default~~ → **inline**.
- ~~Delegate to the built-in `http-connector` vs own the client~~ → **the
  connector instance owns the client**.
- ~~Apache Camel for the REST calls~~ → **rejected**.
- ~~Connect SPI vs JavaDelegate~~ → **Connect SPI kept** (weighed in eng review).
- ~~Status-to-code taxonomy~~ → replaced by the **business-vs-system model**: the
  modeler declares `businessErrorStatuses`, each gets `REST_ERROR_<status>`;
  everything else is an incident.
- ~~Retry every method on timeout~~ → **idempotency-aware retry**.
- ~~Helper classes folded into the connector~~ → **kept separate** (testability).
- ~~SSRF: do nothing~~ → **no cross-host redirects + a documented trust model** in
  v1; the full allowlist deferred (open item above).
