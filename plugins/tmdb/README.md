# Trailers (TMDb)

Official trailers, clips and behind-the-scenes videos for films and series in Hearth TV, found on
[TMDb](https://www.themoviedb.org) by the title's TMDb id, so every video is about that title.

- **Needs:** the household's own free TMDb key (v3 API key or v4 read access token), added from a
  phone in Hearth TV's Settings. It stays in this plugin and goes only to TMDb.
- **Shows:** only videos TMDb marks official, and only those still on YouTube.
- **Provides:** `videos` (`videos.for`) and `settings` (`settings.get`, `settings.set`); takes a key
  Hearth TV kept before through `migrate.import`.

This product uses the TMDB API but is not endorsed or certified by TMDB.

Build: `bash scripts/build-plugin.sh tmdb`.
