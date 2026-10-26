# ADR-0006: Explicit unavailability over fabrication

- **Status:** Accepted

## Context
Environmental advice that silently fills gaps can cause harm.

## Decision
Missing fields are `Unavailable`, never zero. Stale or cached data is labelled and never reported as live or as an all-clear. Unwired integrations fail with a reason. Satellite surface temperature is never shown as air temperature.

## Consequences
+ Trustworthy output. - More empty states in the UI to design.
