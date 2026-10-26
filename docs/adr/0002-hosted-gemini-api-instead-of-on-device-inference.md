# ADR-0002: Hosted Gemini API instead of on-device inference

- **Status:** Accepted (supersedes the original on-device plan)

## Context
The original design targeted an on-device Gemma 3n model through LiteRT-LM. The artifact is several GB, device support varies, and the tool-calling quality is unmeasured. Hackathon time is five days.

## Decision
Use the hosted Gemini API through the `LlmClient` interface. Remove the LiteRT client and dependency. Keep the interface and benchmark harness so a different model can be measured and swapped.

## Consequences
+ Fast to ship, reliable tool calling, small APK. - Needs network and a key; prompts leave the device (mitigated: stateless calls, no coordinates beyond tool args).
