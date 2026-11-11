# Hearth TV plugins

The official plugins for [Hearth TV](https://github.com/zuptalo/hearth), the Android TV home screen,
and the official plugin library they're installed from.

Each plugin is its own small Android app that brings one source or connection to Hearth TV: its
content, its sign-in, its logo and its colour. Hearth TV draws the screens. Plugins speak the
contract in Hearth's [`docs/plugins/CONTRACT.md`](https://github.com/zuptalo/hearth/blob/main/docs/plugins/CONTRACT.md).

| Plugin | What it brings | Status |
|---|---|---|
| `weather` | Weather in the top bar and on its own screen | released |
| `tmdb` | Trailers and clips for films and series | planned |
| `homeassistant` | Your smart home on the TV | planned |
| `homeassistant-bridge` | The TV in Home Assistant (with the `hearth_tv` integration) | planned |
| `jellyfin` | Films, series and music from a Jellyfin server | planned |

## Layout

```
plugins/<id>/          one plugin: AndroidManifest.xml, src/, res/ (with its logo), plugin.json
library/               the official library: library.json is generated and signed at release
.claude/skills/        guides: adding a plugin, releasing one
```

Installing: Hearth TV → Settings → Plugins. Plugins are verified before installing (signed
catalogue, file hash, and the publisher's signing certificate).

Licence: Apache-2.0. See [AGENTS.md](AGENTS.md) before contributing.
