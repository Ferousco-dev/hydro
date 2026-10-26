# ADR-0007: API keys in BuildConfig for the hackathon, proxy later

- **Status:** Accepted (temporary)

## Context
There is no backend and no time to build one in five days.

## Decision
Read keys from `local.properties` or the environment at build time and inject into `BuildConfig`. Use restricted, capped, demo-only keys. Document that production must proxy Gemini, Tavily and Google through a server.

## Consequences
+ Zero infrastructure. - Keys are extractable from the APK; must not ship publicly.
