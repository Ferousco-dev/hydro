# Security and Privacy

## Threat model summary
| Threat | Control |
|---|---|
| Prompt injection via news or place names | News is length-capped, labelled `untrusted_external_text`, and the system prompt forbids following it; tools are a closed set with validated arguments |
| Model invents tool or arguments | Registry rejects unknown tools and schema violations |
| Credential leakage in logs/URLs | `SanitizingDiagnosticLogger` redacts keys and coordinates; Google keys sent in `X-Goog-Api-Key` header where supported |
| Location exposure | Only a place name goes to Tavily; Gemini runs stateless; history is local |
| Spam notifications | Quiet hours, dedup window, hourly cap, severity floor |
| Over-collection of health data | Read-only Health Connect, per-metric grant, no estimation |
| Health data reaching the AI model | The assistant is blocked from the wearable tool by default; a separate off-by-default switch allows it and discloses that the numbers go to Gemini ([ADR-0011](adr/0011-health-data-stays-on-the-device-unless-the-user-allows-the-assistant.md)) |

## Known limits (hackathon)
- API keys are compiled into the APK via `BuildConfig` and are extractable. Use restricted, capped, demo-only keys. Production needs a server-side proxy (ADR-0007).
- `allowBackup=false` so local history is not exported by backup.
- No certificate pinning.

## Not medical advice
Hydro is informational. Wearable data cannot establish dehydration, core temperature or a diagnosis.

## Reporting
Open a private issue or contact the team before disclosing publicly.
