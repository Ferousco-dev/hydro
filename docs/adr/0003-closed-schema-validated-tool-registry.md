# ADR-0003: Closed, schema-validated tool registry

- **Status:** Accepted

## Context
Letting a model call arbitrary functions or URLs is a security and reliability risk.

## Decision
Expose a fixed set of tools, each with a JSON schema (`additionalProperties = false`), declared capabilities, a timeout and documented error codes. Reject anything else before execution.

## Consequences
+ Predictable, auditable, safe against injected instructions. - Adding a tool requires a descriptor and tests.
