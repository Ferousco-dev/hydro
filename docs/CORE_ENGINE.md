# Hydro Core Engine — Architecture & Technical Reference

This document describes the implementation of Hydro's core environmental intelligence engine (`:core-engine`) and its Android Room integration (`:app`).

---

## 1. Engine Architecture

Hydro separates pure environmental domain intelligence from Android UI and language-model runtimes:

- **`:core-engine` (`com.ferousco.hydro.core.*`)** — Pure Kotlin/JVM module with zero Jetpack Compose, Android framework, or LLM runtime dependencies.
  - `model/` — Strongly typed domain quantities, units, provenance, observations, forecasts, assessments, activity plans, and historical records.
  - `error/` — Structured `EngineError` taxonomy and `EngineResult<T>` combinators.
  - `logging/` — `DiagnosticLogger` and `SanitizingDiagnosticLogger` with automatic coordinate/token redaction.
  - `json/` — `SafeJsonCodec`, a bounded RFC-8259 parser/serializer confined strictly to external I/O boundaries.
  - `provider/` & `provider/openmeteo/` — `WeatherProvider`, `OpenMeteoWeatherProvider`, internal `OpenMeteoResponseMapper`, `HttpTransport`, and cache-aware `DefaultWeatherRepository`.
  - `assessment/` — `HeatAssessmentEngine` implementing deterministic public-health calculations.
  - `planning/` — `ActivityPlanningService` comparing candidate departure windows across hourly forecasts.
  - `history/` — `EnvironmentalHistoryStore`, `InMemoryEnvironmentalHistoryStore`, `JsonFileEnvironmentalHistoryStore`, and `EnvironmentalHistoryRepository`.
  - `memory/` — `ForecastVerificationCalculator`: descriptive forecast-versus-observation error over records already stored locally; `HistoricalBaselineSelector`/`HistoricalComparator`: same-clock-time-yesterday selection and unit-normalized deltas; `HistoricalComparisonService`: an explicit latest-observation-versus-yesterday result with a stated unavailable reason instead of `null`.
  - `quality/` — `DataConfidence`: the single provenance/staleness confidence rule shared by the proactive, recommendation, and notification paths.
  - `recommendation/` — `RecommendationEngine`: deterministic urgency, ordered actions, evidence, limitations, and the coolest contiguous forecast window; `RecommendationService`: the fetch → assess → recommend assembly behind `HydroEngine.recommendForLocation`.
  - `preferences/` — `UserPreferences` (units, alert settings, activity defaults, consented personalisation), `UserPreferencesStore`, `InMemoryUserPreferencesStore`, and the versioned `UserPreferencesCodec`.
  - `proactive/` — `ProactiveDecisionEngine`: deterministic threshold evaluation that turns heat/UV assessments into at most one alert per signal, then hands delivery to `create_notification`.
  - `integration/` — Host boundaries the engine cannot implement itself: `LocationSource`, `RoutingProvider`, `HeatMapProvider`, `WearableDataSource`, `NotificationSink`, plus their explicit unavailable defaults and `NotificationPolicy`.
  - `tools/` — `HydroToolRegistry`, JSON schema declarations, argument validators, capability gates, and the 13 built-in tools.
  - `HydroEngine.kt` — Top-level coordinator wiring all engine services together.
- **`:app` (`com.ferousco.hydro.*`)** — Android application module:
  - `data/local/` — Room SQLite entities (`EnvironmentalRecordEntity`, `HeatAssessmentEntity`, `UserPreferencesEntity`), their DAOs, `HydroDatabase` (schema v2), `RoomEnvironmentalHistoryStore`, and `RoomUserPreferencesStore`.
  - `location/` — `DeviceLocationProvider` (last-known `LocationManager` fix with capture time/accuracy) and `AndroidLocationSource` (its `LocationSource` adapter).
  - `notifications/` — `AndroidNotificationSink`: a standard and a critical channel, runtime-permission handling, and explicit `Rejected`/provider-error receipts. It only delivers; all policy lives in `:core-engine`.
  - `monitoring/` — `ProactiveMonitorWorker` (WorkManager `CoroutineWorker`), `ProactiveMonitorScheduler` (one unique 30-minute periodic job), and `ProactiveMonitorLogic` (pure near-term peak selection and previous-category storage keys).
  - `logging/AndroidDiagnosticLogger.kt` — Bridges `SanitizingDiagnosticLogger` to Android `Log`.
  - `MainActivity.kt` — Existing Compose screen preserved without premature UI redesign.

---

## 2. Domain Models

All environmental quantities are represented via `MeasuredQuantity<U : PhysicalUnit>` wrapped in `FieldAvailability<U>`:

- **`GeoLocation`**: Validated WGS84 latitude `[-90, 90]`, longitude `[-180, 180]`, optional elevation `[-500, 10000] m`, optional IANA `ZoneId`, Haversine distance (`distanceKilometersTo`), and privacy-redacted summaries.
- **Physical Units (`Units.kt`)**:
  - `TemperatureUnit` (`CELSIUS`, `FAHRENHEIT`, `KELVIN`)
  - `PercentageUnit` (`PERCENT`)
  - `SpeedUnit` (`METERS_PER_SECOND`, `KILOMETERS_PER_HOUR`, `MILES_PER_HOUR`, `KNOTS`)
  - `IrradianceUnit` (`WATTS_PER_SQUARE_METER`)
  - `UvIndexUnit` (`WHO_INDEX`)
- **Provenance & Nature (`EnvironmentalValue.kt`, `EnvironmentalDataSource.kt`)**:
  - Every `MeasuredQuantity` preserves `value`, `unit`, `validAt`, `location`, `source`, and `ValueNature` (`OBSERVED`, `FORECAST`, or `DERIVED`).
  - `EnvironmentalObservation` rejects any `FORECAST` quantity at construction; `HourlyForecastPoint` requires `FORECAST` quantities at construction.
  - `DataAcquisitionMethod` distinguishes `STATION_MEASUREMENT`, `GRIDDED_MODEL_NOWCAST`, `NUMERICAL_WEATHER_FORECAST`, `HISTORICAL_REANALYSIS`, and `DETERMINISTIC_ENGINE_CALCULATION`.

---

## 3. Weather Provider Configuration (Open-Meteo)

`OpenMeteoWeatherProvider` integrates two verified official Open-Meteo endpoints:

1. **Forecast & Current Conditions (`https://api.open-meteo.com/v1/forecast`)**:
   - Requests only required variables: `temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,uv_index,shortwave_radiation,is_day,weather_code`.
   - Requests `timeformat=unixtime` and IANA `timezone` (`auto` or location's `ZoneId`) so local times remain accurate across Daylight Saving Time transitions.
   - Open-Meteo's `current` block is explicitly tagged `ObservationKind.MODEL_ASSIMILATED_CURRENT` and `QualityFlag.MODEL_DERIVED_CURRENT_CONDITION` so 15-minute model assimilation is never misrepresented as an on-site station thermometer.
2. **Historical Weather Archive (`https://archive-api.open-meteo.com/v1/archive`)**:
   - Requests only supported reanalysis variables (`temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,shortwave_radiation,is_day,weather_code`).
   - Because `/v1/archive` does not provide hourly `uv_index`, `uvIndex` is explicitly mapped to `FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)`.

### Licensing & Credentials

- Open-Meteo's free public APIs are licensed under **CC-BY 4.0** for **non-commercial use only** (<10,000 calls/day; see <https://open-meteo.com/en/terms>). Every Open-Meteo `EnvironmentalDataSource` carries CC-BY 4.0 license and attribution metadata.
- Commercial deployments require a paid Open-Meteo plan routed through an authorized backend proxy. **No API keys or secrets are embedded in the repository or APK.**

---

## 4. Deterministic Assessment Methods & Sources

All numerical thresholds and equations execute deterministically inside `HeatAssessmentEngine` — never inside a language model.

### Primary Thermal Indicator: NOAA / US National Weather Service (NWS) Heat Index

- **Method ID**: `NOAA_NWS_ROTHFUSZ_SR_90_23`
- **Sources**:
  - Rothfusz, L. P. (1990), *NWS Southern Region Technical Attachment SR 90-23: The Heat Index "Equation"*.
  - NOAA Weather Prediction Center (WPC), *The Heat Index Equation* (<https://www.wpc.ncep.noaa.gov/html/heatindex_equation.shtml>).
  - Steadman, R. G. (1979), *The Assessment of Sultriness*, Journal of Applied Meteorology, 18(7), 861–873.
  - NOAA / NWS Heat Index Caution Classifications (<https://www.weather.gov/ama/heatindex>).
- **Procedure**:
  1. Convert dry-bulb air temperature to Fahrenheit ($T$) with relative humidity in percent ($RH$).
  2. Compute Steadman's simple approximation averaged with $T$:
     $$HI_{\text{simple}} = 0.5 \times \left(T + 61.0 + 1.2(T - 68.0) + 0.094 \cdot RH\right)$$
     If $HI_{\text{simple}} < 80.0^\circ\text{F}$, use $HI_{\text{simple}}$.
  3. Otherwise apply the 9-term Rothfusz polynomial with official NWS low-humidity ($RH < 13\%$ and $80^\circ\text{F} \le T \le 112^\circ\text{F}$) and high-humidity ($RH > 85\%$ and $80^\circ\text{F} \le T \le 87^\circ\text{F}$) adjustments.
  4. Classify continuous $HI_F$ into:
     - `BELOW_CAUTION_THRESHOLD`: $< 80.0^\circ\text{F}$ ($< 26.67^\circ\text{C}$)
     - `CAUTION`: $80.0^\circ\text{F}$ to $< 90.0^\circ\text{F}$ ($26.67^\circ\text{C}$ to $< 32.22^\circ\text{C}$)
     - `EXTREME_CAUTION`: $90.0^\circ\text{F}$ to $< 103.0^\circ\text{F}$ ($32.22^\circ\text{C}$ to $< 39.44^\circ\text{C}$)
     - `DANGER`: $103.0^\circ\text{F}$ to $< 125.0^\circ\text{F}$ ($39.44^\circ\text{C}$ to $< 51.67^\circ\text{C}$)
     - `EXTREME_DANGER`: $\ge 125.0^\circ\text{F}$ ($\ge 51.67^\circ\text{C}$)

### Supplementary Radiative Indicator: WHO / WMO Global Solar UV Index

- **Method ID**: `WHO_WMO_GLOBAL_SOLAR_UVI_2002`
- **Source**: WHO / WMO / UNEP / ICNIRP (2002), *Global Solar UV Index: A Practical Guide*.
- Evaluated only when `uvIndex` is `Available`: `LOW` (`<3`), `MODERATE` (`3–<6`), `HIGH` (`6–<8`), `VERY_HIGH` (`8–<11`), `EXTREME` (`11+`).
- Shortwave solar irradiance and 10m wind speed are reported as contextual `ContributingFactor` entries (noting NWS guidance that direct sunlight can raise perceived Heat Index by up to ~8.3°C / 15°F above shade values) without inventing an undocumented composite risk equation.

---

## 5. Missing-Data Behaviour

- Missing or invalid weather variables are represented as `FieldAvailability.Unavailable(reason, detail)` (`NULL_IN_PROVIDER_RESPONSE`, `NOT_PROVIDED_BY_SOURCE`, `FAILED_VALIDATION`, `NOT_REQUESTED`, `UNSUPPORTED_FOR_ENDPOINT`). Zero (`0.0`) or sentinel values are **never** substituted.
- In `HeatAssessmentEngine`:
  - Missing `air_temperature` or `relative_humidity` returns `HeatAssessment.Incomplete` with no computed index or category.
  - Missing optional variables (`uv_index`, `solar_radiation`, `wind_speed`, `feels_like_temperature`) produce a `HeatAssessment.Supported` Heat Index while listing every omitted variable in `missingInformation`.
- In `ActivityPlanningService`:
  - Candidate windows missing any hourly forecast step or lacking required inputs for any hour in `[departure, departure + duration)` are returned as `CandidateWindowEvaluation.IncompleteCoverage` and excluded from ranking. If no candidate has full coverage, `ActivityPlanningOutcome.InsufficientData` is returned.
- In `EnvironmentalHistoryRepository`:
  - Variable deltas are computed only for variables `Available` in both records; unavailable variables are listed in `uncomparableVariables`.

---

## 6. Data Freshness & Caching

- `DataQualityAndFreshness` tracks `retrievedAt`, `evaluatedAt`, `referenceDataTime`, `age`, `dataLag`, `deliveryOrigin` (`LIVE_NETWORK`, `LOCAL_CACHE`, `LOCAL_HISTORY_DB`), `freshnessState` (`FRESH`, `AGING`, `STALE`, `HISTORICAL_RECORD`), `completenessRatio`, and `QualityFlag`s.
- Default `FreshnessPolicy`:
  - Current observations: `FRESH` $\le 30\text{ min}$, `AGING` $\le 60\text{ min}$, `STALE` $> 60\text{ min}$ (or observation lag $> 90\text{ min}$).
  - Hourly forecasts: `FRESH` $\le 3\text{ h}$, `AGING` $\le 6\text{ h}$, `STALE` $> 6\text{ h}$.
- `DefaultWeatherRepository` caches recent responses in bounded LRU stores. When offline or encountering transient provider errors under `CachePolicy.PREFER_FRESH_FALLBACK_CACHE`:
  - `deliveryOrigin` is set to `DeliveryOrigin.LOCAL_CACHE`.
  - `QualityFlag.SERVED_FROM_CACHE` is attached and `isCurrentAndLive` is strictly `false`.
  - Freshness is re-evaluated against the current `Clock` without resetting `retrievedAt`.
- `HeatAssessmentEngine` and `ActivityPlanningService` reject `STALE` inputs by default unless `allowStaleInput` / `allowStaleForecast` is explicitly enabled.

---

## 7. Controlled AI Tool Interfaces

`HydroToolRegistry` exposes a closed, schema-validated set of 13 tools for AI orchestration:

| Tool | Required Capabilities | Responsibility |
|---|---|---|
| `get_weather` | `WEATHER_PROVIDER_READ` | Fetch normalized current observation with freshness & provenance |
| `get_hourly_forecast` | `WEATHER_PROVIDER_READ` | Fetch normalized hourly forecast (`1..168` hours), strictly marked `FORECAST` |
| `get_historical_weather` | `WEATHER_PROVIDER_READ` | Fetch past reanalysis observations (`<= 31` days, non-future dates) |
| `calculate_heat_risk` | `DETERMINISTIC_ANALYSIS` | Run deterministic NOAA/NWS Heat Index & WHO UV assessment |
| `compare_forecast_periods` | `WEATHER_PROVIDER_READ`, `DETERMINISTIC_ANALYSIS` | Compare candidate activity departure windows across forecast hours |
| `query_memory` | `LOCAL_HISTORY_READ` | Query local records near a coordinate, run compatibility-validated comparisons, read saved preferences, compute forecast-versus-observation error, and compare the latest observation with yesterday |
| `get_current_location` | `LOCATION_READ` | Return the platform's cached fix with capture time, accuracy, provider, and mock flag; stale/future fixes are rejected |
| `analyze_anomaly` | `WEATHER_PROVIDER_READ`, `LOCAL_HISTORY_READ` | Fetch its own observation and compare it against a same-hour local baseline and the issued forecast |
| `get_route_conditions` | `ROUTING_READ`, `WEATHER_PROVIDER_READ`, `DETERMINISTIC_ANALYSIS` | Estimate route distance/duration, sample conditions at arrival times, and report the worst NWS category |
| `get_heat_map` | `HEAT_MAP_READ` | Return a gridded layer with resolution, valid time, legend, bounds, and provenance |
| `get_wearable_data` | `WEARABLE_READ` | Read only user-authorised wearable/Health Connect metrics with source, timestamp, unit, and quality |
| `create_notification` | `NOTIFICATION_WRITE` | Apply user alert preferences, dedup, quiet hours, rate limiting, and confidence thresholds before submitting an alert |
| `plan_hydration` | `WEATHER_PROVIDER_READ`, `DETERMINISTIC_ANALYSIS` | Compute a fluid/sodium range and drinking schedule from a forecast heat assessment |

`get_hourly_forecast` and `compare_forecast_periods` are Hydro extensions beyond the product
document's §6 table; the other eleven names match it.

Security & policy boundaries:
- Rejects unregistered tool names (`TOOL_NOT_REGISTERED`), unknown parameters (`additionalProperties = false`), wrong JSON types, oversized JSON (`>32 KB`), deep nesting (`>8`), and out-of-range coordinates/dates/durations before execution.
- Enforces host-controlled `ToolPermissionGate` capabilities (`PERMISSION_DENIED`) and bounded per-tool timeouts (`TOOL_TIMEOUT`).
- Provides no arbitrary code execution, no arbitrary URL/host parameters, and no arbitrary file-path access.

### Integration Boundaries & Unavailable-Provider Semantics

`integration/DeviceIntegration.kt` defines the host boundaries the core engine cannot implement
itself. Every boundary has an explicit unavailable default, so a missing integration produces
`UNSUPPORTED_CALCULATION` with a machine-readable `reason` instead of fabricated data:

| Boundary | Default | Behaviour |
|---|---|---|
| `LocationSource` | `UnavailableLocationSource` | `reason = LOCATION_SOURCE_NOT_CONFIGURED` |
| `RoutingProvider` | `GreatCircleRoutingProvider` | Always available; returns `GREAT_CIRCLE_ESTIMATE` plus explicit limitations |
| `HeatMapProvider` | `UnavailableHeatMapProvider` | `reason = HEAT_MAP_PROVIDER_NOT_CONFIGURED` |
| `WearableDataSource` | `UnavailableWearableDataSource` | `reason = WEARABLE_SOURCE_NOT_CONFIGURED` |
| `NotificationSink` | `UnavailableNotificationSink` | `reason = NOTIFICATION_SINK_NOT_CONFIGURED` |

`:app` wires `AndroidLocationSource` (backed by the `LocationManager` last-known fix) into
`HydroEngine.create`. Heat maps, wearables, and the notification sink remain unwired until their
platform integrations exist; the app does not fake them.

Deterministic policy is applied inside the tools, never delegated to the model or the platform:

- `analyze_anomaly` fetches its own observation, so the model cannot "analyse" invented readings. It
  needs at least 3 same-hour local records within 10 km before it will call anything anomalous.
- `get_route_conditions` defaults to a great-circle distance/duration estimate and says so; route
  conditions are sampled from the origin's hourly forecast grid, not a spatial route field.
- `get_heat_map` caps a request at 400 grid cells and refuses to interpolate an unsourced layer.
- `create_notification` enforces 3 alerts/hour, a 6-hour dedup window, quiet hours 22:00–07:00
  (CRITICAL may bypass), and minimum confidence 0.5 (0.8 for CRITICAL) *before* calling the sink. A
  suppressed alert is a successful call with `status: SUPPRESSED` and a reason, not an error the
  model should retry. Saved user preferences are applied on top (see below).
- `plan_hydration` computes every number from the forecast assessment, duration, intensity, and
  optional body mass; output is explicitly a planning estimate, not medical advice.

### Memory, Preferences & Forecast Verification

Memory is data, not a longer prompt. The product document's five record types are covered by
explicit, queryable structures rather than conversation history:

| Record type | Where it lives |
|---|---|
| Observed history | `StoredEnvironmentalRecord` (`OBSERVATION`, `HISTORICAL_REANALYSIS_OBSERVATION`) |
| Forecast history | `StoredEnvironmentalRecord` (`ISSUED_FORECAST`, retaining issue time) |
| Hydro assessments | `StoredHeatAssessmentRecord` |
| User preferences | `UserPreferences` |
| Wearable readings | `WearableSample` via `WearableDataSource` (authorised metrics only) |

**`UserPreferences`** (`preferences/UserPreferences.kt`) is a typed record with three groups:

- units — temperature, distance, volume;
- alerts — enabled, minimum severity, quiet hours, critical-during-quiet-hours, hourly ceiling,
  dedup window;
- activity — preferred categories, default intensity, body mass, home and saved locations.

`bodyMassKg`, `homeLocation`, and `savedLocations` are personal data. `UserPreferencesCodec.encodeForTool`
withholds them unless `consentedPersonalization` is `true`, and reports
`personalization_withheld: true` rather than pretending the fields do not exist. Units, alert
settings, and activity defaults are always available because they are not personal.

Persistence is a boundary, not a detail: `UserPreferencesStore` has an in-memory implementation for
the JVM/tests and a Room-backed implementation in `:app` (`user_preferences` table, schema v2).
Stored payloads are versioned JSON; decoding is strict about ranges and enums but ignores unknown
fields, and a corrupt or newer-schema payload is a `PERSISTENCE_FAILURE` rather than a silent reset
to defaults. `HydroEngine` exposes `loadUserPreferences`, `saveUserPreferences`, and
`updateUserPreferences` for the UI.

**Forecast verification** (`memory/ForecastVerification.kt`) answers "was the forecast accurate?"
with arithmetic over what is already on the device. A forecast is paired with an observation when:

- the forecast was issued at or before the observation's valid time,
- valid times are within ±60 minutes,
- locations are within the requested radius.

For each variable present on both sides it reports sample count, mean forecast, mean observed, signed
mean error (forecast − observed), MAE, and RMSE, plus lead-time min/mean/max. It is explicitly
*descriptive*: it does not retrain or bias-correct the provider, it never backfills missing hours,
and below 10 pairs it labels the metrics indicative rather than calibration-grade.

**Notification preferences** are enforced inside `CreateNotificationTool`, not by the model:

| Preference | Effect | Guard |
|---|---|---|
| `enabled = false` | `SUPPRESSED / USER_DISABLED` | — |
| `minimumSeverity` | `SUPPRESSED / BELOW_MINIMUM_SEVERITY` | — |
| `quietHoursStart` / `End` | Applied as the user's quiet window | Overrides the engine default window |
| `maxNotificationsPerHour` | Lowered ceiling | Cannot exceed the engine ceiling (`minOf`) |
| `dedupWindowHours` | Widened window | Cannot undercut the engine floor (`maxOf`) |
| `allowCriticalDuringQuietHours` | Critical bypass | Cannot enable a bypass the engine forbids (`&&`) |

The `policy` object in every `create_notification` result reports the effective values, including
`minimum_severity` and `user_preferences_applied`, so a suppressed alert can always be explained.
If the preferences store cannot be read, the engine policy is used unchanged and
`user_preferences_applied` is `false` — a persistence fault never silently loosens or blocks an
alert.

### Proactive Decision Engine

`proactive/ProactiveDecisionEngine` implements the product document's **Evaluate → Decide** stages.
It is a pure function over assessments Hydro already computed: no language model runs while the user
is idle, and no alert is generated from prose.

Inputs (`ProactiveEvaluationRequest`) are deliberately optional: a background refresh may have only
an observation, only a forecast, or both. Missing data produces an explicit rationale, never a guess.

| Signal | Threshold | Severity |
|---|---|---|
| `HEAT_THRESHOLD` | NWS `EXTREME_CAUTION` (rank 2) or worse | `WARNING`; `CRITICAL` at `DANGER` (rank 3) |
| `UV_THRESHOLD` | WHO `VERY_HIGH` (rank 3) or worse | `WARNING`; `CRITICAL` at `EXTREME` |
| `FORECAST_DETERIORATION` | Forecast category worsens since the previous evaluation | `WATCH`, and only when a planned outdoor activity is recorded |

Rules that keep the tray quiet and the output auditable:

- At most one alert per signal type; when both the observation and the forecast exceed a threshold,
  the more severe one wins and the other is mentioned in the rationale.
- A deterioration that also crosses the heat threshold is **not** sent twice; the rationale says the
  threshold alert already covers it.
- Confidence describes the *data*, not the danger: `OBSERVED + FRESH` = 0.95, `FORECAST + FRESH` =
  0.85, `AGING` = base − 0.2, `STALE`/`HISTORICAL_RECORD` = 0.4. Stale data therefore falls below the
  notification policy's 0.5 floor and is suppressed there rather than by a special case.
- Dedup keys are stable per day per ~1 km cell (`heat:<cell>:<date>`, `uv:<cell>:<date>`,
  `forecast-change:<cell>:<date>:<category>`), so re-running the check cannot spam.
- Every alert expires (two hours after its valid time), so a stale alert cannot be delivered late.

`HydroEngine.runProactiveCheck(request)` evaluates, then sends each alert through the **same**
`create_notification` tool the assistant uses, and returns a `ProactiveCheckResult` with a
`SUBMITTED`/`SUPPRESSED`/failure outcome per alert. One policy implementation, two callers.

#### Background monitoring (`:app`)

`ProactiveMonitorScheduler` enqueues one unique periodic `ProactiveMonitorWorker` job named
`hydro_proactive_monitor`: 30-minute interval with a 10-minute flex, a `CONNECTED` network
constraint, exponential backoff from 15 minutes, and `ExistingPeriodicWorkPolicy.UPDATE` so repeated
app launches update rather than stack jobs. It is scheduled from `HydroApplication.onCreate()`.

Each pass:

1. Resolves a place — a device fix no older than six hours, otherwise the saved home location. A
   stale fix is never used as a fallback because alerting for a city the user left is worse than
   silence. With neither, the pass finishes quietly.
2. Loads `UserPreferences`; if alerts are disabled it returns before any network call.
3. Fetches a current observation and a 12-hour forecast. If both fail it returns `Result.retry()`;
   if only one fails it evaluates what exists.
4. Assesses with `allowStaleInput = true`, so an old reading becomes a `Supported` assessment marked
   `STALE` (confidence 0.4) and is suppressed by policy rather than disguised as "no data".
5. Picks the worst forecast hour inside a six-hour near-term horizon. Past hours, hours beyond the
   horizon, and unsupported hours are dropped — never clamped or guessed.
6. Reads the previous forecast category from `SharedPreferences`, keyed by grid cell and local date,
   then writes the current one after a successful pass. Deterioration is therefore compared against
   the previous run, not yesterday's weather.
7. Calls `runProactiveCheck`. Because no planned outdoor activity is recorded yet (a UI concern),
   `activityCategory` is `null`, so sub-threshold forecast deterioration stays silent as designed.
8. Writes the observation, its assessment, and the six-hour forecast points to local history on a
   best-effort basis. IDs are deterministic, so repeated fetches upsert instead of duplicating; a
   persistence failure is logged and never turns a successful fetch into a failed pass. This is what
   lets forecast verification and the same-hour anomaly baseline see data from unopened sessions.

The worker contains no thresholds and no notification copy. `ProactiveMonitorLogic` is pure Kotlin
and unit-tested on the JVM; the worker is a thin adapter. WorkManager may defer execution (Doze,
battery saver), so Hydro never claims a check happened when it did not.

### Deterministic Guidance and Historical Comparison

Two more deterministic layers sit beside the proactive engine. Neither calls a model, and neither
may invent data to fill a gap.

**`RecommendationEngine` (`recommendation/`)** answers "what should I do?" rather than "should I be
interrupted?".

| Output | Rule |
|---|---|
| `urgency` | Heat category rank (0→`LOW`, 1→`MODERATE`, 2→`HIGH`, 3+→`CRITICAL`), raised by WHO UV `VERY_HIGH`/`EXTREME` or a worse forecast peak |
| `actions` | Ordered by seriousness: postpone/indoors → limit exertion → shift window → hydrate → rest breaks → sun protection → symptom check → check vulnerable people |
| `bestWindow` | Lowest peak severity, then lowest peak heat index; must cover the planned duration and never spans a forecast gap > 90 minutes |
| `confidence` | `DataConfidence.forInput` — the same rule the proactive path uses |
| stale input | A stale calm reading becomes `UNKNOWN`, adds `REFRESH_DATA`, and is never presented as an all-clear |

Every recommendation carries evidence (source, freshness, forecast peak, window) and limitations
(ambient threshold, not medical advice; missing UV; missing forecast coverage; no activity supplied).
The language model may rephrase this text for chat; it may not change the urgency or add an action.

**`RecommendationService` (`recommendation/`)** is the one-call assembly behind
`HydroEngine.recommendForLocation(...)`. It fetches the current observation and a lookahead-bounded
hourly forecast, assesses both, and hands them to `RecommendationEngine`.

- A failed observation fetch is returned as `EngineResult.Failure`; Hydro does not guess a
  recommendation from a forecast alone.
- A failed forecast fetch degrades to current-condition guidance and records the missing coverage as
  a limitation, so silence is never mistaken for calm weather.
- Stale current data is assessed with `allowStaleInput = true` so the engine can return `UNKNOWN`
  and ask for a refresh; stale forecast hours are dropped so an old forecast never becomes today's
  coolest window.
- The requested lookahead is honoured, not widened: only forecast hours inside
  `evaluatedAt + lookahead` are eligible. The forecast request is capped at 48 hours.

A screen that has already refreshed conditions can skip the second round-trip and call
`HydroEngine.recommend(request)` directly; that re-runs only the pure decision stage.

**`HistoricalBaselineSelector` / `HistoricalComparator` (`memory/`)** answer "how does today compare
with yesterday?".

- The baseline is the observation closest to the same clock time 24 hours earlier, within ±90
  minutes and 10 km. Forecast records are excluded because a forecast is not a measurement.
- Selection caveats are explicit: the result is a same-clock-time snapshot, not a climatological
  normal; reanalysis is labelled as such; distance and time offsets are reported.
- Comparison normalizes temperature to °C and wind to m/s, so a Fahrenheit baseline is not compared
  numerically against a Celsius current value.
- A variable missing on either side is absent, never zero-filled. Deltas are descriptive: Hydro does
  not attribute them to a cause or a long-term trend.
- `HistoricalComparisonService.latestVersusYesterday(...)` wraps the selector for the common question
  and returns `Available(summary)` or `Unavailable(NO_RECENT_OBSERVATION | NO_BASELINE)` with an
  explanation, so a caller can distinguish "I did not ask" from "there is no honest answer". It is
  exposed through `HydroEngine.compareLatestToYesterday(...)` and through `query_memory`'s
  `include_historical_comparison` argument, which reads only observation-compatible records from a
  bounded three-day pool rather than widening the caller's query.

### Language Model Layer

Hydro's orchestrator is deployment-agnostic: it talks to `LlmClient` and reads only a model's
identity (id, display name, context window, tool-calling style). Two implementations exist.

#### Default: Gemini API (hosted)

The app ships with the **Gemini Interactions API** as its language layer
(`LlmModelCatalog.DEFAULT` = `GEMINI_3_8_FLASH`, model id `gemini-3.8-flash`). Gemini 3.5 Flash-Lite
(`gemini-3.5-flash-lite`) and Gemini 4 Argon (`gemini-4-argon`) are catalogued as one-line swaps.

- **`:core-engine` (`com.ferousco.hydro.core.ai.*`)** — pure Kotlin, runtime-agnostic:
  - `LlmModelSpec` — sealed identity: `OnDeviceLlmModelSpec` (local artifact) and
    `HostedLlmModelSpec` (vendor endpoint). A hosted model has no artifact, RAM floor, or
    quantization, so those fields are absent rather than faked.
  - `LlmClient` — inference contract (`checkReadiness`, `initialize`, `generate`, `generateStream`).
  - `GeminiApiLlmClient` — maps the orchestrator's conversation to the Interactions wire format and
    back.
  - `HydroOrchestrator` — zero-LLM-arithmetic tool loop over `HydroToolRegistry`.
- **Request/response contract** (`POST https://generativelanguage.googleapis.com/v1beta/interactions`,
  header `x-goog-api-key`):

  | Direction | Shape |
  |---|---|
  | Tool declaration | `{"type":"function","name":…,"description":…,"parameters":{…}}` |
  | User turn | `{"type":"user_input","content":[{"type":"text","text":…}]}` |
  | Model asks for data | `steps[].type == "function_call"`, with `name`, `arguments`, `id` |
  | Tool result | `{"type":"function_result","name":…,"call_id":…,"result":[{"type":"text","text":"<tool JSON>"}]}` |
  | Final answer | `steps[].type == "model_output"` → `content[].text` |

  Hydro runs **statelessly** (`store: false`): the orchestrator owns history and replays the full
  step list each round, so Google retains no location-bearing prompt and the client is fully
  testable offline.

- **API key setup** (never committed):
  1. Create a key at <https://aistudio.google.com/apikey>.
  2. Add it to `local.properties` (gitignored):
     ```properties
     GEMINI_API_KEY=your-key-here
     ```
     or export `GEMINI_API_KEY` in the environment (used by CI).
  3. Rebuild. `app/build.gradle.kts` injects it as `BuildConfig.GEMINI_API_KEY` and
     `HydroApplication` wires `GeminiApiLlmClient` + `HydroOrchestrator`.
  4. Without a key the app still runs: `checkReadiness()` returns
     `LlmReadiness.CredentialsMissing` and the orchestrator returns
     `HydroAssistantOutcome.ModelUnavailable`. Every deterministic capability keeps working.

  > **Security:** anything embedded in an APK can be extracted. That is acceptable for a prototype
  > with no backend. A production build must proxy Gemini through a server that holds the key and
  > authenticate the app separately.

- **Deliberate limitations:** only text and tool results are sent (image/audio parts are rejected
  explicitly rather than guessed at, so the client advertises `TEXT_ONLY`); the Interactions API
  exposes `seed` and `max_output_tokens` but no temperature/top-k/top-p, so those sampling fields are
  not sent; streaming is not implemented yet (`generateStream` emits the single final answer).

#### On-device inference (removed)

The earlier on-device Gemma 3n / LiteRT-LM client was removed from `:app` (see
[ADR-0002](adr/0002-hosted-gemini-api-instead-of-on-device-inference.md)). The `LlmClient` interface
and benchmark harness stay model-agnostic, so a different hosted model can be measured and swapped.

#### Model benchmarking (`ToolCallBenchmarkHarness`)

`core-engine/.../ai/benchmark/ToolCallBenchmark.kt` runs a fixed, versioned prompt suite
(`HYDRO_CORE_V1`, 7 prompts) against any `LlmClient` and reports tool-selection accuracy,
schema-valid/malformed tool-call rates, latency (mean/p95), and per-case outcomes, serialisable via
`report.toJsonString()`. It needs no model file: scripted clients stand in for real inference, so the
measurement logic itself is unit-tested offline.

---

## 8. Running Tests

### Standard Gradle Environment (with Maven Central & Google Maven access)

The repository ships a Gradle wrapper; no global Gradle installation is required.

```bash
./gradlew :core-engine:test :app:testDebugUnitTest
```

To also build the debug APK:

```bash
./gradlew :app:assembleDebug
```

On macOS you can point `JAVA_HOME` at the JDK bundled with Android Studio (JDK 21):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core-engine:test :app:testDebugUnitTest :app:assembleDebug
```

`scripts/verify-core-engine.sh` is a thin wrapper around the same Gradle tasks.

### Test Coverage and Gaps

All core domain tests use `FakeWeatherProvider`, `FakeHttpTransport`, and `MutableClock` and run completely offline without live weather API calls or an LLM runtime.

- `:core-engine`: 118 JVM tests covering units, JSON parsing (including surrogate validation), heat index reference values, provider mapping/caching, planning/tie ranking, tool schemas, orchestration, the Gemini Interactions contract (request shape, `function_call`/`function_result` round-trip, error mapping), the tool-call benchmark harness, privacy sanitization, all seven C2 tools (location freshness, anomaly baselines, route sampling, heat-map bounds, wearable authorisation, notification policy, hydration math), the C3 memory layer (preferences codec round-trip/consent gating/corrupt-payload handling, `query_memory` preference projection, forecast-verification pairing and error metrics, and preference-driven notification suppression), the C4 proactive engine (thresholds, severity escalation, forecast deterioration, stale-data confidence, dedup through the real notification policy, and failure reporting), the deterministic recommendation engine (urgency escalation, stale-reading handling, coolest-window selection across forecast gaps, activity tailoring), the `RecommendationService` assembly (combined guidance, forecast-failure degradation, observation-failure propagation, stale handling, lookahead containment), and historical baseline selection/comparison (same-clock-time selection, distance rejection, unit normalization, missing-variable handling, and the `query_memory` latest-versus-yesterday projection with its unavailable reasons).
- `:app`: 19 JVM tests covering the Room history and preferences stores (via fake DAOs), the pure background-monitoring assembly rules (six-hour near-term window, unsupported-hour dropping, deterministic peak selection, and per-cell/per-date previous-category keys), and the scheduler contract (WorkManager's 15-minute floor, flex < interval, stable unique work name).
- **Not covered:** the actual Room SQL schema/indices (no instrumentation tests), the Compose UI, real device networking, live Gemini calls (the adapter is tested against a fake transport), WorkManager execution itself (the worker is not run under a test scheduler), The `:app` tests do not require notification permission, or an API key.

---

## 9. Current Limitations

1. **Thermal Model Scope**: NOAA/NWS Heat Index assumes shaded conditions and light wind (~2.5 m/s) for a reference adult walking lightly. Full Wet-Bulb Globe Temperature (WBGT) is not computed because standard forecast APIs do not directly provide black-globe temperature without additional radiative-convective modeling assumptions.
2. **Route Microclimates**: Activity planning evaluates gridded weather forecasts (`~1–25 km` model resolution) and does not infer street-level tree/building shade, surface albedo, elevation profile, or personal physiological limits.
3. **Open-Meteo Commercial Licensing**: Default Open-Meteo endpoints are restricted to non-commercial usage under CC-BY 4.0; commercial distribution requires configuring an authorized backend proxy backed by a paid Open-Meteo plan.
4. **Hosted-Only Language Layer**: The LLM path is the Gemini API, so it needs connectivity and sends the prompt, tool results, and coordinates to Google. The API key is embedded in the APK for this prototype. The app screen now exposes a basic chat backed by the orchestrator.
5. **In-Memory Weather Cache**: `DefaultWeatherRepository` caches in process memory only. Observations are persisted to Room, but Room is not yet used as an offline weather cache/fallback.
6. **Result-Schema Enforcement**: Tool argument schemas are enforced; `ToolDescriptor.resultSchema` is descriptive metadata and is not validated against tool output.
7. **Location**: `AndroidLocationSource` reads a last-known `LocationManager` fix and `get_current_location` enforces `max_age_seconds`, but there is no continuous tracking, background updates, or user-facing location picker yet.
8. **Partly Wired Platform Integrations**: The heat-map, wearable, and notification boundaries are interfaces with explicit unavailable defaults. `AndroidNotificationSink` is now wired (two channels, runtime permission, honest `Rejected` receipts), `HealthConnectWearableDataSource` (read-only, per-metric consent) and `OpenMeteoGridHeatMapProvider` (coarse model grid) are wired in the app/engine defaults; the permission screen and map rendering are still UI work. Without a permission or provider, tools fail with an explicit reason rather than returning placeholder data.
9. **Routing Accuracy**: `GreatCircleRoutingProvider` is the keyless fallback. With `GOOGLE_MAPS_API_KEY`, `GoogleRoutesProvider` supplies road/foot/bike/transit geometry and ETA. The fallback returns a straight-line distance and constant-speed duration, not road geometry, traffic, terrain, or accessibility-aware ETAs.
10. **Forecast Verification Is Descriptive**: `ForecastVerificationCalculator` reports error statistics from records that happen to be stored locally. It does not backfill missing hours, does not model diurnal bias, and does not retrain or bias-correct the provider. Pairing is limited to ±60 minutes and the requested radius.
11. **Preferences Have No UI Yet**: `UserPreferences` is persisted and honoured by the engine, but there is no settings screen; the app currently runs with defaults until the UI phase wires `HydroEngine.updateUserPreferences`.
12. **Background Monitoring Is Best-Effort**: `ProactiveMonitorWorker` runs on a 30-minute WorkManager interval, which Android may defer under Doze or battery saver. Hydro therefore cannot promise exact real-time monitoring. Sub-threshold forecast-deterioration alerts also stay silent until the UI records a planned outdoor activity, because a saved preference is not a plan.
