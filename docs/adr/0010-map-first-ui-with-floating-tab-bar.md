# ADR-0010: Map-first UI with a floating tab bar

- **Status:** Accepted (supersedes an earlier in-panel segmented switcher)

## Context
The product document makes the map the home screen with a draggable intelligence panel. Our first layout put Now, Trends, Ask and Alerts in a segmented control inside that panel. It left too little room, made the assistant input compete with the panel, and hid whole features behind a small control.

## Decision
Use the Google Maps SDK (consistent with the Google data we already call) and a **floating capsule tab bar** with four destinations: Map, Trends, Ask, Alerts. Map keeps the search bar, activity chips, layers and the Now panel. Trends, Ask and Alerts are full screens drawn over the map, which stays composed so the camera and layers are preserved. The tab bar hides while the keyboard is open.

## Consequences
- More room for each screen; the assistant input is always pinned and visible.
- Familiar navigation, and it still respects the "floating, not fixed" Apple-Maps principle.
- The map is kept alive under the other tabs (a small memory cost) so returning is instant.
- Google data may only be shown on a Google map, so the map provider is not swappable without revisiting data terms.
