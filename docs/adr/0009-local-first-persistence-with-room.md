# ADR-0009: Local-first persistence with Room

- **Status:** Accepted

## Context
Historical comparison and forecast verification need stored records; users own their location history.

## Decision
Store observations, forecasts, assessments and preferences in Room with retention policy. No custom cloud database.

## Consequences
+ Private, offline-capable. - No cross-device sync.
