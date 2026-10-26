# Data Sources

Every value carries provenance (source, retrieval time, nature: observed / forecast / modelled / derived).

| Source | Tool(s) | Key | Data | Notes / licence |
|---|---|---|---|---|
| Open-Meteo Forecast + Archive | `get_weather`, `get_hourly_forecast`, `get_historical_weather`, heat map | No | Temperature, humidity, wind, UV, solar, precipitation, history (ERA5) | Free tier is non-commercial (CC-BY 4.0); commercial use needs a subscription |
| Open-Meteo Air Quality | `get_air_quality` (fallback) | No | US/EU AQI, PM2.5, PM10, O3, NO2, SO2, CO, dust | Modelled |
| Open-Meteo Geocoding | `geocode_place` (fallback) | No | Place to coordinates | |
| NASA POWER | `get_solar_climate` | No | Daily solar irradiance, T max/min, RH, rainfall | Satellite/reanalysis, about 3 days latency; `-999` treated as missing |
| Google Routes | `get_route_conditions` | Google | Real walking/cycling/driving/transit routes and ETA | Replaces the great-circle estimate |
| Google Geocoding | `geocode_place` | Google | Address to coordinates | |
| Google Places (New) | `find_nearby_relief` | Google | Parks, indoor cooling, water, medical, open-now | |
| Google Weather | `get_google_weather` | Google | Current + hourly incl. thunderstorm and rain probability, heat index | Second opinion; disagreements are reported |
| Google Air Quality | `get_air_quality` | Google | Universal + EPA AQI, pollutants, dominant pollutant, health advice; heat-map tile URLs for the map | |
| Google Isochrones (preview) | `find_relief_within_reach` | Google | Real reachable area within N minutes on foot, bike or car; combined with Places so only reachable places are returned | Pre-GA API; request shape verified against fake responses only |
| Google Places Aggregate (Area Insights) | `get_area_insights` | Google | Counts of parks, medical, indoor cooling and supply places in a radius, plus density per km2 | Green-space count is a proxy for cooler pockets, not proof of shade |
| Google Elevation | `get_elevation_profile` | Google | Elevation samples along a path: min, max, ascent, descent | Straight path between points, not the road |
| Google Time Zone | `get_time_zone` | Google | IANA zone and offset for any coordinate | Unknown zone ids fail instead of defaulting |
| Google Pollen | `get_pollen` | Google | Grass/tree/weed index, season, advice, up to 5 days | Region-limited coverage |
| Tavily | `get_environmental_news` | Tavily | News on heat, air, floods, storms, health | Untrusted text; only a place name is sent |
| Android Health Connect | `get_wearable_data` | Permission | Heart rate, HRV, SpO2, respiratory rate, body temperature, steps, active energy | Read-only, per-metric consent |
| Device | `get_current_location` | Permission | Last-known coarse location | |

## NASA beyond POWER (candidates)
| API | Value | Key |
|---|---|---|
| **FIRMS** active fire detections | Smoke and fire proximity for air quality | Free MAP_KEY |
| **EONET** natural events | Wildfires, storms, floods near the user | No |
| **GIBS / MODIS land-surface temperature** | Urban heat context (not air temperature) | No |

Land-surface temperature must never be presented as air temperature.

## Not used, and why
- Google Solar API: its data layers include hourly building shade, which could support shade claims, but coverage is mostly the US and Europe, so it will not help in West Africa.
- Street View Static: using imagery to guess tree cover would present a guess as evidence.
- Map Tiles API, Maps Grounding Lite, Geolocation API: possible later; not needed for the data layer. Maps SDK for Android is needed for the map screen.
- Roads, Navigation SDK, Route Optimization, Fleet Routing, Aerial View, Address Validation, Embed/JS/iOS, Booking, Datasets, Picker, Checks: unrelated to heat, air or health guidance.
