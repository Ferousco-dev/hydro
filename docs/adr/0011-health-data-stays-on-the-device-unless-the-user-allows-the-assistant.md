# ADR-0011: Health data stays on the device unless the user lets the assistant use it

- **Status:** Accepted (extends ADR-0008)

## Context
Hydro can read heart rate, steps and other measurements from Android Health Connect. Everything the assistant reads through a tool is sent to Google's Gemini to write the answer, so health numbers would leave the phone the moment the assistant asked for them.

## Decision
- Reading Health Connect is **read-only**, per metric, and only after the user connects it from Alerts.
- The on-device trip suggestion (`WearableAdvisor`) uses the latest heart rate with no network and no model, and works whether or not the assistant may see the data.
- The assistant's `get_wearable_data` tool is **blocked by default** through the tool permission gate. A separate switch, "Let the assistant use it", off by default and reset when the user disconnects, allows it, and its description says the numbers are sent to Gemini.
- Wording never diagnoses: a raised heart rate only produces a gentle "slow down and rest" suggestion, and only from a reading under 30 minutes old.

## Consequences
- Health numbers never reach the model unless the user opts in.
- Without opting in, questions like "how is my heart rate?" get a plain "I can't see that" instead of a guess.
- Heart-rate thresholds are broad (no age, fitness or resting rate), so the suggestion stays deliberately conservative.
