# Setup

## Requirements
JDK 17+ (Android Studio's bundled JDK works), Android SDK, emulator or device on Android 8.0+ (`minSdk 26`).

## API keys
Put keys in `local.properties` at the repo root (gitignored) or export them as environment variables. Android builds do **not** read `.env`.

| Key | Needed for | Required? | Where |
|---|---|---|---|
| `GEMINI_API_KEY` | Assistant | Optional | https://aistudio.google.com/apikey |
| `TAVILY_API_KEY` | News tool | Optional | https://app.tavily.com |
| `GOOGLE_MAPS_API_KEY` | Routes, geocoding, places, Google weather, air quality, pollen, map | Optional | https://console.cloud.google.com/google/maps-apis |

Enable these APIs on the Google project: **Routes API, Geocoding API, Places API (New), Weather API, Air Quality API, Pollen API, Isochrones API, Places Aggregate API, Maps Elevation API, Time Zone API**, and **Maps SDK for Android** for the map screen. Restrict the key (Android package + SHA-1, and API restrictions) and set a billing cap.

Without a key, the matching feature reports "unavailable"; the rest keeps working. Open-Meteo, NASA POWER and the Open-Meteo geocoder need no key.

## Build and test
```bash
./gradlew :core-engine:test :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:installDebug      # with an emulator/device attached
```

## Troubleshooting
- **Chat says assistant is offline:** `GEMINI_API_KEY` is empty in `BuildConfig`; rebuild after adding it.
- **403 from a Google API:** that API is not enabled for the key.
- **Pollen/air-quality "no coverage":** the provider does not cover that location; expected in some regions.
- **No alerts on Android 13+:** grant the notification permission.
