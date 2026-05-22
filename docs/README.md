# rest-datasonnet connector — documentation

A custom CIB seven (Camunda 7 fork) **Connect SPI connector** that lets a
hand-modeled BPMN service task call an external REST API, with DataSonnet
request / response / error mapping and per-status error handling. Intended as a
shareable open-source community extension for CIB seven.

**The core idea:** build the connector once, and from then on every REST
integration is pure BPMN — a service task, inline DataSonnet scripts, error
routing — with nothing compiled or deployed on the CIB7 engine side. A modeler
ships an integration without ever opening an IDE.

## Documentation map

| Doc | What it covers |
|---|---|
| [design.md](design.md) | Purpose, architecture, the SPI constraint, locked design decisions, the HTTP client, packaging |
| [configuration.md](configuration.md) | Input / output parameters, the retry & timeout model |
| [error-handling.md](error-handling.md) | Failure taxonomy, HTTP-status → error-code mapping, mapping failures as incidents |
| [bpmn-modeling.md](bpmn-modeling.md) | The wrapper-subprocess pattern, the Modeler element template, the DataSonnet scripts |
| [testing.md](testing.md) | Test tiers, WireMock at the HTTP boundary, test engine setup |
| [roadmap.md](roadmap.md) | v1 scope and build order, milestones 2 & 3, open items |

## Status

| | |
|---|---|
| Status | Draft / Design — reviewed (office hours + eng review) |
| Date | 2026-05-22 |
| Target engine | CIB seven (Camunda 7 fork) — verified against the Connect SPI |
| Connector ID | `rest-datasonnet` |
| Java package | `org.cibseven.community.connector.rest` |
| v1 approach | Connector + Modeler element template + copy-paste wrapper fragment (see [roadmap.md](roadmap.md)) |

## How these docs were produced

The original design was a single file. On 2026-05-22 it went through a
YC-office-hours-style design session (split into the six focused docs above) and
then a full engineering review. Both rounds of decisions are folded into these
docs; [roadmap.md](roadmap.md) lists what was locked and what is still open. The
error model in particular changed during the eng review — it is now a
business-vs-system split, see [error-handling.md](error-handling.md).
