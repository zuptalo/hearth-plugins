# Weather

The weather for Hearth TV's header and weather screen: now, the next hours and ten days.

- **Where:** the place chosen in Hearth TV's Settings → Plugins → Weather: Place. Choices
  appear as you type, from Open-Meteo's place search in the TV's language and the home country's.
  With no place chosen, it uses where the TV is: the city of its internet address, from
  [ipapi.co](https://ipapi.co), or [GeoJS](https://www.geojs.io) if that fails. It asks at most once a day, and only
  the city and a position rounded to about a kilometre are kept. If neither answers, it uses the
  city the TV's time zone is named after.
- **Forecast:** SMHI (Swedish Meteorological and Hydrological Institute, open data, "Source:
  SMHI") in the Nordic area; [Open-Meteo](https://open-meteo.com) elsewhere. No accounts or keys.
- **Kept:** answers are reused for half an hour, in the plugin's own cache.
- **Provides:** `weather` (method `weather.forecast`, see the contract §3.4) and `settings`
  (`settings.get`, `settings.search`, `settings.set`, §3.6).

Build: `bash scripts/build-plugin.sh weather`.
