package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.os.Bundle;
import org.mozilla.geckowebview.runtime.SinytraLog;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Debug-only burst probe (issue: third-party hosts create WebViews on
// background threads and open several sessions within seconds; single
// main-thread sessions always commit). Creates 3 framework WebViews on a
// BACKGROUND thread (Bilibili :web pattern: creator TID != main) and loads
// a distinct URL in each. Verdict: BURST PASS when all 3 report
// onPageFinished, BURST FAIL otherwise. Release builds never reference
// this (src/debug only).
public final class BurstProbeActivity extends Activity {
    private static final String TAG = "Sinytra/burst";

    private static final String[] URLS = {
        "https://example.com/",
        "https://example.org/",
        "https://example.net/",
    };

    private final WebView[] mViews = new WebView[URLS.length];
    private final CountDownLatch mDone =
            new CountDownLatch(URLS.length);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SinytraLog.i(TAG, "burst start: creator=" + Thread.currentThread().getName());
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        setContentView(layout);
        // Looper thread (Bilibili fasthybrid pattern: creator has a Looper
        // but is not main — plain threads are rejected by the framework
        // before the provider even runs).
        android.os.HandlerThread worker =
                new android.os.HandlerThread("Sinytra/burst-worker");
        worker.start();
        new android.os.Handler(worker.getLooper()).post(() -> {
            SinytraLog.i(TAG, "worker: creator=" + Thread.currentThread().getName()
                    + " main=" + (android.os.Looper.myLooper()
                            == android.os.Looper.getMainLooper()));
            for (int i = 0; i < URLS.length; i++) {
                final int index = i;
                final String url = URLS[i];
                try {
                    WebView webView = new WebView(BurstProbeActivity.this);
                    mViews[index] = webView;
                    runOnUiThread(() -> layout.addView(webView,
                            new android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams
                                            .MATCH_PARENT, 0, 1f)));
                    webView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String finished) {
                            SinytraLog.i(TAG, "finished[" + index + "] " + finished);
                            mDone.countDown();
                        }
                    });
                    webView.loadUrl(url);
                    SinytraLog.i(TAG, "loaded[" + index + "] " + url);
                } catch (Throwable t) {
                    SinytraLog.w(TAG, "create/load[" + index + "] threw", t);
                }
            }
            try {
                boolean ok = mDone.await(90, TimeUnit.SECONDS);
                SinytraLog.i(TAG, ok ? "BURST PASS" : "BURST FAIL");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SinytraLog.i(TAG, "BURST FAIL(interrupted)");
            }
            worker.quitSafely();
        });
    }

    @Override
    protected void onDestroy() {
        for (WebView webView : mViews) {
            if (webView != null) {
                try {
                    webView.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        super.onDestroy();
    }
}
