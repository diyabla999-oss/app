package com.famspace.unlock;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

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

    /** the app's own strings - used to crack the JWT secret of the session */
    private static final String DICT_ASSET = "famunlock/dict.txt";

    private static LocalProxy proxy;
    private static String status = "not started";

    private ProxyHolder() {
    }

    /** loads the string dictionary from the assets (once) */
    private static void loadDictionary(Context context) {
        if (LocalProxy.dictionary != null || context == null) {
            return;
        }
        try {
            AssetManager assets = context.getAssets();
            InputStream in = assets.open(DICT_ASSET);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            LocalProxy.dictionary = new String(out.toByteArray(), "UTF-8");
            Log.i(TAG, "dictionary loaded: " + LocalProxy.dictionary.length() + " chars");
        } catch (Throwable t) {
            Log.w(TAG, "no dictionary: " + t);
        }
    }

    public static synchronized void ensureStarted(Context context) {
        loadDictionary(context);
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
