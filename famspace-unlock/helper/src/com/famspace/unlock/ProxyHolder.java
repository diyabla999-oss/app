package com.famspace.unlock;

import android.content.Context;
import android.util.Log;


/**
 * Holds the one proxy instance for the whole app process.
 *
 * Both the Activity and the foreground Service call ensureStarted(): if the
 * service is refused by the system (background-start restrictions, FGS type
 * rules, ...) the proxy still runs in the app process, so Famspace keeps
 * working as long as the helper app is alive.
 */
public final class ProxyHolder {

    private static final String TAG = "FamUnlock";

    private static LocalProxy proxy;
    private static String status = "not started";

    private ProxyHolder() {
    }

    /** finds the data directory of the app (used to patch its cache) */
    private static void findDataDir(Context context) {
        if (context != null && LocalCache.dataDir == null) {
            try {
                LocalCache.dataDir = context.getApplicationInfo().dataDir;
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized void ensureStarted(Context context) {
        findDataDir(context);
        if (proxy != null && proxy.isRunning()) {
            status = "running on " + proxy.publicUrl() + " (ports " + proxy.ports() + ")";
            return;
        }
        try {
            proxy = new LocalProxy();
            proxy.start();
            status = "running on " + proxy.publicUrl() + " (ports " + proxy.ports() + ")";
            Log.i(TAG, status);
        } catch (Throwable t) {
            status = "failed: " + t;
            Log.e(TAG, "proxy failed to start", t);
            // second attempt with a fresh instance (the port may have been
            // taken by a previous, half-dead process)
            try {
                proxy = new LocalProxy();
                proxy.start();
                status = "running on " + proxy.publicUrl() + " (ports " + proxy.ports() + ")";
                Log.i(TAG, status);
            } catch (Throwable t2) {
                status = "failed: " + t2;
                Log.e(TAG, "proxy failed to start (retry)", t2);
            }
        }
    }

    public static synchronized String status() {
        if (proxy != null && proxy.isRunning()) {
            return "running on " + proxy.publicUrl() + " (ports " + proxy.ports() + ")";
        }
        if (proxy != null && proxy.lastError() != null) {
            return "error: " + proxy.lastError();
        }
        return status;
    }
}
