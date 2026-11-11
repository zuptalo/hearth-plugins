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
3. Ask for host capabilities only if the plugin can't work without them, and say why in its
   README.
4. Per profile: key every stored sign-in and setting by the request's `profile`.
5. Test on a real TV with Hearth installed. Follow Hearth's `install-on-tv` skill for the safety
   checks.
6. Add a line for it in the README's table.
