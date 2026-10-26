# Changelog

All notable changes to Hydro are recorded here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project is pre-1.0 and versions are not yet tagged.

## [Unreleased]

### Added
- **Four more Google Maps Platform tools** (registry now 24): `find_relief_within_reach` (Isochrones + Places: only places reachable in N real travel minutes), `get_area_insights` (Places Aggregate: green space, medical, indoor-cooling and supply counts and density), `get_elevation_profile` (min/max/ascent/descent), `get_time_zone`. Isochrones and Area Insights request shapes are unverified until the first live run.
- **Google Maps Platform integration** (one `GOOGLE_MAPS_API_KEY`): Routes API routing, Geocoding, Places (New) nearby relief, Weather (current + hourly), Air Quality (US EPA + universal AQI, pollutants, health advice) and Pollen forecast.
- **Tavily news tool** (`get_environmental_news`) with prompt-injection hardening: article text is length-capped, labelled untrusted, and only a place name is sent.
- **Free data tools:** Open-Meteo air quality (`get_air_quality`), NASA POWER solar/climate (`get_solar_climate`), Open-Meteo geocoder fallback.
- **Real heat map:** `OpenMeteoGridHeatMapProvider` samples a grid in one request and computes NWS heat index locally.
- **Health Connect wearable source** (read-only, per-metric permission, no estimation of missing values).
- **Four new tools** (`geocode_place`, `get_pollen`, `find_nearby_relief`, `get_google_weather`); the registry then held 20 tools.
- **App screen:** activity chips, a recommendation card (urgency, ordered actions, coolest window) and an "Ask Hydro" chat backed by the Gemini orchestrator.
- Notification-permission request on Android 13+.
- `docs/`: PRD, architecture, data sources, setup, security and privacy, five-day plan, and architecture decision records.
- `.env.example` documenting `GEMINI_API_KEY`, `TAVILY_API_KEY` and `GOOGLE_MAPS_API_KEY`.

### Changed
- Routing, geocoding and air quality use Google when a key is present and fall back to free providers otherwise.
- The orchestrator's system prompt now treats news as untrusted and labels air-quality/NASA values as modelled.
- Language layer is the hosted Gemini API only (see ADR-0002).

### Removed
- On-device LiteRT-LM / Gemma 3n client, its tests, the `litertlm` dependency and manifest native-library entries.

### Fixed
- Location permission granted mid-load no longer gets dropped (refresh is now queued).
- Alerts were silently rejected on Android 13+ because `POST_NOTIFICATIONS` was never requested.
- `DeviceLocationProvider` was recreated on every recomposition.

## [0.1.0] - Foundation (pre-hackathon)

### Added
- `:core-engine`: typed domain models with explicit units and `Available`/`Unavailable` field semantics, Open-Meteo adapter with cache fallback, NOAA/NWS Heat Index, WHO UV index, activity-window planner, deterministic recommendation engine, historical comparison, forecast verification, 13-tool schema-validated registry, model-agnostic orchestrator, Gemini API client, tool-call benchmark harness, privacy-redacting logger.
- `:app`: Room persistence (schema v2), last-known device location, WorkManager proactive monitoring every 30 minutes, notification channels, one current-conditions screen.
- CI workflow and verification script.
