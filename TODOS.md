# TODOS

Deferred work, captured with enough context to pick up cold.

## Multi-format request/response bodies (XML, CSV)

**What:** Let request and response bodies be XML or CSV, not only JSON, via a
`contentType` / `bodyMediaType` parameter passed through to DataSonnet.

**Why:** v1 is JSON-only. Many enterprise REST (and SOAP-adjacent) APIs return
XML. DataSonnet already supports XML and CSV natively through its
`bodyMediaType` hint, so the connector's reach is artificially narrowed by the
JSON-only choice. The transformation engine is already a dependency.

**Pros:** Widens the set of APIs the connector can talk to; most of the
machinery already exists in DataSonnet.

**Cons:** More content-type branching; larger test surface (each format needs
its own success and failure tests); the error context body field becomes
format-dependent.

**Context:** v1 deliberately scoped to JSON (Codex finding #4 in the
2026-05-22 eng review: media-type semantics were undefined; v1 settles them as
JSON-only). The connector reads the response body, hands it to DataSonnet as
`payload`. Multi-format means threading a media-type hint from an input
parameter (and/or the HTTP `Content-Type` response header) into the
`DataSonnetMapper` call.

**Depends on / blocked by:** v1 shipping first; the JSON-only content-type
handling (eng-review task T11) being in place as the baseline.
