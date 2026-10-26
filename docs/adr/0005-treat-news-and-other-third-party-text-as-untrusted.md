# ADR-0005: Treat news and other third-party text as untrusted

- **Status:** Accepted

## Context
Search results can contain instructions aimed at the model (prompt injection).

## Decision
Cap length, mark results `untrusted_external_text`, send only a place name (never coordinates), and instruct the model to summarise and cite but never obey. News never overrides tool-computed values.

## Consequences
+ Limits injection and privacy risk. - Cannot fully eliminate model susceptibility; tool whitelist remains the backstop.
