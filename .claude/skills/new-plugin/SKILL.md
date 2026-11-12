---
name: new-plugin
description: Create a new Hearth TV plugin in plugins/<id>/ that speaks the plugin contract, with its own logo and colour. Use when adding any new source or connection.
---

# Adding a plugin

1. Read Hearth's `docs/plugins/CONTRACT.md`, the version your plugin will speak.
2. Create `plugins/<id>/` with:
   - `AndroidManifest.xml`: package `dev.hearth.plugin.<id>`. Declare the SDK's `PluginProvider`
     with authority `<package>.hearth`, the `dev.hearth.action.PLUGIN` intent filter, and the
     `dev.hearth.plugin` meta-data. Request no Android permissions beyond what the service
     needs (usually only `INTERNET`).
   - `res/xml/hearth_plugin.xml`: id, name, api, minHost, logo, color, provides, ratesContent,
     capabilities.
   - `res/drawable/logo.xml`: a vector logo. Use the service's own mark only as its brand
     guidelines allow.
   - `src/`: implement the SDK interfaces for what you `provide`.
   - Remote buttons: if something the plugin does deserves a button (open its screen, play a
     title's trailer), add an `<action>` with a scope, a suggested `key` and at least two
     `alternatives`, best first, and put `actions` in `provides` (contract §3.7). Look at
     Hearth TV's own layout in that section first, and suggest a button that's free in that
     scope. Answer `actions.run` with `open`, `play` or `notice`: never start an Activity.
     Actions are few (at most 8) and worth a button. A setting or a rarely used thing isn't.
     `build-plugin.sh` runs `library.py check <id>`, which refuses a plugin that breaks a rule.
3. Ask for host capabilities only if the plugin can't work without them, and say why in its
   README.
4. Per profile: key every stored sign-in and setting by the request's `profile`.
5. Test on a real TV with Hearth installed. Follow Hearth's `install-on-tv` skill for the safety
   checks. For actions: install while the suggested button is free (it's given at once, with a
   notice), then again after giving that button to something else in Settings → Remote buttons.
   Hearth must ask, and "instead" must offer your first alternative.
6. Add a line for it in the README's table.
