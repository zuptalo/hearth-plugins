# Weather

The weather for Hearth TV's header and weather screen: now, the next hours and ten days.

- **Where:** the city in the TV's time zone, found with Open-Meteo's place search. No location or
  IP lookup.
- **Forecast:** SMHI (Swedish Meteorological and Hydrological Institute, open data, "Source:
  SMHI") in the Nordic area; [Open-Meteo](https://open-meteo.com) elsewhere. No accounts or keys.
- **Kept:** answers are reused for half an hour, in the plugin's own cache.
- **Provides:** `weather` (method `weather.forecast`, see the contract §3.4).

Build: `bash scripts/build-plugin.sh weather`.
