package com.famspace.unlock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Launcher for the helper.  It starts the proxy inside the app process and
 * asks the foreground service to keep it alive.
 *
 * Nothing here may throw: a crash would close the Activity and look like
 * "the app does nothing".  Every step is guarded and the result - including
 * the error text - is shown on screen.
 */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildUi();
        } catch (Throwable t) {
            Log.e("FamUnlock", "ui failed", t);
        }
        try {
            ProxyHolder.ensureStarted(this);
        } catch (Throwable t) {
            Log.e("FamUnlock", "proxy failed", t);
        }
        try {
            startServiceSafely();
        } catch (Throwable t) {
            Log.e("FamUnlock", "service refused", t);
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void startServiceSafely() {
        Intent svc = new Intent(this, ProxyService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable t) {
            // Foreground start refused: the proxy still runs in this process.
            Log.e("FamUnlock", "could not start service", t);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(40, 48, 40, 48);

        TextView title = new TextView(this);
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        title.setText("Famspace Unlock");
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(Color.DKGRAY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        Button retry = new Button(this);
        retry.setText("Restart proxy");
        retry.setOnClickListener(v -> {
            try {
                ProxyHolder.ensureStarted(this);
            } catch (Throwable t) {
                Log.e("FamUnlock", "retry failed", t);
            }
            try {
                startServiceSafely();
            } catch (Throwable ignored) {
            }
            refresh();
        });
        root.addView(retry);

        TextView body = new TextView(this);
        body.setTextSize(14);
        body.setTextColor(Color.GRAY);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 28, 0, 0);
        body.setText("The proxy forwards every Famspace request to the real service and\n"
                + "rewrites the answers: unlimited gems/coins, active VIP,\n"
                + "every space item unlocked and viewable.\n\n"
                + "Leave this app running (the notification keeps the service\n"
                + "alive), then open Famspace.\n\n"
                + "If the status above is not \"running\", open Famspace anyway -\n"
                + "the proxy runs in this process while the app is open.");
        root.addView(body);

        setContentView(root);
    }

    private void refresh() {
        if (status == null) {
            return;
        }
        String s;
        try {
            s = ProxyHolder.status();
        } catch (Throwable t) {
            s = "error: " + t;
        }
        status.setText(s);
    }
}
