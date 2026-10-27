# UX and Design System

Hydro is map-first. The map is home; everything else is reachable from one compact floating tab bar.

## Screens
| Tab | What it shows |
|---|---|
| **Map** (home) | Full-screen map with a search capsule, activity chips, map-layer and locate buttons, and a draggable **Now** panel: temperature, risk, coolest window, four key readings (heat index, humidity, wind, UV) and what to do. Long-press the map to set a destination and see a trip card. |
| **Map, going somewhere** | Search "Where to?" (suggestions appear as you type) or long-press the map. The panel becomes a trip view: back button, pin button, Walk / Bike / Drive, time and distance with a risk pill, advice (water, clothing, tips), a heat chart for the destination and a chat that already knows the trip. The route is drawn on the map, coloured by heat. Pinned places show as chips under the activity chips and as markers; tapping one opens its trip. A coloured glow circles the panel while the assistant works. |
| **Trends** | Next-24-hours heat bars coloured by risk, hottest and coolest hour. An info icon at the top right opens a **Colour key** popup that explains each colour in plain words. |
| **Ask** | A full-screen assistant: suggestions in the middle until you ask, the message field pinned above the keyboard and tab bar. While it works you see three typing dots and a plain step line ("Checking air quality"); replies type out, with a short "Used weather and air quality" caption. Stop, Try again and New chat are built in. |
| **Alerts** | A blue master card (on or off), then grouped cards: **How urgent** (Early, Important, Urgent, each with a one-line explanation) and **Quiet hours** (From and Until steppers). With alerts off, only the master card shows. A **Health data** card connects your watch data through Health Connect (Connect, then your latest heart rate and steps, a switch to let the assistant use it, and Disconnect). An info icon at the top right opens an **About Hydro** popup with credits, the disclaimer and the version. |

Map layers (open from the layers button): **Heat** (where it feels hottest) and **Air quality** (needs a Google Maps key).

## Design rules
- **Plain language only.** No provider names, formula names, error codes or tool names on screen. Errors are friendly sentences with a way forward. Credits and the medical disclaimer live under Alerts, About.
- **One shape language.** Wide, short things (search, chips, segmented controls, inputs, tab bar) are full capsules. Square-ish things (map buttons, tiles, cards) use a 12dp corner. The Now panel is an inset floating card with 28dp corners that ends above the tab bar, so the map shows around the bar. Selected segments are concentric with their container.
- **Floating, not fixed.** The tab bar and the Now panel both float over the map with the map visible around them. Surfaces are opaque white because a translucent surface lets its own shadow show through as a square.
- **Tap feedback.** Pressed elements fade softly; there is no square ripple or highlight anywhere (one indication installed in the theme).
- **Colour.** Snow white base, blue-ice tints, one clean blue accent. The heat ramp (blue, yellow, orange, red, purple) always comes with a text label.
- **Type.** One typeface (Android system font), a small scale: 32 display, 24 title, 18 section, 16 body, 14 secondary, 12 metadata.
- **Icons.** Phosphor Icons (MIT) as vector drawables; regular weight by default, fill for the selected state.
- **Loading.** Skeletons that match the real layout; an Apple-style segmented activity indicator for short waits. Both respect the system "animations off" setting.
- **Accessibility.** 48dp touch targets, descriptions on icon-only controls, selection never shown by colour alone, text scales with system font size.

## Reusable components (`ui/components`)
`MapSearchBar`, `MapCategoryChips`, `MapFloatingButton` / `MapControlGroup` / `CurrentLocationButton`, `MapBottomSheet`, `FloatingTabBar`, `SegmentedControl`, `MetricTile`, `HourlyBarChart`, `SwitchRow` / `StepperRow`, `PlaceDetailsHeader` / `ActionRow` / `StatusPill`, `AppleActivityIndicator`, `Skeleton*`, `HydroPrimaryButton`, `PhosphorIcon`. Tokens live in `ui/theme/HydroTheme.kt`.
