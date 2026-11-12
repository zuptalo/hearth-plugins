package dev.hearth.plugin.ha_bridge;

import dev.hearth.sdk.PluginProvider;
import org.json.JSONObject;

/**
 * What Hearth TV asks the bridge itself: {@code describe} (is it paired, and with how many Home
 * Assistants). The work happens in {@link BridgeService}, which Hearth keeps running.
 */
public class BridgePlugin extends PluginProvider {
    @Override
    protected JSONObject handle(String method, JSONObject request) throws Exception {
        if (!"describe".equals(method)) return null;
        Bridge b = Bridge.get(getContext());
        int paired = b.pairedCount();
        return new JSONObject().put("name", "Home Assistant bridge").put("provides", "bridge")
                .put("configured", paired > 0).put("running", b.running())
                .put("detail", paired == 0 ? "Not paired yet: add Hearth in Home Assistant" : paired == 1 ? "Paired with Home Assistant" : "Paired with " + paired + " Home Assistants");
    }
}
