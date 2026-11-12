package dev.hearth.plugin.ha_bridge;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * Kept running by Hearth TV while it runs (capability {@code background}). Starts the bridge's
 * local API and stops it when Hearth lets go.
 */
public class BridgeService extends Service {
    private final IBinder binder = new Binder();

    @Override public void onCreate() {
        super.onCreate();
        Bridge.get(this).start();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        Bridge.get(this).stop();
        super.onDestroy();
    }
}
