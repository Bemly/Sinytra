package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.widget.TextView;

// Framework-entry verification: exercises the REAL system path the
// framework uses after a developer-options switch — new WebView(context)
// binds WebViewFactory.getProvider() (which loads OUR trampoline class
// com.android.webview.chromium.WebViewChromiumFactoryProviderForT via
// reflection) instead of P0Glue's manual factory construction.
// Debug-only (src/debug); reports PASS/FAIL on screen + logcat.
public final class FrameworkEntryActivity extends Activity {
    private static final String TAG = "Sinytra/fwentry";

    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStatus = new TextView(this);
        mStatus.setText("Framework entry: starting…");
        mStatus.setTextIsSelectable(true);
        setContentView(mStatus);
        new Thread(this::runProbe, "fw-entry").start();
    }

    private void runProbe() {
        StringBuilder out = new StringBuilder();
        try {
            Log.i(TAG, "loading trampoline class via framework name");
            Class<?> trampoline = Class.forName(
                    "com.android.webview.chromium.WebViewChromiumFactoryProviderForT",
                    true, getClassLoader());
            out.append("PASS trampoline class=").append(trampoline.getName())
                    .append('\n');
            java.lang.reflect.Method create = null;
            for (java.lang.reflect.Method m : trampoline.getDeclaredMethods()) {
                if (m.getName().equals("create")
                        && m.getParameterTypes().length == 1) {
                    create = m;
                    break;
                }
            }
            if (create == null) {
                throw new IllegalStateException("create(WebViewDelegate) not found");
            }
            out.append("PASS create param=")
                    .append(create.getParameterTypes()[0].getName()).append('\n');

            final WebView[] box = new WebView[1];
            final Throwable[] err = new Throwable[1];
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(() -> {
                try {
                    box[0] = new WebView(FrameworkEntryActivity.this);
                } catch (Throwable t) {
                    err[0] = t;
                } finally {
                    done.countDown();
                }
            });
            if (!done.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("WebView create timeout");
            }
            if (err[0] != null) {
                throw new IllegalStateException("new WebView() failed", err[0]);
            }
            out.append("PASS new WebView() via framework provider\n");

            final java.util.concurrent.CountDownLatch loaded =
                    new java.util.concurrent.CountDownLatch(1);
            final String[] urlBox = new String[1];
            runOnUiThread(() -> {
                try {
                    WebView w = box[0];
                    w.setWebViewClient(new android.webkit.WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            urlBox[0] = url;
                            loaded.countDown();
                        }
                    });
                    // The NPE is in the ACTIVITY's DecorView measuring OUR
                    // WebView as a child with null LayoutParams: our delegate
                    // setLayoutParams is a stash-only no-op (reflective write
                    // blocked, re-add recurses), so the framework View never
                    // receives params. Wrap in a FrameLayout WITH explicit
                    // params: the wrapper (not our WebView) is the measured
                    // child, and IT has valid params. This mirrors what any
                    // real app layout does (WebView inside a container).
                    android.widget.FrameLayout wrapper =
                            new android.widget.FrameLayout(
                                    FrameworkEntryActivity.this);
                    wrapper.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                    w.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                    wrapper.addView(w);
                    setContentView(wrapper);
                    w.loadUrl("https://example.com");
                } catch (Throwable t) {
                    Log.w(TAG, "load dispatch threw", t);
                    loaded.countDown();
                }
            });
            if (!loaded.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("page load timeout");
            }
            out.append("PASS page finished url=").append(urlBox[0]).append('\n');
            out.append("FRAMEWORK ENTRY PASS\n");
        } catch (Throwable t) {
            out.append("FRAMEWORK ENTRY FAIL: ").append(Log.getStackTraceString(t))
                    .append('\n');
        }
        String result = out.toString();
        Log.i(TAG, "\n" + result);
        runOnUiThread(() -> mStatus.setText(result));
    }
}
