package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// P0 glue harness: drives GeckoWebViewProvider WITHOUT the framework by
// injecting a real android.webkit.WebView + the real hidden PrivateAccess
// (obtained via reflection on the device framework). Verifies:
// createWebView path, loadUrl → onPageFinished, getUrl/getTitle/getProgress,
// canGoBack/goBack round-trip, copyBackForwardList, settings live.
public final class P0GlueActivity extends Activity {
    private static final String TAG = "Sinytra/p0glue";
    private static final String URL_1 = "https://example.com";
    private static final String URL_2 = "https://example.org";

    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStatus = new TextView(this);
        mStatus.setText("P0 glue: starting…");
        mStatus.setTextIsSelectable(true);
        setContentView(mStatus);
        Log.i(TAG, "onCreate: starting probe thread");
        new Thread(this::runProbe, "p0-glue").start();
    }

    private void runProbe() {
        Log.i(TAG, "runProbe: entered");
        StringBuilder out = new StringBuilder();
        try {
            Log.i(TAG, "runProbe: loading factory class");
            Class<?> factoryClass =
                    Class.forName("org.mozilla.geckowebview.provider.GeckoWebViewFactoryProvider");
            Log.i(TAG, "runProbe: factory class loaded: " + factoryClass.getClassLoader());
            Log.i(TAG, "runProbe: constructing factory");
            GeckoWebViewFactoryProvider factory = new GeckoWebViewFactoryProvider();
            out.append("PASS provider construct\n");
            Log.i(TAG, "runProbe: factory ok");

            // android.webkit.WebView's constructor binds the CURRENT system
            // provider (Chromium) and requires a prepared Looper — must run on
            // the UI thread. Collect the objects first, then continue.
            Log.i(TAG, "runProbe: creating WebView on UI thread");
            final WebView[] webViewBox = new WebView[1];
            final Object[] privateAccessBox = new Object[1];
            final Throwable[] uiError = new Throwable[1];
            final java.util.concurrent.CountDownLatch uiDone =
                    new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(() -> {
                try {
                    WebView webView = new WebView(P0GlueActivity.this);
                    webViewBox[0] = webView;
                    privateAccessBox[0] = newPrivateAccess(webView);
                } catch (Throwable t) {
                    uiError[0] = t;
                } finally {
                    uiDone.countDown();
                }
            });
            if (!uiDone.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("UI thread WebView create timeout");
            }
            Log.i(TAG, "runProbe: WebView created, error=" + uiError[0]);
            if (uiError[0] != null) {
                throw new IllegalStateException("WebView create failed", uiError[0]);
            }
            WebView webView = webViewBox[0];
            Object privateAccess = privateAccessBox[0];
            out.append("privateAccess=").append(privateAccess.getClass().getName())
                    .append('\n');

            // GeckoSession constructor requires the UI thread too — create the
            // whole provider on the UI thread, like the real framework does
            // (WebViewFactory.getProvider → create() happens on the UI thread).
            Log.i(TAG, "runProbe: creating provider on UI thread");
            final Object[] providerBox = new Object[1];
            final Throwable[] providerError = new Throwable[1];
            final java.util.concurrent.CountDownLatch providerDone =
                    new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(() -> {
                try {
                    providerBox[0] = factory.createWebView(webView, privateAccess);
                } catch (Throwable t) {
                    providerError[0] = t;
                } finally {
                    providerDone.countDown();
                }
            });
            if (!providerDone.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("UI thread createWebView timeout");
            }
            Log.i(TAG, "runProbe: provider created, error=" + providerError[0]);
            if (providerError[0] != null) {
                throw new IllegalStateException("createWebView failed", providerError[0]);
            }
            Object raw = providerBox[0];
            if (!(raw instanceof GeckoWebViewProvider)) {
                throw new IllegalStateException("not a GeckoWebViewProvider");
            }
            out.append("PASS createWebView type\n");
            GeckoWebViewProvider provider = (GeckoWebViewProvider) raw;

            TestClient client = new TestClient();
            Log.i(TAG, "runProbe: loading URL_1");
            runOnUiThread(() -> {
                provider.setWebViewClient(client.client);
                provider.setWebChromeClient(client.chrome);
                provider.getSettings().setJavaScriptEnabled(true);
                provider.loadUrl(URL_1);
            });

            if (client.awaitFinished(30) == null) {
                throw new IllegalStateException("page1 timeout");
            }
            if (!URL_1.equals(client.finishedUrl())
                    && !"https://example.com/".equals(client.finishedUrl())) {
                throw new IllegalStateException("page1: url=" + client.finishedUrl());
            }
            out.append("PASS page1 finished\n");
            if (provider.getProgress() != 100) {
                throw new IllegalStateException("progress=" + provider.getProgress());
            }
            if (provider.getUrl() == null) {
                throw new IllegalStateException("url null");
            }
            out.append("PASS page1 progress/url\n");
            out.append("title1=").append(provider.getTitle()).append('\n');

            TestClient client2 = new TestClient();
            runOnUiThread(() -> {
                provider.setWebViewClient(client2.client);
                provider.loadUrl(URL_2);
            });
            if (client2.awaitFinished(30) == null) {
                throw new IllegalStateException("page2 timeout");
            }
            out.append("PASS page2 finished\n");

            if (!provider.canGoBack()) {
                throw new IllegalStateException("expected canGoBack=true");
            }
            out.append("PASS canGoBack\n");
            TestClient client3 = new TestClient();
            runOnUiThread(() -> {
                provider.setWebViewClient(client3.client);
                provider.goBack();
            });
            if (client3.awaitFinished(30) == null) {
                throw new IllegalStateException("goBack timeout");
            }
            out.append("PASS goBack finished\n");

            android.webkit.WebBackForwardList list = provider.copyBackForwardList();
            if (list.getSize() < 2 || list.getCurrentIndex() < 0) {
                final CountDownLatch flushed = new CountDownLatch(1);
                runOnUiThread(() -> {
                    try {
                        provider.dumpHistorySources("pre-flush");
                        provider.flushHistory();
                    } finally {
                        flushed.countDown();
                    }
                });
                flushed.await(5, TimeUnit.SECONDS);
                Thread.sleep(3000);
                final CountDownLatch dumped = new CountDownLatch(1);
                runOnUiThread(() -> {
                    try {
                        provider.dumpHistorySources("post-flush");
                    } finally {
                        dumped.countDown();
                    }
                });
                dumped.await(5, TimeUnit.SECONDS);
                list = provider.copyBackForwardList();
                out.append("flushed: size=").append(list.getSize())
                        .append(" index=").append(list.getCurrentIndex()).append('\n');
                if (list.getSize() < 2 || list.getCurrentIndex() < 0) {
                    throw new IllegalStateException(
                            "list size=" + list.getSize() + " index="
                                    + list.getCurrentIndex());
                }
            }
            out.append("PASS backForwardList size=").append(list.getSize()).append('\n');

            if (!provider.canGoBackOrForward(-1)) {
                throw new IllegalStateException("expected canGoBackOrForward(-1)=true");
            }
            if (provider.canGoBackOrForward(1)) {
                throw new IllegalStateException("expected canGoBackOrForward(+1)=false");
            }
            out.append("PASS canGoBackOrForward\n");
            TestClient client4 = new TestClient();
            final CountDownLatch goForwardDone = new CountDownLatch(1);
            runOnUiThread(() -> {
                try {
                    provider.setWebViewClient(client4.client);
                    provider.goBackOrForward(1);
                } finally {
                    goForwardDone.countDown();
                }
            });
            goForwardDone.await(5, TimeUnit.SECONDS);
            if (client4.awaitFinished(30) == null) {
                throw new IllegalStateException("goBackOrForward(+1) timeout");
            }
            if (!provider.getUrl().contains("example.org")) {
                throw new IllegalStateException(
                        "goBackOrForward(+1): url=" + provider.getUrl());
            }
            out.append("PASS goBackOrForward\n");
            int beforeClear = provider.copyBackForwardList().getSize();
            runOnUiThread(provider::clearHistory);
            final CountDownLatch historyCleared = new CountDownLatch(1);
            runOnUiThread(() -> {
                try {
                    provider.flushHistory();
                } finally {
                    historyCleared.countDown();
                }
            });
            historyCleared.await(5, TimeUnit.SECONDS);
            Thread.sleep(2000);
            provider.dumpHistorySources("post-clear");
            int afterClear = provider.copyBackForwardList().getSize();
            if (afterClear >= beforeClear || afterClear < 0) {
                throw new IllegalStateException(
                        "clearHistory: before=" + beforeClear + " after=" + afterClear);
            }
            out.append("PASS clearHistory before=").append(beforeClear)
                    .append(" after=").append(afterClear).append('\n');

            runOnUiThread(() -> provider.getSettings().setTextZoom(150));
            runOnUiThread(() -> {
                if (provider.getSettings().getTextZoom() != 150) {
                    throw new IllegalStateException("textZoom");
                }
            });
            out.append("PASS settings roundtrip\n");

            runOnUiThread(provider::destroy);
            out.append("P0 GLUE PASS\n");
        } catch (Throwable t) {
            out.append("P0 GLUE FAIL: ").append(Log.getStackTraceString(t)).append('\n');
        }
        String result = out.toString();
        Log.i(TAG, "\n" + result);
        runOnUiThread(() -> mStatus.setText(result));
    }

    private Object newPrivateAccess(WebView webView) throws Exception {
        for (Class<?> inner : WebView.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("PrivateAccess")) {
                Constructor<?> ctor = inner.getDeclaredConstructor(WebView.class);
                ctor.setAccessible(true);
                return ctor.newInstance(webView);
            }
        }
        throw new IllegalStateException("WebView.PrivateAccess not found on device");
    }

    private static final class TestClient {
        final android.webkit.WebViewClient client = new android.webkit.WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                Log.i(TAG, "client: onPageStarted " + url);
                if (url != null && !url.equals("about:blank")) {
                    lastStartedUrl.set(url);
                    started.countDown();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "client: onPageFinished " + url);
                if (url != null && !url.equals("about:blank")) {
                    lastFinishedUrl.set(url);
                    finished.countDown();
                }
            }
        };
        final android.webkit.WebChromeClient chrome = new android.webkit.WebChromeClient() {
        };
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicReference<String> lastStartedUrl = new AtomicReference<>();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<String> lastFinishedUrl = new AtomicReference<>();

        String startedUrl() {
            return lastStartedUrl.get();
        }

        String awaitStarted(int seconds) throws InterruptedException {
            return started.await(seconds, TimeUnit.SECONDS)
                    ? lastStartedUrl.get() : null;
        }

        String finishedUrl() {
            return lastFinishedUrl.get();
        }

        String awaitFinished(int seconds) throws InterruptedException {
            return finished.await(seconds, TimeUnit.SECONDS)
                    ? lastFinishedUrl.get() : null;
        }
    }
}
