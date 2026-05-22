# Testing — rest-datasonnet connector

The connector is **test-friendly but not test-aware** — no `testMode` flag, no
mock branch, no environment sniffing. Mocking happens *outside* the connector, at
the HTTP boundary.

---

## 1. Test tiers

Build and run them in this order — cheapest and most fundamental first:

1. **Pure unit — `StatusClassifier`** — HTTP status (and transport failures) plus
   `businessErrorStatuses` → SUCCESS / BUSINESS_ERROR / SYSTEM fault, and the
   retryability decision. No engine, no HTTP. Zero infrastructure.
2. **Pure script — `DataSonnetMapper`** — drive each `.ds` script through the
   `Mapper` with sample input; cover the cache, the concurrency case, and the
   defensive input-type branches.
3. **Connector unit** — drive `RestDataSonnetRequest` against WireMock; assert
   the output parameters, the error codes, and which failures throw vs return.
4. **Process** — deploy a BPMN with the wrapper subprocess, WireMock the
   endpoint, assert the instance takes the correct path per outcome.

Tiers 1 and 2 catch the riskiest new logic with no setup. Tier 3 is the
load-bearing proof — see §4.

---

## 2. WireMock at the HTTP boundary

WireMock runs a real local HTTP server; the connector makes a real HTTP call to
it, exercising the actual HTTP client, retry loop, classification, and DataSonnet
mapping. One tool covers the whole taxonomy:

| Test target | WireMock stub |
|---|---|
| Real success value | `200` + JSON body |
| Declared business status → boundary event | `withStatus(404)`, `businessErrorStatuses=404` |
| Undeclared status → incident | `withStatus(401)`, `businessErrorStatuses` empty → connector throws |
| Retryable 5xx → retry loop | `withStatus(503)`, or scenario states fail-then-succeed |
| POST + read-timeout → NOT retried | `withFixedDelay(ms)` past `readTimeout`, method `POST` |
| Cross-host redirect → not followed | `302` to another host → connector does not follow |
| Response-mapping failure → returned as data | `200` + malformed JSON |
| Oversize response → incident | `200` + body larger than `maxResponseBytes` |

---

## 3. Where the mock is "defined"

The connector's `url` input is expression-driven (`${restBaseUrl}/orders`). The
**same BPMN file** runs in production and test — only the base URL variable
changes. Production resolves `restBaseUrl` from `application.properties`; the test
starts WireMock and passes `restBaseUrl = wm.baseUrl()`.

```java
@RegisterExtension
static WireMockExtension wm = WireMockExtension.newInstance()
    .options(wireMockConfig().dynamicPort()).build();

@Test
void notFound_declaredBusiness_routesToBoundaryEvent() {
    wm.stubFor(post("/orders").willReturn(
        aResponse().withStatus(404).withBody("{\"message\":\"no such order\"}")));

    ProcessInstance pi = runtimeService().startProcessInstanceByKey("orderSync",
        Map.of("input", inputData, "restBaseUrl", wm.baseUrl()));

    assertThat(pi).hasPassed("handleNotFound");   // took the REST_ERROR_404 path
}
```

---

## 4. The load-bearing test

`shouldReturn404AsBusinessErrorData()` (tier 3, against WireMock, with
`businessErrorStatuses=404`):

- no exception is thrown;
- `restOutcome = BUSINESS_ERROR`;
- `restErrorCode = REST_ERROR_404`;
- `statusCode = 404`;
- `restError` carries the `errorMapping` result.

If a Connect SPI connector cannot turn a declared business failure into
deterministic output data, the whole errors-as-data architecture is wrong. Write
this early. Its sibling, `shouldRaiseIncidentForUndeclared401()`, proves the other
half: an undeclared status throws.

---

## 5. Test engine setup

The test process engine must parse `<camunda:connector>`, so its config
(`camunda.cfg.xml`) needs the Connect plugin alongside the Spin plugin:

```xml
<property name="processEnginePlugins">
  <list>
    <bean class="org.camunda.spin.plugin.impl.SpinProcessEnginePlugin"/>
    <bean class="org.camunda.connect.plugin.impl.ConnectProcessEnginePlugin"/>
  </list>
</property>
```

The connector module on the test classpath is discovered by `ServiceLoader`
automatically — no registration code.

---

## 6. Coverage requirements (from the 2026-05-22 eng review)

Every codepath has a test. Specific gaps the eng review added to the plan:

- Idempotency retry branches — GET+read-timeout retries; POST+read-timeout does
  not; POST+connect-timeout does; `retryNonIdempotent=true` opts in.
- Input validation — empty `url`, bad `method`, negative numbers → incident.
- `DataSonnetMapper` — compiled-`Mapper` cache hit/miss; concurrent `transform()`;
  the three input-type branches (String / SpinJsonNode / other).
- Business-vs-system classification — a declared status routes, the same status
  undeclared becomes an incident.
- `asyncBefore` + `R0/PT0S` — a mapping failure raises an immediate incident with
  zero job retries.
- `maxResponseBytes` — an oversize response → incident, engine heap stable.
- `request.attempts` — total invocations; `1` when not retried.
