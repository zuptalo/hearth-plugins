# Home Assistant bridge

The TV in Home Assistant, with the `hearth_tv` integration
([hearth-homeassistant](https://github.com/zuptalo/hearth-homeassistant)): what's playing, play and
pause, carry on with a series or film, a film, an episode or a song by name, and notices on the
screen.

- **API:** HTTP on the home network, port 47815, found as `_hearth-tv._tcp`. Private addresses
  only. `GET /api/hello`; pairing with `POST /api/pair` (the TV shows a code and asks, with the
  remote), then `GET /api/pair/<id>` for the token, once; `GET /api/describe`, `/api/state`,
  `/api/events` (Server-Sent Events), `/api/search?q=`, `/api/browse?list=resume|next_up` and
  `POST /api/command` with the token. Tokens are kept as hashes and never logged.
- **Entities it describes:** the TV (media player), "Show on TV" (notify), "Playing video" and
  "Playing music", "Now playing from", "Profile", "Screen", "Hearth version", "Continue the series"
  and "Continue the film" (buttons), and "Playback" (event: started, paused, resumed, stopped).
- **Asks for:** `background`, `network.lan`, `ask`, `notices`, `playback.read`, `playback.control`,
  `navigate`, `profiles.read`, `controls.host` (contract §6). An adult allows them in Hearth TV's
  Settings → Plugins. Everything goes through Hearth TV, which keeps the profile's age limit.
- **Provides:** `bridge` (`describe` says whether it's paired).

Build: `bash scripts/build-plugin.sh ha_bridge`.
