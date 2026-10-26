# Five-Day Hackathon Plan

Dates are placeholders until the event window is confirmed. Each day ends with a green build and a short demo.

| Day | Theme | Deliverables | Exit check |
|---|---|---|---|
| **1** | Foundations and integrations (done) | Core engine, 20 tools, Gemini orchestrator, Google/Tavily/NASA/Open-Meteo providers, Room, WorkManager alerts | `./gradlew :core-engine:test :app:testDebugUnitTest :app:assembleDebug` green |
| **2** | Prove it live | Real keys in `local.properties`; live smoke test of every provider and the Gemini tool loop; fix parser/model-id surprises; run on emulator | "Walk 12 km this afternoon" answered with real data |
| **3** | UI | Dashboard polish, chat, alerts and history screens, settings (units, quiet hours, activity defaults), onboarding with permissions | Full flow without touching code |
| **4** | Map and standout features | Maps SDK screen with air-quality tile overlay and heat-map layer, route-conditions view, nearby-relief pins, Health Connect permission flow | Map demo works offline-tolerantly |
| **5** | Polish and ship | Demo video (under 3 minutes), screenshots in README, release APK, pitch deck, final docs and changelog, bug bash | Submission checklist complete |

## Demo script (target 2:30)
1. Open app: live conditions and recommendation for the user's location (20 s).
2. Switch activity to Running: advice and coolest window change (20 s).
3. Ask: "Is it safe to run 5 km at 4 pm in Lekki?" Show tool trace, AQI, news (45 s).
4. Map: heat layer plus air-quality overlay plus nearby shade (30 s).
5. Background alert fires with quiet-hours and dedup explanation (20 s).
6. Close on the honesty angle: unplug network, show cached label and missing-data handling (15 s).

## Submission checklist
- [ ] README links: demo video, APK, deck
- [ ] Team table complete, license added
- [ ] Keys removed from repo history and APK built with demo-only restricted keys
- [ ] CHANGELOG up to date
- [ ] What-we-built-during-the-hackathon statement in README
