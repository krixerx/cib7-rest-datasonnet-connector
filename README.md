# rest-datasonnet connector

[![CI](https://github.com/krixerx/cib7-rest-datasonnet-connector/actions/workflows/ci.yml/badge.svg)](https://github.com/krixerx/cib7-rest-datasonnet-connector/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-11%2B-blue)
![CIB seven](https://img.shields.io/badge/CIB%20seven-2.1.0-orange)

> A CIB seven (Camunda 7 fork) **Connect SPI connector** that calls a REST API
> from a BPMN service task — with DataSonnet request / response / error mapping
> and a business-vs-system error model.

Build the connector once. From then on, every REST integration is **pure BPMN** —
a service task, inline DataSonnet scripts, error routing — with nothing compiled
or deployed on the engine side. A modeler ships an integration without ever
opening an IDE.

## Why

A normal Camunda 7 REST integration means a `JavaDelegate`: write Java, compile
it, deploy it to the engine, and repeat for every endpoint. This connector
removes that loop. Deploy the connector once; every integration after that is a
`<camunda:connector>` block inside the BPMN — a URL, a method, and three
DataSonnet scripts that shape the request, the response, and the error.

## Features

- **Pure-BPMN integrations** — no Java, no compile step per endpoint.
- **DataSonnet mapping** — request, response and error transforms, written
  inline in the service task or loaded from the classpath.
- **Business-vs-system error model** — you declare which non-2xx statuses are
  *business* outcomes (a 404 "not found" routes to a boundary event) versus
  *system* faults (a 401, a 500, a network drop → an engine incident).
- **Idempotency-aware retry** — `GET` / `PUT` / `DELETE` retried freely;
  `POST` / `PATCH` retried only when it is provably safe.
- **Bounded and safe** — per-call timeouts, a response-size cap, redirects off.
- **Camunda Modeler element template** — configure the whole connector from a
  properties panel instead of hand-editing XML.

## Requirements

- A **CIB seven 2.1.0** engine with the Connect process-engine plugin enabled.
- **Java 11+**.

## Install

Not yet on Maven Central (it is on the [roadmap](docs/roadmap.md)). Build from
source:

```bash
git clone https://github.com/krixerx/cib7-rest-datasonnet-connector.git
cd cib7-rest-datasonnet-connector
mvn package
```

Then put the connector and its runtime dependencies on the engine classpath:

- `target/rest-datasonnet-connector-0.1.0-SNAPSHOT.jar`
- its runtime dependencies — gather them with
  `mvn dependency:copy-dependencies -DincludeScope=runtime` (they land in
  `target/dependency/`).

`httpclient5` and the Connect SPI are `provided` scope — the engine already
supplies them.

Finally, import `element-templates/rest-datasonnet.json` into your Camunda
Modeler so the connector appears in the element-template picker.

## Usage

### 1. Add the connector to a service task

Apply the **REST DataSonnet Connector** element template to a service task, or
write the `<camunda:connector>` block directly:

```xml
<bpmn:serviceTask id="invokeOrderApi" camunda:asyncBefore="true">
  <bpmn:extensionElements>
    <camunda:connector>
      <camunda:connectorId>rest-datasonnet</camunda:connectorId>
      <camunda:inputOutput>
        <camunda:inputParameter name="url">${restBaseUrl}/orders</camunda:inputParameter>
        <camunda:inputParameter name="method">POST</camunda:inputParameter>
        <camunda:inputParameter name="source">${orderInput}</camunda:inputParameter>
        <camunda:inputParameter name="businessErrorStatuses">404,409</camunda:inputParameter>
        <camunda:inputParameter name="requestMapping"><![CDATA[
          /** DataSonnet version=2.0 */
          { customerId: payload.customer.id }
        ]]></camunda:inputParameter>
        <camunda:outputParameter name="restOutcome">${restOutcome}</camunda:outputParameter>
        <camunda:outputParameter name="result">${result}</camunda:outputParameter>
      </camunda:inputOutput>
    </camunda:connector>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

### 2. Map data with DataSonnet

Each mapping script receives the relevant data as `payload` and returns JSON.
A request mapping:

```jsonnet
/** DataSonnet version=2.0 */
{
  customerId: payload.customer.id,
  lines: std.map(function(item) {
    sku: item.code,
    quantity: item.qty
  }, payload.items)
}
```

All three scripts (`requestMapping`, `responseMapping`, `errorMapping`) are
optional — omit one for an identity passthrough.

### 3. Route the outcomes

The connector returns `restOutcome` — `SUCCESS`, `BUSINESS_ERROR`, or
`RESPONSE_MAPPING_FAILED` — and throws for system faults, which become engine
incidents. An embedded subprocess with an exclusive gateway on `${restOutcome}`
and error boundary events turns the business outcomes into catchable BPMN errors.

**See [`samples/order-sync.bpmn`](samples/order-sync.bpmn)** for a complete
worked process: the connector service task plus the wrapper subprocess that
routes a 404 and a 409 to their handler tasks.

## Documentation

| Doc | Covers |
|---|---|
| [design.md](docs/design.md) | Architecture, the SPI constraint, locked decisions |
| [configuration.md](docs/configuration.md) | Every input / output parameter, the retry & timeout model |
| [error-handling.md](docs/error-handling.md) | The failure taxonomy and the business-vs-system split |
| [bpmn-modeling.md](docs/bpmn-modeling.md) | The wrapper-subprocess pattern and the element template |
| [testing.md](docs/testing.md) | Test tiers and engine setup |
| [roadmap.md](docs/roadmap.md) | v1 scope, later milestones, open items |

## Build & test

```bash
mvn verify
```

76 tests across three tiers — unit, connector-integration (against a stub HTTP
server), and process-level (the connector running inside an embedded CIB seven
engine). CI builds and tests on Java 11 and 17.

## Status

**v1, in development** (`0.1.0-SNAPSHOT`). The connector is built and tested at
all three tiers; it is not yet published to Maven Central, nor exercised in a
production deployment. See [roadmap.md](docs/roadmap.md) for v1 scope and the
open items.

## License

[Apache License 2.0](LICENSE) — a permissive open-source license, and the norm
across the Camunda / CIB seven ecosystem.

## Contributing

Issues and pull requests welcome. The codebase is small and the design is
documented under [`docs/`](docs/) — start with [design.md](docs/design.md).
