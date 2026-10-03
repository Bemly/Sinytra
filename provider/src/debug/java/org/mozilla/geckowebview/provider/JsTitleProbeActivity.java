package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// CTS WebSettingsTest image-family triage (STATUS §1x): loadData pages whose
// inline script sets document.title come back with getTitle() == "" —
// expected "48"/"1". Isolates (data: vs http-base) x (immediate vs delayed
// load after setJavaScriptEnabled) x (plain script vs CTS image+onload
// shape). Debug-only; release APKs never contain it.
public class JsTitleProbeActivity extends Activity {
    private static final String TAG = "Sinytra/probe";

    private static final String SCRIPT_HTML =
            "<html><head><script>document.title='SINYTRA42';</script></head>"
                    + "<body>hi</body></html>";
    // Exact CTS testLoadsImagesAutomatically_dataUriImagesLoaded shape
    // (1x1 data PNG, onload-driven title), localhost server replaced by an
    // inline image so no network is involved.
    private static final String CTS_SHAPE_HTML =
            "<html><head><script>function updateTitle(){"
                    + "document.title=document.getElementById('img').naturalHeight;}"
                    + "</script></head><body onload='updateTitle()'>"
                    + "<img id='img' onload='updateTitle()' src='data:image/png;base64,"
                    + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAAAXNSR0IArs4c6QAAAA1"
                    + "JREFUCB0BAgD9/wAAAAIAAc3j0SsAAAAASUVORK5CYII='>"
                    + "</body></html>";

    private LinearLayout mRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        setContentView(mRoot);
        new Thread(() -> {
            try {
                String only = getIntent().getStringExtra("probe");
                if (match(only, "A")) runProbe("A-data-immediate", SCRIPT_HTML, false, 0);
                if (match(only, "B")) runProbe("B-data-delayed", SCRIPT_HTML, false, 3000);
                if (match(only, "C")) runProbe("C-httpbase-immediate", SCRIPT_HTML, true, 0);
                if (match(only, "D")) runProbe("D-data-ctsshape", CTS_SHAPE_HTML, false, 0);
                if (match(only, "E")) runProbe("E-data-statictitle",
                        "<html><head><title>STATICTITLE</title></head>"
                                + "<body>hi</body></html>",
                        false, 0);
                if (match(only, "F")) runProbe("F-data-jsnav",
                        "<html><head><script>location.hash='JSRAN';</script></head>"
                                + "<body>hi</body></html>",
                        false, 0);
                // CTS testLoadsImagesAutomatically_blockLoadingImages shape:
                // images disabled + remote img (connection-refused host, no
                // server needed) + onload-driven title. Expect "0".
                if (match(only, "G")) {
                    mImagesAuto = false;
                    runProbe("G-data-blockedimg", blockedImgHtml(), false, 0);
                    mImagesAuto = true;
                }
                // Same but images allowed: refused img errors fast, body
                // onload still fires, title "0" must arrive.
                if (match(only, "H")) {
                    runProbe("H-data-errorimg", blockedImgHtml(), false, 0);
                }
                // Sinytra 0024 single-session discriminator: loadable data:
                // image with images OFF. Blocked -> "0", loaded -> "1".
                // (G/H cannot discriminate: refused imgs read 0 either way.)
                if (match(only, "I")) {
                    mImagesAuto = false;
                    runProbe("I-data-blockedimg", CTS_SHAPE_HTML, false, 0);
                    mImagesAuto = true;
                }
                Log.i(TAG, "PROBE DONE");
            } catch (Throwable t) {
                Log.e(TAG, "PROBE FAILED", t);
            }
        }).start();
    }

    private static boolean match(String only, String name) {
        return only == null || only.isEmpty() || only.equals(name);
    }

    private static String blockedImgHtml() {
        return "<html><head><script>function updateTitle(){"
                + "document.title=document.getElementById('img')"
                + ".naturalHeight;}</script></head>"
                + "<body onload='updateTitle()'>"
                + "<img id='img' onload='updateTitle()' "
                + "src='http://127.0.0.1:9/none.png'>"
                + "</body></html>";
    }

    // loadData() identity is data:, so poll for the data: URL itself plus
    // the JS-navigation hash.
    private void runProbe(String name, String html, boolean httpBase, long delayMs)
            throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        runOnUiThread(() -> {
            WebView w = new WebView(JsTitleProbeActivity.this);
            w.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 700));
            mRoot.addView(w);
            w.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    // The session's initial about:blank finishes before the
                    // probe navigation starts (FrameworkEntryActivity §1s) —
                    // only the real page may open the latch, or the lag
                    // metric measures from the wrong finish.
                    if (url == null || url.startsWith("about:")) {
                        return;
                    }
                    finished.countDown();
                }
            });
            w.setWebChromeClient(new WebChromeClient());
            w.getSettings().setJavaScriptEnabled(true);
            w.getSettings().setLoadsImagesAutomatically(mImagesAuto);
            try {
                if (delayMs > 0) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> load(w, html, httpBase), delayMs);
                } else {
                    load(w, html, httpBase);
                }
            } catch (Throwable t) {
                Log.e(TAG, name + " load threw", t);
                finished.countDown();
            }
        });
        boolean ok = finished.await(60, TimeUnit.SECONDS);
        long finishedAt = android.os.SystemClock.uptimeMillis();
        // Poll the title: body-onload title lands after PageFinished on a
        // slow bring-up, so sample for up to 10s instead of reading once.
        String title = null;
        for (int i = 0; i < 20; i++) {
            CountDownLatch got = new CountDownLatch(1);
            final String[] urlBox = new String[1];
            runOnUiThread(() -> {
                try {
                    WebView top =
                            (WebView) mRoot.getChildAt(mRoot.getChildCount() - 1);
                    titleHolder[0] = top.getTitle();
                    urlBox[0] = top.getUrl();
                } catch (Throwable t) {
                    titleHolder[0] = "THREW:" + t;
                }
                got.countDown();
            });
            got.await(5, TimeUnit.SECONDS);
            title = titleHolder[0];
            lastUrl[0] = urlBox[0];
            String seenUrl = urlBox[0];
            if ("SINYTRA42".equals(title) || "1".equals(title)
                    || "48".equals(title) || "STATICTITLE".equals(title)
                    || "0".equals(title)
                    || (seenUrl != null && seenUrl.endsWith("#JSRAN"))) {
                break;
            }
            Thread.sleep(500);
        }
        Log.i(TAG, "PROBE " + name + " finished=" + ok + " title=" + title
                + " url=" + lastUrl[0] + " titleLagMs="
                + (android.os.SystemClock.uptimeMillis() - finishedAt));
        CountDownLatch removed = new CountDownLatch(1);
        runOnUiThread(() -> {
            try {
                WebView w = (WebView) mRoot.getChildAt(mRoot.getChildCount() - 1);
                mRoot.removeView(w);
                w.destroy();
            } catch (Throwable t) {
                Log.e(TAG, name + " teardown threw", t);
            }
            removed.countDown();
        });
        removed.await(10, TimeUnit.SECONDS);
    }

    private final String[] titleHolder = new String[1];
    private final String[] lastUrl = new String[1];
    // CTS image-family probes toggle this (default true = Chromium default).
    private volatile boolean mImagesAuto = true;

    private void load(WebView w, String html, boolean httpBase) {
        if (httpBase) {
            w.loadDataWithBaseURL("http://www.example.com/", html,
                    "text/html", "utf-8", null);
        } else {
            w.loadData(html, "text/html", "utf-8");
        }
    }
}
