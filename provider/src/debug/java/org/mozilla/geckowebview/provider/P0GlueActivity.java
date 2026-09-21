package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.content.Context;
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

            // --- P1 singleton checks (factory-owned implementations) ---
            final Object[] singletons = new Object[7];
            final Throwable[] singletonError = new Throwable[1];
            final CountDownLatch singletonDone = new CountDownLatch(1);
            runOnUiThread(() -> {
                try {
                    Context app = P0GlueActivity.this.getApplicationContext();
                    singletons[0] = factory.cookieManager(app);
                    singletons[1] = factory.getWebStorage();
                    singletons[2] = factory.icons(app);
                    singletons[3] = factory.webViewDatabase(app);
                    singletons[4] = factory.getGeolocationPermissions();
                    singletons[5] = factory.getServiceWorkerController();
                    singletons[6] = factory.getTracingController();
                } catch (Throwable t) {
                    singletonError[0] = t;
                } finally {
                    singletonDone.countDown();
                }
            });
            singletonDone.await(10, TimeUnit.SECONDS);
            if (singletonError[0] != null) {
                throw new IllegalStateException("singletons failed", singletonError[0]);
            }
            String[] names = {"cookie", "storage", "icon", "webdb", "geo", "sw", "tracing"};
            for (int i = 0; i < singletons.length; i++) {
                if (singletons[i] == null) {
                    throw new IllegalStateException("singleton null: " + names[i]);
                }
                out.append("singleton ").append(names[i]).append('=')
                        .append(singletons[i].getClass().getName()).append('\n');
            }
            out.append("PASS singletons\n");

            // --- P1 WebViewDatabase HTTP-auth round-trip ---
            final Throwable[] dbError = new Throwable[1];
            final CountDownLatch dbDone = new CountDownLatch(1);
            runOnUiThread(() -> {
                try {
                    provider.setHttpAuthUsernamePassword(
                            "example.com", "realm1", "user1", "pass1");
                    String[] creds = provider.getHttpAuthUsernamePassword(
                            "example.com", "realm1");
                    if (creds == null || !"user1".equals(creds[0])
                            || !"pass1".equals(creds[1])) {
                        throw new IllegalStateException(
                                "auth creds mismatch");
                    }
                } catch (Throwable t) {
                    dbError[0] = t;
                } finally {
                    dbDone.countDown();
                }
            });
            dbDone.await(10, TimeUnit.SECONDS);
            if (dbError[0] != null) {
                throw new IllegalStateException("wevdb auth failed", dbError[0]);
            }
            out.append("PASS httpAuth store\n");

            // --- P1 find-in-page on the live example.org page ---
            final CountDownLatch findDone = new CountDownLatch(1);
            final int[][] findResult = new int[1][];
            final Throwable[] findError = new Throwable[1];
            runOnUiThread(() -> {
                try {
                    provider.setFindListener((active, total, done) -> {
                        findResult[0] = new int[] {active, total, done ? 1 : 0};
                        findDone.countDown();
                    });
                    provider.findAllAsync("Example");
                } catch (Throwable t) {
                    findError[0] = t;
                    findDone.countDown();
                }
            });
            if (!findDone.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("find timeout");
            }
            if (findError[0] != null) {
                throw new IllegalStateException("find failed", findError[0]);
            }
            int total = findResult[0] != null ? findResult[0][1] : 0;
            out.append("PASS find total=").append(total).append('\n');
            runOnUiThread(provider::clearMatches);

            // --- P1 error mapping: bad host must fan out onReceivedError ---
            final CountDownLatch errDone = new CountDownLatch(1);
            final int[][] errResult = new int[1][];
            TestClient errClient = new TestClient();
            runOnUiThread(() -> {
                provider.setWebViewClient(errClient.errClient(errDone, errResult));
                provider.loadUrl("https://nonexistent.invalid/");
            });
            if (!errDone.await(45, TimeUnit.SECONDS)) {
                throw new IllegalStateException("error mapping timeout");
            }
            out.append("PASS loadError code=").append(errResult[0][0]).append('\n');

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

        android.webkit.WebViewClient errClient(CountDownLatch done, int[][] out) {
            return new android.webkit.WebViewClient() {
                @Override
                public void onReceivedError(WebView view, int errorCode,
                        String description, String failingUrl) {
                    Log.i(TAG, "errClient: onReceivedError " + errorCode + " "
                            + failingUrl);
                    out[0] = new int[] {errorCode};
                    done.countDown();
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (url != null && url.contains("nonexistent.invalid")) {
                        done.countDown();
                    }
                }
            };
        }
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
