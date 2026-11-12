# Working on Hearth TV plugins

Follow the rules in Hearth's [AGENTS.md](https://github.com/zuptalo/hearth/blob/main/AGENTS.md),
especially: no secrets or personal data in the repository, a no-reply commit identity, nothing
secret in logs, and kids' safety enforced by the host. Enable the hooks once:
`sh scripts/setup-hooks.sh`.

Plugin specifics:

1. One plugin per folder in `plugins/<id>/`. Package `dev.hearth.plugin.<id>` (official library only).
2. A plugin brings its own `res/drawable/logo.xml` (vector) and colour. Hearth draws all screens:
   no Activities in plugins.
3. Keep sign-ins per `profile` id from each request. Never log tokens, headers or query strings.
4. Rate content (`rating.age`) wherever the service allows. Plugins that can't are hidden from
   children's profiles.
5. Respect each service's terms of use. A plugin that doesn't belong in a public library
   goes to a private one.
6. Remote buttons are offered, never taken. A plugin declares `<action>`s with a suggested
   button and alternatives (Hearth's contract §3.7). Hearth asks the household before giving
   one a button that's taken. `library.py check` (run by `build-plugin.sh`) enforces the rules.
   Don't bypass it.
7. Releases are signed by the maintainer's plugin key, which is never in the repository (see the
   `release-plugin` skill).
