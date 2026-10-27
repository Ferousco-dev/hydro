# Changelog

All notable changes to Hydro are recorded here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project is pre-1.0 and versions are not yet tagged.

## [Unreleased]

### Changed
- **Now panel hierarchy.** The temperature is now the anchor (72 sp, coloured by how warm it is, separate from the risk assessment); the risk pill and "Feels like" sit beside it, the headline and coolest window follow, and the four readings share one surface with larger values instead of four cards. Panel corners are 24 dp.
- **Trends.** A live weather card (temperature, sky, humidity, wind, hour-by-hour strip) sits above the heat chart. The chart has a left scale with dotted guides and can be touched or dragged to read any hour.
- **Weather animation.** A sun (colour follows heat risk, kept soft so it never glares), a moon with its phase at night, and rain or snow over the map, drawn with plain Canvas code so it works on every Android version. One on/off switch on Alerts turns it off.
- **Popups redesigned.** About, Colour key and Pin now share one `HydroDialog`: icon badge, centred title and message, content, a full-width primary button and an optional quiet secondary one. It fades and scales in, floats with a soft shadow, and closes on a tap outside. The Colour key gained a five-step colour bar and one-line meanings.

### Added
- **Trips (the "context manager").** Search "Where to?" with live suggestions, tap a place (or long-press the map) and the panel becomes a trip view: route, time and distance, a Walk / Bike / Drive switch, a risk pill, plain-language advice (water in 500 ml bottles, what to wear, when it is cooler), a heat chart for the destination, and a back button. Changing the mode re-plans everything.
- **Trip-aware chat.** An "Ask about this trip" box in the panel, and the full Ask chat, both know the destination, mode, route and advice. The assistant also writes a short briefing automatically (shown when it arrives; the deterministic advice never waits for it).
- **Pinned places.** Pin a trip's destination with your own name ("Work"); pins show as chips and map markers and open the trip view with advice in one tap. Stored in the saved-locations preference.
- **Wearables.** A Health data card on Alerts connects Health Connect (permission flow, latest heart rate and steps, Disconnect). When connected, a trip panel can add a gentle "From your watch" suggestion from a fresh heart-rate reading (`WearableAdvisor`, tested, never diagnostic). The assistant cannot read health data unless the user turns on "Let the assistant use it" (off by default).
- **AI glow.** A soft, slowly turning colour ring around the panel while the assistant works.
- `TripAdvisor` (core, tested) and `TripPlanner` / `TripController` / `TripContext` (app). `HydroEngine.routingProvider` is exposed so the real route can be drawn.
- **Map-first UI** (Jetpack Compose + Google Maps SDK): full-screen map, search, activity chips, floating layer and locate buttons, a draggable Now panel, and a **floating tab bar** (Map, Trends, Ask, Alerts).
- **Trends screen** with next-24-hours heat bars and the hottest and coolest hour. The colour key lives in a popup opened from an info icon at the top right.
- **Ask screen** completed: centred suggestions, a message field pinned above the keyboard and tab bar (multi-line, Enter or the on-screen Send key sends, Shift+Enter adds a line), three-dot typing indicator with live step text ("Checking air quality"), replies typed out once, a Stop button, "Try again" on failure, New chat, and follow-up questions that remember the conversation. Answers are written in plain language with a short "Used weather and air quality" caption. A 90-second limit and a "Still working on it" hint stop the chat from waiting forever.
- **Alerts screen** redesigned as grouped cards: a master on/off card, urgency levels with plain explanations, and quiet hours with time steppers. Credits and the disclaimer now live in an About popup opened from an info icon at the top right.
- **Map layers:** forecast heat grid and Google air-quality tiles; long-press the map to plan a trip with a route-conditions card.
- Design system: tokens, capsule/12dp/24dp shape language, Phosphor icon set, Apple-style activity indicator, skeleton loading, `docs/UX.md` and ADR-0010.
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
- The tab bar is always visible and rises above the keyboard instead of hiding.
- `HydroOrchestrator.respond` accepts an `onToolStarted` callback for live progress, and `HydroOrchestratorConfig.showSourceAttribution` can switch off provider attribution lines for consumer UIs.
- All user-facing text is plain language: provider names, formula names, tool names and raw error messages no longer appear on screen. Errors show friendly messages; credits and the medical disclaimer moved to Alerts, About.
- Recommendation copy rewritten in plain words ("No heat risk right now").
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
