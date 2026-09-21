package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

public final class BootstrapProbeActivity extends Activity {
    private static final String TAG = "Sinytra/bootstrap";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("Running P-1 bootstrap probe…");
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);

        final TextView view = text;
        final BootstrapProbe.Result result = BootstrapProbe.runStatic(this);
        view.setText(result.toString() + "\nCreating GeckoRuntime on UI thread…");
        new Thread(() -> {
            runOnUiThread(() -> {
                try {
                    BootstrapProbe.probeRuntimeCreate(BootstrapProbeActivity.this, result);
                } catch (Throwable t) {
                    result.add("GeckoRuntime.create: FAIL " + Log.getStackTraceString(t));
                }
                Log.i(TAG, "\n" + result);
                view.setText(result.toString());
            });
        }, "bootstrap-probe").start();
    }
}
