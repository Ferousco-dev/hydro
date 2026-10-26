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
  - `tools/` — `HydroToolRegistry`, JSON schema declarations, argument validators, capability gates, and the 6 built-in tools.
  - `HydroEngine.kt` — Top-level coordinator wiring all engine services together.
- **`:app` (`com.ferousco.hydro.*`)** — Android application module:
  - `data/local/` — Room SQLite entities (`EnvironmentalRecordEntity`, `HeatAssessmentEntity`), `EnvironmentalHistoryDao`, `HydroDatabase`, and `RoomEnvironmentalHistoryStore`.
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

`HydroToolRegistry` exposes a closed, schema-validated set of 6 tools for future on-device AI orchestration:

| Tool | Required Capabilities | Responsibility |
|---|---|---|
| `get_current_weather` | `WEATHER_PROVIDER_READ` | Fetch normalized current observation with freshness & provenance |
| `get_hourly_forecast` | `WEATHER_PROVIDER_READ` | Fetch normalized hourly forecast (`1..168` hours), strictly marked `FORECAST` |
| `get_historical_weather` | `WEATHER_PROVIDER_READ` | Fetch past reanalysis observations (`<= 31` days, non-future dates) |
| `calculate_heat_assessment` | `DETERMINISTIC_ANALYSIS` | Run deterministic NOAA/NWS Heat Index & WHO UV assessment |
| `compare_forecast_periods` | `WEATHER_PROVIDER_READ`, `DETERMINISTIC_ANALYSIS` | Compare candidate activity departure windows across forecast hours |
| `query_local_history` | `LOCAL_HISTORY_READ` | Query local records near a coordinate and run compatibility-validated comparisons |

Security & policy boundaries:
- Rejects unregistered tool names (`TOOL_NOT_REGISTERED`), unknown parameters (`additionalProperties = false`), wrong JSON types, oversized JSON (`>32 KB`), deep nesting (`>8`), and out-of-range coordinates/dates/durations before execution.
- Enforces host-controlled `ToolPermissionGate` capabilities (`PERMISSION_DENIED`) and bounded per-tool timeouts (`TOOL_TIMEOUT`).
- Provides no arbitrary code execution, no arbitrary URL/host parameters, and no arbitrary file-path access.

### On-Device Open-Weight Model Integration (`google/gemma-3n-E2B-it-litert-lm`)

Hydro targets **`google/gemma-3n-E2B-it-litert-lm`** (`gemma-3n-E2B-it-int4.litertlm`, ~3.66 GB int4 weights with float activations, 2B effective active parameters, 8 GB minimum device RAM, 4,096-token Android LiteRT-LM context window, text + vision + audio input) executed via **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android:0.18.0`):

- **`:core-engine` (`com.ferousco.hydro.core.ai.*`)** — Pure Kotlin, runtime-agnostic orchestration:
  - `Gemma3nModelSpec` & `LocalModelArtifactValidator` — Validates local `.litertlm` package presence, extension, integrity, and device RAM (`>= 8 GB` for E2B; sibling `E4B` spec provided for `>= 12 GB` devices).
  - `OnDeviceLlmClient` — Clean interface decoupling `:core-engine` from Android JNI libraries while enforcing text-before-media ordering (`orderedTextFirstForLiteRt()`) for multimodal prompts.
  - `HydroGemmaOrchestrator` — Enforces zero LLM arithmetic via low-temperature (`temperature = 0.15`) grounded system instructions, supports both native LiteRT-LM `ToolCall` responses and structured JSON `{"tool_call": ...}` blocks across bounded rounds (`maxToolRounds = 4`), appends mandatory Open-Meteo CC-BY 4.0 and cache disclosures when omitted, and degrades cleanly to `HydroAssistantOutcome.ModelUnavailable` when weights are not yet provisioned on-device.
- **`:app` (`com.ferousco.hydro.ai.litert.*`)** — Android LiteRT-LM adapter:
  - `LiteRtGemma3nClient` — Initializes `com.google.ai.edge.litertlm.Engine` on `Dispatchers.IO` using LiteRT-LM's 3-stage cascading fallback (`Preferred GPU/NPU Multimodal` $\rightarrow$ `CPU Multimodal` $\rightarrow$ `CPU Text-Only`) and configures `AndroidManifest.xml` optional `<uses-native-library>` entries (`libOpenCL.so`, `libvndksupport.so`).
  - `HydroLiteRtOpenApiTool` — Adapts every `HydroToolRegistry` descriptor directly to `com.google.ai.edge.litertlm.OpenApiTool`.
- **Provisioning Gated Weights Locally (Never Committed to Git)**:
  1. Accept the **Gemma Terms of Use** at `https://huggingface.co/google/gemma-3n-E2B-it-litert-lm` and download `gemma-3n-E2B-it-int4.litertlm`.
  2. Push to app-private external storage during development:
     ```bash
     adb push gemma-3n-E2B-it-int4.litertlm /sdcard/Android/data/com.ferousco.hydro/files/models/gemma-3n-E2B-it-int4.litertlm
     ```
     (`*.litertlm`, `*.task`, `*.tflite`, and `models/` are gitignored.)

### Model Sizing for 4 GB – 12 GB RAM Phones & Hosted Open-Weight API Option

Because `gemma-3n-E2B-it-int4.litertlm` (`3.66 GB`) requires **8 GB+ device RAM**, teams targeting **4 GB – 6 GB RAM** phones have two plug-and-play paths (both compatible with `OnDeviceLlmClient` and `HydroGemmaOrchestrator` with zero changes to weather, math, planning, or tool-validation code):

1. **Lighter On-Device `.litertlm` Models (same `LiteRT-LM 0.18.0` runtime)**:
   - **`litert-community/Qwen3-0.6B`** (`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`, **329 MB**, ~650–950 MB peak RAM, Apache 2.0 ungated, 4K context, native tool calling in LiteRT-LM `0.18.0`) — recommended default for **4 GB RAM** devices.
   - **`litert-community/gemma-3-270m-it`** (`gemma3-270m-it-q8.litertlm`, **304 MB**, ~510 MB peak RAM) or **`google/functiongemma-270m-it`** (**289 MB**) — best lightweight option if restricted strictly to the Google Gemma family.
   - **`litert-community/Gemma3-1B-IT`** (`gemma3-1b-it-int4.litertlm`, **584 MB**, ~1.1–1.3 GB peak RAM) — strong mid-tier option for **6 GB RAM** phones.
2. **Hosted Open-Weight Model APIs (0 MB on-device download)**:
   - Implement `OnDeviceLlmClient` against an OpenAI-compatible `/v1/chat/completions` endpoint serving open-weight checkpoints (`google/gemma-3-27b-it`, `Qwen/Qwen3-32B`, `meta-llama/Llama-3.3-70B-Instruct`) via a thin backend proxy (never hardcode secret API keys in the Android APK). All 6 deterministic Hydro tools continue to run locally on the phone.

---

## 8. Running Tests

### Standard Gradle Environment (with Maven Central & Google Maven access)

```bash
gradle :core-engine:test :app:testDebugUnitTest
```

### Offline / Restricted Sandbox Verification

When Maven repositories are unreachable, `:core-engine` and `:app` data-layer unit tests can be compiled with `kotlinc -Werror` and executed directly on the JVM:

```bash
./scripts/verify-core-engine.sh
```

All core domain tests use `FakeWeatherProvider`, `FakeHttpTransport`, and `MutableClock` and run completely offline without live weather API calls or an LLM runtime.

---

## 9. Current Limitations & Build Blockers

1. **Thermal Model Scope**: NOAA/NWS Heat Index assumes shaded conditions and light wind (~2.5 m/s) for a reference adult walking lightly. Full Wet-Bulb Globe Temperature (WBGT) is not computed because standard forecast APIs do not directly provide black-globe temperature without additional radiative-convective modeling assumptions.
2. **Route Microclimates**: Activity planning evaluates gridded weather forecasts (`~1–25 km` model resolution) and does not infer street-level tree/building shade, surface albedo, elevation profile, or personal physiological limits.
3. **Open-Meteo Commercial Licensing**: Default Open-Meteo endpoints are restricted to non-commercial usage under CC-BY 4.0; commercial distribution requires configuring an authorized backend proxy backed by a paid Open-Meteo plan.
4. **Sandbox Gradle / Android SDK Network Blocker**: Full `./gradlew :app:assembleDebug` requires downloading the Gradle distribution (`services.gradle.org`), Android Gradle Plugin / Android SDK artifacts (`dl.google.com`), and Room/Compose dependencies (`repo.maven.apache.org`), which are blocked by restricted sandbox egress rules. All `:core-engine` production and test sources and `:app` Room persistence sources have been verified with `kotlinc 2.1.21 -Werror` and executed on OpenJDK 21.0.8.
