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
        new Thread(() -> {
            BootstrapProbe.Result result = BootstrapProbe.run(BootstrapProbeActivity.this);
            Log.i(TAG, "probe done");
            runOnUiThread(() -> view.setText(result.toString()));
        }, "bootstrap-probe").start();
    }
}
