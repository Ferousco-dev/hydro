# Product Requirements Document: Hydro

| | |
|---|---|
| Status | MVP, hackathon build |
| Owners | Feranmi Oresajo (mobile), Emerald (AI integration) |
| Last updated | 2026-10-26 |

## 1. Summary
Hydro is an Android companion that turns raw environmental data (heat, humidity, UV, air quality, pollen, news) into **a personal decision**: should I go, when, what do I carry, where can I cool down. Numbers come from tested code; the AI only chooses tools and explains.

## 2. Problem
Weather apps report conditions but leave interpretation to the user. In hot, humid regions (our first market: Nigeria and West Africa), the difference between "34 °C" and a 41 °C feels-like with high UV and poor air is the difference between a fine walk and heat exhaustion. People also lack a quick way to find shade, cooling or medical help nearby, or to see whether today is unusual.

## 3. Target users
| Persona | Need |
|---|---|
| Outdoor worker / commuter | Safest time window, water plan, when to take breaks |
| Runner / cyclist / walker | Best departure time and route conditions |
| Parent / carer of vulnerable people | Early warning when heat, air or pollen become risky |
| Traveller in an unfamiliar city | Quick local context and nearby relief |

## 4. Goals and non-goals
**Goals (MVP)**
1. Answer "Should I go out / when / what should I prepare?" with a cited, grounded answer.
2. Surface heat index, UV, air quality, pollen and relevant news for the user's place.
3. Proactively alert on meaningful deterioration without spamming.
4. Work with partial data and say plainly what is missing.

**Non-goals**
- Medical diagnosis or personalised physiological risk scoring.
- Claiming a route is shaded/safer without data.
- A custom backend (post-MVP: key proxy only).
- On-device LLM inference (dropped, ADR-0002).

## 5. Requirements
### Functional
| ID | Requirement | Status |
|---|---|---|
| F1 | Current conditions with heat index, UV, provenance and freshness | Done |
| F2 | Activity-aware recommendation: urgency, ordered actions, coolest window | Done |
| F3 | Natural-language assistant that calls approved tools only | Backend done, chat UI basic |
| F4 | Geocode a named place and use it in every tool | Done (tool) |
| F5 | Air quality (EPA/universal AQI, pollutants, health advice) | Done (tool) |
| F6 | Pollen forecast | Done (tool, region-limited) |
| F7 | Nearby shade, indoor cooling, water, medical | Done (tool, needs Google key) |
| F8 | Route-aware conditions along a real route | Done (Google Routes) |
| F9 | Heat-map layer for a radius | Done (data); map rendering pending |
| F10 | Local news on heat, air, floods, storms | Done (tool, needs Tavily key) |
| F11 | Local memory and "today vs yesterday" | Done |
| F12 | Proactive background alerts with quiet hours, dedup, thresholds | Done |
| F13 | Optional wearable context (Health Connect) | Data source done; permission UI pending |
| F14 | Interactive map screen with overlays | Not started |
| F15 | Alerts/history/settings screens | Not started |

### Non-functional
- **Honesty:** never fabricate or zero-fill missing data; distinguish observed / forecast / modelled / derived.
- **Privacy:** history stays on device; only a place name goes to the news provider; logs redact coordinates and credentials.
- **Resilience:** every provider has an explicit unavailable path; cached data is labelled.
- **Performance:** tool calls bounded by timeouts; typical answer under ~10 s on a good connection.
- **Safety:** informational only; deterministic thresholds; no medical claims.

## 6. Success metrics (demo and judging)
- A live question answered end to end with visible tool traces.
- Tool-selection accuracy on the benchmark prompt suite (`HYDRO_CORE_V1`).
- Zero fabricated values across the missing-data test scenarios.
- Alert precision: no duplicate alerts inside the dedup window.

## 7. Risks
| Risk | Mitigation |
|---|---|
| API keys extractable from the APK | Acceptable for the demo; server proxy post-MVP (ADR-0007) |
| Provider coverage gaps (pollen, air quality in parts of Africa) | Explicit failure messages; fall back to free providers |
| Gemini tool-calling regressions | Benchmark harness; model id is configuration |
| Background work delayed by Android | Documented as best-effort |
| Google Maps Platform cost | Per-request cost is small for a demo; restrict and cap the key |

## 8. Scope for the five-day hackathon
See [ROADMAP.md](ROADMAP.md).
