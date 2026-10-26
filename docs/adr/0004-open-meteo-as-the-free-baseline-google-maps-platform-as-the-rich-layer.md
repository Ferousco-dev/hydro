# ADR-0004: Open-Meteo as the free baseline, Google Maps Platform as the rich layer

- **Status:** Accepted

## Context
We want maximum data without making the app unusable when a key is missing.

## Decision
Open-Meteo, NASA POWER and the Open-Meteo geocoder work with no key. When `GOOGLE_MAPS_API_KEY` is present, Google supplies routing, geocoding, places, weather, air quality and pollen. Each provider sits behind an interface and fails explicitly when unavailable.

## Consequences
+ Graceful degradation and a free demo path. - Two providers for some data; cross-source disagreement must be reported, not hidden.
