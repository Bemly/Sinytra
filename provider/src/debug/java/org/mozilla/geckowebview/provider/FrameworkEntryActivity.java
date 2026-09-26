package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Real-path acceptance probe (AGENTS §6: final acceptance runs on the
// switched device, BOOTSTRAP §2.4). Unlike P0GlueActivity (reflection-built
// factory, framework WebView still bound to the system provider) this goes
// through the framework exactly like any app: WebView.getCurrentWebViewPackage
// must be us, new WebView(context) loads the trampoline via WebViewFactory,
// and the singletons come from the framework's own getInstance() calls.
// Same contract as the harness: append PASS or throw; ends with
// FRAMEWORK ENTRY PASS/FAIL (logcat tag Sinytra/fwentry).
public final class FrameworkEntryActivity extends Activity {
    private static final String TAG = "Sinytra/fwentry";

    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStatus = new TextView(this);
        mStatus.setText("Framework entry: starting…");
        setContentView(mStatus);
        new Thread(this::runProbe, "fw-entry").start();
    }

    private interface UiStep<T> {
        T run() throws Exception;
    }

    private <T> T onUi(UiStep<T> step) throws Exception {
        final Object[] box = new Object[1];
        final Throwable[] err = new Throwable[1];
        final CountDownLatch done = new CountDownLatch(1);
        runOnUiThread(() -> {
            try {
                box[0] = step.run();
            } catch (Throwable t) {
                err[0] = t;
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("UI step timeout");
        }
        if (err[0] != null) {
            throw new IllegalStateException("UI step failed", err[0]);
        }
        @SuppressWarnings("unchecked")
        T result = (T) box[0];
        return result;
    }

    private void runProbe() {
        StringBuilder out = new StringBuilder();
        try {
            // 1) The system provider must be us (otherwise this probe would
            // silently test Chromium).
            PackageInfo current = WebView.getCurrentWebViewPackage();
            String currentPkg = current != null ? current.packageName : null;
            if (!getPackageName().equals(currentPkg)) {
                throw new IllegalStateException("system WebView is " + currentPkg
                        + ", not " + getPackageName() + " (switch first: DEVICE §5)");
            }
            out.append("PASS currentWebViewPackage=").append(currentPkg).append('\n');

            // 2) new WebView() through WebViewFactory, attached the way apps
            // do it: setContentView(webView) with no wrapper — the parent's
            // addView → WebView.setLayoutParams → provider ViewDelegate →
            // PrivateAccess.super_setLayoutParams must land the params.
            final CountDownLatch loaded = new CountDownLatch(1);
            final String[] finishedUrl = new String[1];
            final WebView webView = onUi(() -> {
                WebView w = new WebView(FrameworkEntryActivity.this);
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        // The session's initial empty document finishes before
                        // loadUrl's navigation starts; only the real page may
                        // open the latch.
                        if (url == null || url.startsWith("about:")) {
                            return;
                        }
                        finishedUrl[0] = url;
                        loaded.countDown();
                    }
                });
                setContentView(w);
                return w;
            });
            out.append("PASS new WebView() via framework\n");
            boolean paramsSet = onUi(() -> webView.getLayoutParams() != null);
            if (!paramsSet) {
                throw new IllegalStateException("WebView LayoutParams null after setContentView");
            }
            out.append("PASS layoutParams via PrivateAccess\n");

            // 3) Load + title through the framework API surface.
            onUi(() -> {
                webView.loadUrl("https://example.com/");
                return null;
            });
            if (!loaded.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("onPageFinished timeout");
            }
            String title = onUi(webView::getTitle);
            out.append("PASS pageFinished url=").append(finishedUrl[0])
                    .append(" title=").append(title).append('\n');

            // 4) Visual surface: the Gecko child is in the tree and attached.
            boolean geckoChild = onUi(() -> {
                for (int i = 0; i < webView.getChildCount(); i++) {
                    View c = webView.getChildAt(i);
                    // By name, not instanceof: the framework loads provider
                    // classes through its own provider classloader, which
                    // need not be this activity's even in the same APK.
                    for (Class<?> k = c.getClass(); k != null; k = k.getSuperclass()) {
                        if ("org.mozilla.geckoview.GeckoView".equals(k.getName())
                                && c.isAttachedToWindow()) {
                            return true;
                        }
                    }
                }
                return false;
            });
            if (!geckoChild) {
                throw new IllegalStateException("no attached GeckoView child");
            }
            out.append("PASS geckoChild attached\n");

            // 5) evaluateJavascript through the framework WebView (the first
            // eval after a navigation can race the transport: retry).
            String evalValue = null;
            for (int attempt = 0; attempt < 8 && evalValue == null; attempt++) {
                final CountDownLatch evalDone = new CountDownLatch(1);
                final String[] evalBox = new String[1];
                onUi(() -> {
                    webView.evaluateJavascript("1+2", v -> {
                        evalBox[0] = v;
                        evalDone.countDown();
                    });
                    return null;
                });
                evalDone.await(30, TimeUnit.SECONDS);
                if ("3".equals(evalBox[0])) {
                    evalValue = evalBox[0];
                } else {
                    Thread.sleep(1500);
                }
            }
            if (evalValue == null) {
                throw new IllegalStateException("evaluateJavascript never returned 3");
            }
            out.append("PASS evaluateJavascript value=").append(evalValue).append('\n');

            // 6) Framework singletons via getInstance(): must be ours and
            // must not recurse (WebStorage/Geolocation used to).
            String storage = onUi(() -> WebStorage.getInstance().getClass().getName());
            String geo = onUi(() -> GeolocationPermissions.getInstance().getClass().getName());
            if (!"android.webkit.SinytraWebStorage".equals(storage)
                    || !"android.webkit.SinytraGeolocationPermissions".equals(geo)) {
                throw new IllegalStateException("singletons: storage=" + storage + " geo=" + geo);
            }
            out.append("PASS singletons storage+geo provider-owned\n");
            String cookie = onUi(() -> {
                CookieManager cm = CookieManager.getInstance();
                cm.setCookie("https://example.com/", "sinytra-fw=entry; Max-Age=600");
                return cm.getCookie("https://example.com/");
            });
            if (cookie == null || !cookie.contains("sinytra-fw=entry")) {
                throw new IllegalStateException("CookieManager round-trip: " + cookie);
            }
            out.append("PASS CookieManager.getInstance round-trip cookie=")
                    .append(cookie).append('\n');

            out.append("FRAMEWORK ENTRY PASS\n");
        } catch (Throwable t) {
            out.append("FRAMEWORK ENTRY FAIL: ").append(Log.getStackTraceString(t))
                    .append('\n');
        }
        String result = out.toString();
        Log.i(TAG, "\n" + result);
    }
}
