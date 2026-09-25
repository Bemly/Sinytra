package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.os.ParcelFileDescriptor;
import android.print.PrintDocumentAdapter;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// P1 system-capability probes: cookie policy flags, cookie jar round-trip
// (Gecko jar via firefox-patches/0007), print (PDF round-trip through the
// destination fd), media permission prompt + decision routing, geolocation
// prompt (deny path — deterministic without location services), download
// (in-process attachment server; no blob/gesture/debug-build gating).
//
// Same harness contract as P0/P2 sections: every probe appends a PASS
// line or throws. Clients/listeners installed here are restored in each
// probe's finally block (the errClient lesson: a probe that swaps a
// client and forgets to restore breaks every downstream probe).
//
// Requires: a live provider on a loaded https page (example.org after the
// P2 deny probe — secure context for getUserMedia/geolocation).
//
// Print note: the framework WriteResultCallback/LayoutResultCallback
// types are package-private abstract classes — they cannot be constructed
// (nor subclassed) outside android.print, so the probe drives
// PrintBridge.writeTo (the framework-independent core the adapter
// delegates to) instead of onWrite. The onLayout/onWrite shell stays
// covered by code review + CTS.
final class P1SystemProbes {
    private P1SystemProbes() {}

    // Verbatim shape of upstream mozilla-central
    // mobile/android/geckoview/src/androidTest/assets/www/download.html —
    // the gesture-free blob-download trigger proven by
    // ContentDelegateTest.downloadOneRequest.
    private static final String DL_PAGE = "<html><head><meta charset=\"utf-8\">"
            + "</head><body><script>"
            + "const blob = new Blob([\"Downloaded Data\"],"
            + " {type: \"text/plain\"});"
            + "const element = document.createElement(\"a\");"
            + "element.href = URL.createObjectURL(blob);"
            + "element.download = \"download.txt\";"
            + "element.style.display = \"none\";"
            + "document.body.appendChild(element);"
            + "element.click();"
            + "URL.revokeObjectURL(element.href);"
            + "</script></body></html>";

    static void run(Activity activity, StringBuilder out,
            GeckoWebViewProvider provider, WebView webView,
            GeckoWebViewFactoryProvider factory,
            P0GlueActivity.TestClient client)
            throws Exception {

        // --- P1 cookie policy flags (our GeckoCookieManagerImpl via the
        // factory — NOT the system Chromium singleton the framework would
        // hand out before the provider switch) ---
        final boolean[] cookieOk = new boolean[1];
        final CountDownLatch cookieDone = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            try {
                CookieManager cm =
                        factory.cookieManager(webView.getContext());
                boolean before = cm.acceptCookie();
                cm.setAcceptCookie(!before);
                boolean flipped = cm.acceptCookie() != before;
                cm.setAcceptCookie(before);
                boolean restored = cm.acceptCookie() == before;
                boolean tpBefore = cm.acceptThirdPartyCookies(webView);
                cm.setAcceptThirdPartyCookies(webView, !tpBefore);
                boolean tpFlipped =
                        cm.acceptThirdPartyCookies(webView) != tpBefore;
                cm.setAcceptThirdPartyCookies(webView, tpBefore);
                cookieOk[0] = flipped && restored && tpFlipped;
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/p1", "cookie probe threw", t);
            } finally {
                cookieDone.countDown();
            }
        });
        if (!cookieDone.await(10, TimeUnit.SECONDS)
                || !cookieOk[0]) {
            throw new IllegalStateException("cookiePolicy failed");
        }
        out.append("PASS cookiePolicy\n");

        // --- P2 cookie jar: real Gecko jar round-trip through the
        // firefox-patches/0007 primitives (set → get →
        // removeSessionCookies → gone). The cookiePolicy probe above only
        // covers facade state; this one proves jar data. Runs against
        // example.org regardless of the current page (jar ops are
        // host-addressed, not page-addressed). The facade resolves the
        // round-trip synchronously (poll on the Gecko thread), so these run
        // directly on the probe thread. ---
        final AtomicReference<Boolean> jarSet = new AtomicReference<>();
        final AtomicReference<Boolean> jarRemoved = new AtomicReference<>();
        final CountDownLatch jarSetDone = new CountDownLatch(1);
        final CountDownLatch jarRemoveDone = new CountDownLatch(1);
        CookieManager jarManager =
                factory.cookieManager(webView.getContext());
        jarManager.setCookie("https://example.org/",
                "sinytra-cookie-0007=jar; Path=/",
                value -> {
                    jarSet.set(value);
                    jarSetDone.countDown();
                });
        if (!jarSetDone.await(15, TimeUnit.SECONDS)
                || !Boolean.TRUE.equals(jarSet.get())) {
            throw new IllegalStateException(
                    "cookieJar setCookie failed: " + jarSet.get());
        }
        String jarValue = jarManager.getCookie("https://example.org/");
        if (jarValue == null
                || !jarValue.contains("sinytra-cookie-0007=jar")) {
            throw new IllegalStateException(
                    "cookieJar getCookie lost the marker: " + jarValue);
        }
        if (!jarManager.hasCookies()) {
            throw new IllegalStateException(
                    "cookieJar hasCookies=false right after a set");
        }
        jarManager.removeSessionCookies(value -> {
            jarRemoved.set(value);
            jarRemoveDone.countDown();
        });
        if (!jarRemoveDone.await(15, TimeUnit.SECONDS)
                || !Boolean.TRUE.equals(jarRemoved.get())) {
            throw new IllegalStateException(
                    "cookieJar removeSessionCookies failed: "
                            + jarRemoved.get());
        }
        String jarGone = jarManager.getCookie("https://example.org/");
        if (jarGone != null && jarGone.contains("sinytra-cookie-0007")) {
            throw new IllegalStateException(
                    "cookieJar marker survived removeSessionCookies: "
                            + jarGone);
        }
        out.append("PASS cookieJar get=\"")
                .append(jarValue.length() > 64
                        ? jarValue.substring(0, 64) + "…" : jarValue)
                .append("\"\n");

        // --- P1 print: PrintBridge streams a real PDF into the
        // destination fd (regression: the old bridge reported success
        // without writing anything) ---
        org.mozilla.geckowebview.session.PrintBridge printBridge =
                printBridgeOf(provider);
        final File pdf = new File(activity.getCacheDir(),
                "sinytra-print-probe.pdf");
        final AtomicReference<String> writeError = new AtomicReference<>();
        final CountDownLatch writeDone = new CountDownLatch(1);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdf,
                ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE);
        try {
            printBridge.writeTo(
                    new org.mozilla.geckowebview.session.PrintBridge
                            .ParcelFileDescriptorHolder(pfd),
                    new org.mozilla.geckowebview.session.PrintBridge
                            .WriteCompletion() {
                        @Override
                        public void onFinished() {
                            writeDone.countDown();
                        }

                        @Override
                        public void onFailed(String error) {
                            writeError.set(error != null ? error
                                    : "write failed");
                            writeDone.countDown();
                        }
                    });
            if (!writeDone.await(60, TimeUnit.SECONDS)
                    || writeError.get() != null) {
                throw new IllegalStateException("print write failed: "
                        + writeError.get());
            }
            byte[] head = new byte[5];
            long size;
            try (FileInputStream in = new FileInputStream(pdf)) {
                size = pdf.length();
                int read = in.read(head);
                if (read < 5) {
                    throw new IllegalStateException("pdf too small: " + size);
                }
            }
            if (size < 100 || head[0] != '%' || head[1] != 'P'
                    || head[2] != 'D' || head[3] != 'F' || head[4] != '-') {
                throw new IllegalStateException("not a PDF: size=" + size
                        + " head=" + new String(head));
            }
            out.append("PASS print bytes=").append(size).append('\n');
        } finally {
            pfd.close();
            pdf.delete();
        }

        // --- P1 media permission prompt: getUserMedia →
        // onPermissionRequest(VIDEO/AUDIO_CAPTURE) → deny routes back to
        // the page (round-trip proof: both the prompt and the page-side
        // rejection are observed) ---
        final AtomicReference<String> permResources = new AtomicReference<>();
        final AtomicReference<String> permPage = new AtomicReference<>();
        final CountDownLatch permPrompt = new CountDownLatch(1);
        final CountDownLatch permPageDone = new CountDownLatch(1);
        final WebChromeClient permChrome = new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                permResources.set(String.join(",", request.getResources()));
                try {
                    request.deny();
                } catch (Throwable ignored) {
                }
                permPrompt.countDown();
            }
        };
        activity.runOnUiThread(() -> provider.setWebChromeClient(permChrome));
        try {
            // Two-phase: the transport serializes the eval result as
            // JSON without awaiting promises (a Promise serializes to
            // "{}"), so stash the outcome in a global and poll it.
            evalJs(activity, provider,
                    "(() => {"
                    + " window.__sinytraMedia = null;"
                    + " navigator.mediaDevices.getUserMedia({video:true})"
                    + "   .then(() => { window.__sinytraMedia = 'granted'; },"
                    + "         e => { window.__sinytraMedia = 'denied:'"
                    + "                                + e.name; });"
                    + " return 'started'; })()");
            if (!permPrompt.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "media permission prompt never delivered");
            }
            pollGlobal(activity, provider, "__sinytraMedia", permPage,
                    permPageDone);
        } finally {
            activity.runOnUiThread(
                    () -> provider.setWebChromeClient(client.chrome));
        }
        String page = unjson(permPage.get());
        if (page == null || !page.startsWith("denied:")) {
            throw new IllegalStateException(
                    "media permission deny did not route to the page: "
                            + page);
        }
        out.append("PASS permissionPrompt resources=")
                .append(permResources.get() != null
                        ? permResources.get() : "none")
                .append(" page=").append(page).append('\n');

        // --- P1 geolocation prompt: deny path — deterministic without
        // device location services (the CTS geolocation dependence that
        // stays environment-red does not apply here) ---
        final AtomicReference<String> geoOrigin = new AtomicReference<>();
        final AtomicReference<String> geoPage = new AtomicReference<>();
        final CountDownLatch geoPrompt = new CountDownLatch(1);
        final CountDownLatch geoPageDone = new CountDownLatch(1);
        final WebChromeClient geoChrome = new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    android.webkit.GeolocationPermissions.Callback callback) {
                geoOrigin.set(origin);
                try {
                    callback.invoke(origin, false, false);
                } catch (Throwable ignored) {
                }
                geoPrompt.countDown();
            }
        };
        activity.runOnUiThread(() -> provider.setWebChromeClient(geoChrome));
        try {
            evalJs(activity, provider,
                    "(() => {"
                    + " window.__sinytraGeo = null;"
                    + " navigator.geolocation.getCurrentPosition("
                    + "   p => { window.__sinytraGeo = 'granted'; },"
                    + "   e => { window.__sinytraGeo = 'denied:' + e.code; });"
                    + " return 'started'; })()");
            if (!geoPrompt.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "geolocation prompt never delivered");
            }
            pollGlobal(activity, provider, "__sinytraGeo", geoPage,
                    geoPageDone);
        } finally {
            activity.runOnUiThread(
                    () -> provider.setWebChromeClient(client.chrome));
        }
        String geoResult = unjson(geoPage.get());
        // Origin = whatever https page is current when the probe runs
        // (the P2 intercept probes leave large.example loaded) — the
        // round-trip contract is prompt(origin) + deny → page denied:1.
        if (geoOrigin.get() == null
                || !geoOrigin.get().startsWith("https://")
                || !"denied:1".equals(geoResult)) {
            throw new IllegalStateException(
                    "geolocation deny round-trip failed: origin="
                            + geoOrigin.get() + " page=" + geoResult);
        }
        out.append("PASS geolocationPrompt origin=").append(geoOrigin.get())
                .append(" page=").append(geoPage.get()).append('\n');
        // --- P1 download: deterministic attachment-server path (0006
        // verdict, 2026-09-25). Gecko's helper-app dispatch WORKS on this
        // opt build: forceExternalHandling →
        // GeckoViewExternalAppService.CreateListener →
        // ContentDelegate.onExternalResponse (verified at the GeckoView
        // level with the canary; the earlier "no dispatch" verdict misread
        // PageStop success=true, which also fires when the document channel
        // is claimed for external handling). No blob, no gesture — none of
        // the debug-build gating the upstream tests need. ---
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            final AtomicReference<String> dlUrl = new AtomicReference<>();
            final CountDownLatch dlDone = new CountDownLatch(1);
            Thread serverThread = new Thread(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.getInputStream().read(new byte[4096]);
                    byte[] body = "sinytra-0006-attachment"
                            .getBytes("UTF-8");
                    String head = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/octet-stream\r\n"
                            + "Content-Disposition: attachment; "
                            + "filename=\"sinytra-0006.bin\"\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    java.io.OutputStream os = socket.getOutputStream();
                    os.write(head.getBytes("UTF-8"));
                    os.write(body);
                    os.flush();
                } catch (Throwable t) {
                    android.util.Log.w("Sinytra/p1",
                            "attachment server ended", t);
                }
            }, "sinytra-dl-server");
            serverThread.start();
            // Harness idiom: the framework WebView binds the SYSTEM
            // (Chromium) provider — setters must go through our provider
            // instance directly (the only provider.* exception that the
            // probes had been routing through the framework WebView).
            activity.runOnUiThread(() -> provider.setDownloadListener(
                    (url, ua, disposition, mimetype, length) -> {
                        dlUrl.set(url);
                        dlDone.countDown();
                    }));
            try {
                activity.runOnUiThread(() -> provider.loadUrl(
                        "http://127.0.0.1:" + server.getLocalPort()
                                + "/att"));
                if (!dlDone.await(30, TimeUnit.SECONDS)
                        || dlUrl.get() == null) {
                    throw new IllegalStateException(
                            "download never dispatched: " + dlUrl.get());
                }
                if (!dlUrl.get().startsWith("http://127.0.0.1:")) {
                    throw new IllegalStateException(
                            "download dispatched to the wrong url: "
                                    + dlUrl.get());
                }
                out.append("PASS download url=").append(dlUrl.get())
                        .append('\n');
            } finally {
                activity.runOnUiThread(
                        () -> provider.setDownloadListener(null));
                serverThread.join(2000);
            }
        }

        // --- P2 SSL proceed (firefox-patches/0006): self-signed TLS server
        // (keystore in debug assets) → cert error → app proceeds →
        // temporary override + reload → the body actually renders. Locks
        // the full chain: cert capture at error time, the
        // GeckoView:AllowCertError round-trip, and the in-package
        // SslErrorHandler decision routing. Runs last (navigates away). ---
        final AtomicInteger sslPrompts = new AtomicInteger();
        final AtomicReference<String> sslPageBody = new AtomicReference<>();
        final CountDownLatch sslPageDone = new CountDownLatch(1);
        javax.net.ssl.SSLContext sslContext =
                sslContextFromAssets(activity, "sinytra-test.p12",
                        "sinytra-probe");
        try (javax.net.ssl.SSLServerSocket tlsServer =
                (javax.net.ssl.SSLServerSocket) sslContext
                        .getServerSocketFactory().createServerSocket(0, 4,
                                java.net.InetAddress.getByName("127.0.0.1"))) {
            tlsServer.setSoTimeout(30000);
            Thread tlsThread = new Thread(() -> {
                // The first handshake is expected to fail (the probe
                // navigation); keep accepting for the post-override reload.
                for (int i = 0; i < 4; i++) {
                    try (java.net.Socket socket = tlsServer.accept()) {
                        socket.getInputStream().read(new byte[4096]);
                        byte[] body = "sinytra-ssl-0006-ok".getBytes("UTF-8");
                        String head = "HTTP/1.1 200 OK\r\n"
                                + "Content-Type: text/plain\r\n"
                                + "Content-Length: " + body.length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        java.io.OutputStream os = socket.getOutputStream();
                        os.write(head.getBytes("UTF-8"));
                        os.write(body);
                        os.flush();
                    } catch (Throwable t) {
                        android.util.Log.d("Sinytra/p1",
                                "tls server connection ended", t);
                    }
                }
            }, "sinytra-tls-server");
            tlsThread.start();
            WebViewClient originalClient = provider.getWebViewClient();
            WebViewClient sslClient = new WebViewClient() {
                @Override
                public void onReceivedSslError(WebView view,
                        android.webkit.SslErrorHandler handler,
                        android.net.http.SslError error) {
                    // Chromium semantics: exactly one decision; proceed on
                    // the first prompt, cancel afterwards (a working
                    // override means no second prompt ever arrives).
                    if (sslPrompts.incrementAndGet() == 1) {
                        handler.proceed();
                    } else {
                        handler.cancel();
                    }
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    // Body proof with retries: the eval races the fresh
                    // document's transport poller (same pattern as the
                    // jsEval probe's retry loop).
                    new Thread(() -> {
                        for (int i = 0; i < 10
                                && sslPageBody.get() == null; i++) {
                            try {
                                Thread.sleep(1000);
                                evalJs(activity, provider,
                                        "document.body.innerText", 4000,
                                        value -> {
                                            if (value != null && value
                                                    .contains("sinytra-ssl-0006-ok")) {
                                                sslPageBody.set(value);
                                                sslPageDone.countDown();
                                            }
                                        });
                            } catch (Throwable t) {
                                android.util.Log.d("Sinytra/p1",
                                        "ssl probe eval attempt " + i
                                                + " failed");
                            }
                        }
                    }, "sinytra-ssl-eval").start();
                }
            };
            activity.runOnUiThread(() -> provider.setWebViewClient(sslClient));
            try {
                activity.runOnUiThread(() -> provider.loadUrl(
                        "https://127.0.0.1:" + tlsServer.getLocalPort()
                                + "/probe"));
                if (!sslPageDone.await(60, TimeUnit.SECONDS)
                        || sslPageBody.get() == null) {
                    throw new IllegalStateException(
                            "ssl proceed never rendered the body: prompts="
                                    + sslPrompts.get() + " body="
                                    + sslPageBody.get());
                }
                if (sslPrompts.get() != 1) {
                    throw new IllegalStateException(
                            "ssl proceed prompted " + sslPrompts.get()
                                    + " times (override loop?)");
                }
                out.append("PASS sslProceed body=")
                        .append(sslPageBody.get()).append('\n');
            } finally {
                activity.runOnUiThread(
                        () -> provider.setWebViewClient(originalClient));
                tlsThread.join(2000);
            }
        }
    }

    // In-process TLS context for the sslProceed probe: PKCS12 keystore from
    // debug assets (self-signed CN=127.0.0.1, SAN IP:127.0.0.1).
    private static javax.net.ssl.SSLContext sslContextFromAssets(
            Activity activity, String asset, String password)
            throws Exception {
        java.security.KeyStore keyStore =
                java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = activity.getAssets().open(asset)) {
            keyStore.load(in, password.toCharArray());
        }
        javax.net.ssl.KeyManagerFactory kmf =
                javax.net.ssl.KeyManagerFactory.getInstance(
                        javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password.toCharArray());
        javax.net.ssl.SSLContext context =
                javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    private static org.mozilla.geckowebview.session.PrintBridge
            printBridgeOf(GeckoWebViewProvider provider) throws Exception {
        Field field = GeckoWebViewProvider.class.getDeclaredField("mPrint");
        field.setAccessible(true);
        return (org.mozilla.geckowebview.session.PrintBridge)
                field.get(provider);
    }

    // One-shot eval helper: resolves with the raw result string (JSON or
    // null per the JsBridge transport contract). Throws on timeout — the
    // P2 probes' retry loop is only needed right after a navigation; the
    // page has been quiescent since the deny probe.
    // Polls a window global until it becomes non-null (promise outcomes
    // are stashed there by the two-phase probes above).
    private static void pollGlobal(Activity activity,
            GeckoWebViewProvider provider, String global,
            AtomicReference<String> sink, CountDownLatch done)
            throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1000);
            evalJs(activity, provider, "window." + global, value -> {
                if (value != null && !"null".equals(value)) {
                    sink.set(value);
                    done.countDown();
                }
            });
            if (done.getCount() == 0) {
                return;
            }
        }
    }

    private static void evalJs(Activity activity,
            GeckoWebViewProvider provider, String script,
            ValueCallback<String> callback) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> provider.evaluateJavaScript(script,
                value -> {
                    callback.onReceiveValue(value);
                    done.countDown();
                }));
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("eval timeout: " + script);
        }
    }

    private static void evalJs(Activity activity,
            GeckoWebViewProvider provider, String script) throws Exception {
        evalJs(activity, provider, script, value -> { });
    }

    // Short-timeout variant for post-navigation polling: the eval races the
    // fresh document's transport poller (STATUS §1d), so callers retry with
    // a bounded per-attempt wait instead of the 30s one-shot.
    private static void evalJs(Activity activity,
            GeckoWebViewProvider provider, String script, long timeoutMs,
            ValueCallback<String> callback) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> provider.evaluateJavaScript(script,
                value -> {
                    callback.onReceiveValue(value);
                    done.countDown();
                }));
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("eval timeout: " + script);
        }
    }

    // The JsBridge transport returns JSON — a string result arrives
    // quoted. Strip the surrounding quotes for plain-string assertions.
    private static String unjson(String value) {
        if (value != null && value.length() >= 2
                && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String shorten(String s) {
        return s.length() > 48 ? s.substring(0, 48) + "…" : s;
    }
}
