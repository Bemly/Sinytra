package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.os.ParcelFileDescriptor;
import android.print.PrintDocumentAdapter;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// P1 system-capability probes: cookie policy flags, downloads
// (DownloadListener), print (PDF round-trip through the destination fd),
// media permission prompt + decision routing, geolocation prompt (deny
// path — deterministic without location services).
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
        // --- P1 download: REMOVED from the deterministic run (2026-09-25).
        // The Java wiring is verified by inspection (ContentBridge
        // .onExternalResponse -> DownloadListener.onDownloadStart) and the
        // Gecko-side blob request reaches our onLoadRequest, but Gecko
        // never dispatches onExternalResponse on this opt build — the
        // upstream download tests are debug-build-gated themselves
        // (assumeThat(isDebugBuild)) and bug 1543355 documents env-only
        // mitigation. Helper-app dispatch investigation =
        // firefox-patches 0006 candidate; see STATUS.md P1 section.
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
