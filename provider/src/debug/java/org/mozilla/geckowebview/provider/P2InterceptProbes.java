package org.mozilla.geckowebview.provider;

import android.app.Activity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Request-interception probes, split out of P2TransportProbes (file-size
// rule, AGENTS §7): 0001 interceptBody (app body + URL identity), 0002
// interceptSubresource / interceptIframe (parent-side necko cover + deny
// stand-down), 0003 interceptLargeBody (stream path, >16MB), P2-4
// denyIntercept (subframe DENY holds about:blank). Order matters: the
// iframe probe removes its frame before the deny probe asserts ALL frames
// sit at about:blank. Same harness contract: append PASS or throw.
//
// Requires: the live provider with the eval transport ready (P2TransportProbes
// ran first) and the harness TestClient's shouldInterceptRequest hook.
final class P2InterceptProbes {
    private P2InterceptProbes() {}

    static void run(Activity activity, StringBuilder out,
            GeckoWebViewProvider provider, P0GlueActivity.TestClient client)
            throws Exception {
            // --- 0001 interceptBody: app-provided body, URL identity ---
            // Registers a filter prefix, loads a non-resolvable marker
            // host, and asserts the page content IS the app body (the
            // network answer for a NXDOMAIN host would be an error page,
            // never our marker). Identity is asserted via getUrl().
            activity.runOnUiThread(() -> provider.setInterceptFilters(
                    new String[] {"https://body.example/"}));
            final CountDownLatch bodyLoaded = new CountDownLatch(1);
            activity.runOnUiThread(() -> provider.loadUrl(
                    "https://body.example/probe.html"));
            final String[] bodyUrlBox = new String[1];
            String bodyText = null;
            for (int attempt = 0; attempt < 12 && bodyText == null;
                    attempt++) {
                Thread.sleep(2500);
                final CountDownLatch bodyEval = new CountDownLatch(1);
                final String[][] bodyResult = new String[1][];
                activity.runOnUiThread(() -> provider.evaluateJavaScript(
                        "document.body ? document.body.textContent"
                                + " : null",
                        v -> {
                            bodyResult[0] = new String[] {v};
                            bodyEval.countDown();
                        }));
                bodyEval.await(45, TimeUnit.SECONDS);
                String value = bodyResult[0] != null ? bodyResult[0][0]
                                                     : null;
                if (value != null) {
                    value = value.replace("\"", "");
                    if (value.contains("sinytra-body-0001")) {
                        bodyText = value;
                    }
                }
            }
            if (bodyText == null) {
                throw new IllegalStateException(
                        "interceptBody: substituted body never rendered");
            }
            String currentUrl = null;
            final CountDownLatch urlDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    bodyUrlBox[0] = provider.getUrl();
                } finally {
                    urlDone.countDown();
                }
            });
            urlDone.await(10, TimeUnit.SECONDS);
            currentUrl = bodyUrlBox[0];
            if (currentUrl == null
                    || !currentUrl.contains("body.example/probe.html")) {
                throw new IllegalStateException("interceptBody identity: "
                        + currentUrl);
            }
            out.append("PASS interceptBody url=").append(currentUrl)
                    .append(" body=").append(bodyText).append('\n');

            // --- 0002 interceptSubresource: XHR inside the synthesized
            // page. fetch('/sub-target') resolves against the page origin
            // (body.example); the network answer is impossible (NXDOMAIN),
            // so the marker can only come from the app through the necko
            // interception — the ruling experiment for parent-side
            // subresource cover.
            final CountDownLatch subKick = new CountDownLatch(1);
            activity.runOnUiThread(() -> provider.evaluateJavaScript(
                    "fetch('/sub-target').then(function(r){"
                            + "return r.text()})"
                            + ".then(function(t){window.__sub=t},"
                            + "function(e){window.__sub='ERR:'+e});"
                            + " 'kicked'",
                    v -> subKick.countDown()));
            subKick.await(45, TimeUnit.SECONDS);
            String subText = null;
            for (int attempt = 0; attempt < 12 && subText == null;
                    attempt++) {
                Thread.sleep(2500);
                final CountDownLatch subPoll = new CountDownLatch(1);
                final String[][] subResult = new String[1][];
                activity.runOnUiThread(() -> provider.evaluateJavaScript(
                        "window.__sub === undefined ? null : window.__sub",
                        v -> {
                            subResult[0] = new String[] {v};
                            subPoll.countDown();
                        }));
                subPoll.await(45, TimeUnit.SECONDS);
                String value = subResult[0] != null ? subResult[0][0] : null;
                if (value != null) {
                    value = value.replace("\"", "");
                    if (value.startsWith("ERR:")) {
                        throw new IllegalStateException(
                                "interceptSubresource fetch failed: "
                                        + value);
                    }
                    if (value.contains("sinytra-sub-0002")) {
                        subText = value;
                    }
                }
            }
            if (subText == null) {
                throw new IllegalStateException(
                        "interceptSubresource: app body never reached "
                                + "the XHR");
            }
            out.append("PASS interceptSubresource body=").append(subText)
                    .append('\n');

            // --- 0002 interceptIframe: filter-matching iframe navigation.
            // The P2-4 deny approximation must STAND DOWN for filter-owned
            // URIs (0002 Group A) — without it the frame never leaves
            // about:blank and the necko interception never gets the
            // channel. The synthesized frame body is same-origin, so
            // contentDocument is readable from the top page.
            final CountDownLatch frameKick = new CountDownLatch(1);
            activity.runOnUiThread(() -> provider.evaluateJavaScript(
                    "(function(){var f=document.createElement('iframe');"
                            + "f.id='sinytra-frame';"
                            + "f.src='https://body.example/frame.html';"
                            + "document.body.appendChild(f);"
                            + "return 'added';})()",
                    v -> frameKick.countDown()));
            frameKick.await(45, TimeUnit.SECONDS);
            String frameText = null;
            for (int attempt = 0; attempt < 12 && frameText == null;
                    attempt++) {
                Thread.sleep(2500);
                final CountDownLatch framePoll = new CountDownLatch(1);
                final String[][] frameResult = new String[1][];
                activity.runOnUiThread(() -> provider.evaluateJavaScript(
                        "(function(){var f=document.getElementById"
                                + "('sinytra-frame');"
                                + "return (f&&f.contentDocument"
                                + "&&f.contentDocument.body)"
                                + "?f.contentDocument.body.textContent"
                                + ":null;})()",
                        v -> {
                            frameResult[0] = new String[] {v};
                            framePoll.countDown();
                        }));
                framePoll.await(45, TimeUnit.SECONDS);
                String value = frameResult[0] != null
                        ? frameResult[0][0] : null;
                if (value != null) {
                    value = value.replace("\"", "");
                    if (value.contains("sinytra-body-0001")) {
                        frameText = value;
                    }
                }
            }
            if (frameText == null) {
                throw new IllegalStateException(
                        "interceptIframe: frame body never rendered "
                                + "(denied or not synthesized)");
            }
            // Probe hygiene (same lesson as the errClient restore): the
            // deny probe after this one asserts ALL frames sit at
            // about:blank — remove our synthesized frame before it runs.
            final CountDownLatch frameCleanup = new CountDownLatch(1);
            activity.runOnUiThread(() -> provider.evaluateJavaScript(
                    "var f=document.getElementById('sinytra-frame');"
                            + "f&&f.parentNode.removeChild(f); 'removed'",
                    v -> frameCleanup.countDown()));
            frameCleanup.await(45, TimeUnit.SECONDS);
            out.append("PASS interceptIframe body=").append(frameText)
                    .append('\n');

            // --- 0003 interceptLargeBody: a body LARGER than the retired
            // 16MB base64 cap. The network answer is impossible (NXDOMAIN)
            // and the retired path refused >16MB — the app-provided
            // pattern rendering proves the stream path delivers it.
            activity.runOnUiThread(() -> provider.setInterceptFilters(
                    new String[] {"https://body.example/",
                                  "https://large.example/"}));
            activity.runOnUiThread(() -> provider.loadUrl(
                    "https://large.example/big.html"));
            String largeExpected =
                    "17000014:PREFIX:0123456789:0123456789:SUFFIX";
            String largeVerdict = null;
            String largeLast = null;
            for (int attempt = 0; attempt < 12 && largeVerdict == null;
                    attempt++) {
                Thread.sleep(2500);
                final CountDownLatch largePoll = new CountDownLatch(1);
                final String[][] largeResult = new String[1][];
                activity.runOnUiThread(() -> provider.evaluateJavaScript(
                        "(function(){var t=document.body"
                                + "&&document.body.textContent;"
                                + "return (t&&t.length>16000000)"
                                + "?t.length+':'+t.slice(0,17)"
                                + "+':'+t.slice(-17):null;})()",
                        v -> {
                            largeResult[0] = new String[] {v};
                            largePoll.countDown();
                        }));
                largePoll.await(45, TimeUnit.SECONDS);
                String value = largeResult[0] != null
                        ? largeResult[0][0] : null;
                if (value != null) {
                    value = value.replace("\"", "");
                    largeLast = value;
                    if (largeExpected.equals(value)) {
                        largeVerdict = value;
                    }
                }
            }
            if (largeVerdict == null) {
                throw new IllegalStateException(
                        "interceptLargeBody: oversized body never "
                                + "rendered (last: " + largeLast + ")");
            }
            out.append("PASS interceptLargeBody verdict=")
                    .append(largeVerdict).append('\n');

            // --- P2-4 deny value: non-null shouldInterceptRequest ⇒
            // subframe DENY. The DENY GeckoResult was verified nowhere
            // (JVM: GeckoResult class-init needs a live UI Looper; device:
            // no probe). E2E here: inject an iframe to a deny-marker host
            // through the eval transport; the app client answers non-null;
            // DENY must cancel the load before network so the frame never
            // leaves about:blank (an allow would navigate it to an
            // unresolvable host and end cross-origin / error-paged).
            client.interceptedUri.set(null);
            final CountDownLatch denyEvalDone = new CountDownLatch(1);
            final String[][] denyEvalResult = new String[1][];
            activity.runOnUiThread(() -> provider.evaluateJavaScript(
                    "(function(){var f=document.createElement('iframe');"
                            + "f.src='https://example.org/?"
                            + "sinytra-deny-probe=1';"
                            + "document.body.appendChild(f);"
                            + "return 'iframes='+document.querySelectorAll"
                            + "('iframe').length;})()",
                    value -> {
                        denyEvalResult[0] = new String[] {value};
                        android.util.Log.i("Sinytra/p0glue",
                                "deny inject eval result=" + value);
                        denyEvalDone.countDown();
                    }));
            if (!denyEvalDone.await(45, TimeUnit.SECONDS)) {
                throw new IllegalStateException("deny inject eval timeout");
            }
            long waited = 0;
            while (client.interceptedUri.get() == null && waited < 20_000) {
                Thread.sleep(250);
                waited += 250;
            }
            String denyUri = client.interceptedUri.get();
            if (denyUri == null) {
                throw new IllegalStateException(
                        "shouldInterceptRequest never saw the deny marker");
            }
            if (!denyUri.contains("sinytra-deny-probe")) {
                throw new IllegalStateException("deny marker uri: " + denyUri);
            }
            // Settle: an allowed load would be in-flight; deny keeps the
            // frame at its initial about:blank.
            Thread.sleep(8_000);
            final CountDownLatch frameDone = new CountDownLatch(1);
            final String[][] frameState = new String[1][];
            activity.runOnUiThread(() -> provider.evaluateJavaScript(
                    "(function(){try{return document.querySelector('iframe')"
                            + ".contentWindow.location.href}"
                            + "catch(e){return 'ERR:'+e.name}})()",
                    value -> {
                        frameState[0] = new String[] {value};
                        frameDone.countDown();
                    }));
            if (!frameDone.await(45, TimeUnit.SECONDS)) {
                throw new IllegalStateException("deny frame eval timeout");
            }
            String frameHref = frameState[0][0];
            if (frameHref == null) {
                throw new IllegalStateException("deny frame href null");
            }
            // evaluateJavaScript reports JSON-encoded: strip the quotes.
            String href = frameHref.replace("\"", "");
            if (!"about:blank".equals(href)) {
                throw new IllegalStateException(
                        "deny did not hold the frame at about:blank: " + href);
            }
            out.append("PASS denyIntercept uri=").append(denyUri)
                    .append(" frame=about:blank\n");
    }
}
