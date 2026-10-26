# ADR-0001: Deterministic engine, AI only orchestrates

- **Status:** Accepted

## Context
Hydro gives safety-relevant guidance (heat, UV, air). A language model can mis-calculate or invent values.

## Decision
All numbers (heat index, UV category, window ranking, hydration, urgency, alert thresholds) are computed by tested Kotlin in `:core-engine`. The LLM may only pick registered tools and phrase the result. The system prompt forbids mental math.

## Consequences
+ Reproducible, unit-testable safety logic; model can be swapped. - More code than letting the model reason; some answers feel less free-form.
