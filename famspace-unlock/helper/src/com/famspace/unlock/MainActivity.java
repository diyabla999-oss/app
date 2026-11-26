package com.famspace.unlock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Trivial launcher: starts the local rewrite proxy and shows its state.
 * Open this app once after installing, then leave it in the background.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent svc = new Intent(this, ProxyService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        title.setText("Famspace Unlock");
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(15);
        body.setTextColor(Color.DKGRAY);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 32, 0, 0);
        body.setText("Local rewrite proxy running on\n"
                + "http://127.0.0.1:" + ProxyService.PORT + "\n\n"
                + "It forwards every Famspace request to the real service and\n"
                + "rewrites the answers: unlimited gems/coins, active VIP,\n"
                + "every space item unlocked and viewable.\n\n"
                + "Now open Famspace. Leave this app running (the notification\n"
                + "keeps the service alive).");
        root.addView(body);

        setContentView(root);
        Log.i("FamUnlock", "proxy helper started");
    }
}
