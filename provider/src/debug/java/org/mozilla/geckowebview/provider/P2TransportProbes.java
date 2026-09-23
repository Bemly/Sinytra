package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.webkit.WebView;
import java.lang.reflect.InvocationHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// P2 transport + androidx glue probes, extracted from P0GlueActivity
// (file-size rule): saveState/restoreState, evaluateJavascript,
// javascript interface bookkeeping, message channel, render process,
// glue entry/features, boundary live-port round-trip, visual state.
// Same harness contract as the P0/P1 sections: every probe appends a
// PASS line or throws — the orchestrator turns a throw into P0 GLUE FAIL.
//
// Requires: a live provider (session loaded, transport ready per the
// jsEval retry loop) and the harness factory for the glue entry.
final class P2TransportProbes {
    private P2TransportProbes() {}

    static void run(Activity activity, StringBuilder out,
            GeckoWebViewProvider provider, WebView webView,
            GeckoWebViewFactoryProvider factory,
            P0GlueActivity.TestClient client)
            throws Exception {
            // --- P2 saveState/restoreState round-trip ---
            final android.os.Bundle[] stateBox = new android.os.Bundle[1];
            final Throwable[] stateError = new Throwable[1];
            final CountDownLatch stateDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    android.os.Bundle outState = new android.os.Bundle();
                    android.webkit.WebBackForwardList saved =
                            provider.saveState(outState);
                    if (saved.getSize() < 1) {
                        throw new IllegalStateException(
                                "saveState size=" + saved.getSize());
                    }
                    stateBox[0] = outState;
                } catch (Throwable t) {
                    stateError[0] = t;
                } finally {
                    stateDone.countDown();
                }
            });
            stateDone.await(10, TimeUnit.SECONDS);
            if (stateError[0] != null) {
                throw new IllegalStateException("saveState failed", stateError[0]);
            }
            out.append("PASS saveState\n");
            final Throwable[] restoreError = new Throwable[1];
            final CountDownLatch restoreDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    provider.restoreState(stateBox[0]);
                } catch (Throwable t) {
                    restoreError[0] = t;
                } finally {
                    restoreDone.countDown();
                }
            });
            restoreDone.await(10, TimeUnit.SECONDS);
            if (restoreError[0] != null) {
                throw new IllegalStateException("restoreState failed", restoreError[0]);
            }
            out.append("PASS restoreState\n");

            // --- P2 evaluateJavascript via JsBridge transport ---
            // Ready path: extension installed -> JSON-encoded title.
            // Not-ready path (install still in flight): honest-null.
            // Either way the callback MUST fire (no hang, no throw).
            // NOTE: the first eval after a navigation races the new
            // document's content-script loop (old generation dies with
            // the old document; new loop starts at document_start but
            // the poll only arrives after the script runs). Retry a few
            // times on null before accepting honest-null.
            String jsValue = null;
            boolean jsReady = false;
            for (int attempt = 0; attempt < 4 && !jsReady; attempt++) {
                final CountDownLatch jsDone = new CountDownLatch(1);
                final String[][] jsResult = new String[1][];
                final Throwable[] jsError = new Throwable[1];
                activity.runOnUiThread(() -> {
                    try {
                        provider.evaluateJavaScript("document.title",
                                value -> {
                                    jsResult[0] = new String[] {value};
                                    jsDone.countDown();
                                });
                    } catch (Throwable t) {
                        jsError[0] = t;
                        jsDone.countDown();
                    }
                });
                if (!jsDone.await(45, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("js timeout");
                }
                if (jsError[0] != null) {
                    throw new IllegalStateException("js failed", jsError[0]);
                }
                jsValue = jsResult[0][0];
                jsReady = jsValue != null;
                if (!jsReady) {
                    Thread.sleep(2000);
                }
            }
            out.append("PASS jsEval ready=").append(jsReady)
                    .append(" value=").append(jsValue).append('\n');
            // Second probe: arithmetic must round-trip as JSON when ready
            // (1+2 -> "3"); null is accepted only when not ready.
            final CountDownLatch jsDone2 = new CountDownLatch(1);
            final String[][] jsResult2 = new String[1][];
            activity.runOnUiThread(() -> provider.evaluateJavaScript("1+2",
                    value -> {
                        jsResult2[0] = new String[] {value};
                        jsDone2.countDown();
                    }));
            if (!jsDone2.await(45, TimeUnit.SECONDS)) {
                throw new IllegalStateException("js2 timeout");
            }
            String jsValue2 = jsResult2[0][0];
            if (jsReady && !"3".equals(jsValue2)) {
                throw new IllegalStateException("js arithmetic: " + jsValue2);
            }
            out.append("PASS jsArith value=").append(jsValue2).append('\n');

            // --- P2 addJavascriptInterface bookkeeping ---
            final Throwable[] jiError = new Throwable[1];
            final CountDownLatch jiDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    provider.addJavascriptInterface(new Object() {
                        @android.webkit.JavascriptInterface
                        public String echo(String s) {
                            return s;
                        }
                    }, "TestBridge");
                    provider.removeJavascriptInterface("TestBridge");
                } catch (Throwable t) {
                    jiError[0] = t;
                } finally {
                    jiDone.countDown();
                }
            });
            jiDone.await(10, TimeUnit.SECONDS);
            if (jiError[0] != null) {
                throw new IllegalStateException("jsInterface failed", jiError[0]);
            }
            out.append("PASS jsInterface\n");

            // --- P2 message channel create ---
            final Object[][] msgBox = new Object[1][];
            final Throwable[] msgError = new Throwable[1];
            final CountDownLatch msgDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    msgBox[0] = provider.createWebMessageChannel();
                } catch (Throwable t) {
                    msgError[0] = t;
                } finally {
                    msgDone.countDown();
                }
            });
            msgDone.await(10, TimeUnit.SECONDS);
            if (msgError[0] != null) {
                throw new IllegalStateException("msgChannel failed", msgError[0]);
            }
            // WebMessagePort is abstract: framework-typed ports need the P2
            // patch's concrete subclass. Bridge bookkeeping holds 2 ports per
            // channel; verify via count (msgBox holds framework array or null).
            out.append("PASS msgChannel fwNull=").append(msgBox[0] == null)
                    .append(" bridgePorts=").append(provider.messagePortCount())
                    .append('\n');

            // --- P2 renderProcess token ---
            final Object[] renderBox = new Object[1];
            final Throwable[] renderError = new Throwable[1];
            final CountDownLatch renderDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    renderBox[0] = provider.getWebViewRenderProcess();
                } catch (Throwable t) {
                    renderError[0] = t;
                } finally {
                    renderDone.countDown();
                }
            });
            renderDone.await(10, TimeUnit.SECONDS);
            if (renderError[0] != null || renderBox[0] == null) {
                throw new IllegalStateException("renderProcess failed", renderError[0]);
            }
            out.append("PASS renderProcess\n");

            // --- P2-8 androidx glue: SupportLibReflectionUtil entry ---
            final Object[] glueBox = new Object[1];
            final Throwable[] glueError = new Throwable[1];
            final CountDownLatch glueDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    Class<?> glueClass = Class.forName(
                            "org.chromium.support_lib_glue.SupportLibReflectionUtil",
                            false,
                            GeckoWebViewFactoryProvider.class.getClassLoader());
                    java.lang.reflect.Method m = glueClass.getDeclaredMethod(
                            "createWebViewProviderFactory");
                    glueBox[0] = m.invoke(null);
                } catch (Throwable t) {
                    glueError[0] = t;
                } finally {
                    glueDone.countDown();
                }
            });
            glueDone.await(10, TimeUnit.SECONDS);
            if (glueError[0] != null || glueBox[0] == null) {
                throw new IllegalStateException("glue entry failed", glueError[0]);
            }
            out.append("PASS glue entry=")
                    .append(glueBox[0].getClass().getName()).append('\n');

            // --- P2-8 getSupportedFeatures honest set ---
            final Object[] featBox = new Object[1];
            final Throwable[] featError = new Throwable[1];
            final CountDownLatch featDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    java.lang.reflect.InvocationHandler handler =
                            (java.lang.reflect.InvocationHandler) glueBox[0];
                    Object[] noArgs = null;
                    java.lang.reflect.Method getFeatures = null;
                    for (java.lang.reflect.Method candidate
                            : org.chromium.support_lib_boundary
                                    .WebViewProviderFactoryBoundaryInterface.class
                                    .getMethods()) {
                        if (candidate.getName().equals("getSupportedFeatures")) {
                            getFeatures = candidate;
                            break;
                        }
                    }
                    featBox[0] = handler.invoke(null, getFeatures, noArgs);
                } catch (Throwable t) {
                    featError[0] = t;
                } finally {
                    featDone.countDown();
                }
            });
            featDone.await(10, TimeUnit.SECONDS);
            if (featError[0] != null || !(featBox[0] instanceof String[])) {
                throw new IllegalStateException("features failed", featError[0]);
            }
            String[] features = (String[]) featBox[0];
            boolean hasClient = false;
            boolean hasRenderer = false;
            boolean hasTracing = false;
            boolean leaksJsInjection = false;
            for (String f : features) {
                if ("GET_WEB_VIEW_CLIENT".equals(f)) {
                    hasClient = true;
                }
                if ("GET_WEB_VIEW_RENDERER".equals(f)) {
                    hasRenderer = true;
                }
                if ("TRACING_CONTROLLER_BASIC_USAGE".equals(f)) {
                    hasTracing = true;
                }
                if ("JS_INJECTION_IN_FRAME_AND_WORLD".equals(f)) {
                    leaksJsInjection = true;
                }
                out.append("feature ").append(f).append('\n');
            }
            if (!hasClient || !hasRenderer || !hasTracing || leaksJsInjection) {
                throw new IllegalStateException("feature set dishonest");
            }
            out.append("PASS features count=").append(features.length).append('\n');

            // --- P2-3 boundary live port round-trip (androidx.webkit surface) ---
            // Drives glue factory createWebView -> createWebMessageChannel ->
            // LiveMessagePort -> MessageBridge -> JsBridge mailbox -> page shim
            // echo (sinytra-port-deliver) -> port-deliver event -> boundary
            // onMessage. The shim loops a port post back to the same port id,
            // so the callback must fire with the posted data. When the
            // transport is not ready MessageBridge falls back to local
            // delivery: same data, same callback, labelled honestly via=.
            final String[] bndData = new String[1];
            final java.lang.reflect.InvocationHandler[][] bndPorts =
                    new java.lang.reflect.InvocationHandler[1][];
            final Throwable[] bndError = new Throwable[1];
            final CountDownLatch bndDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    org.chromium.support_lib_glue.SupportLibReflectionUtil
                            .setFactoryForTests(factory);
                    Object glueHandler =
                            org.chromium.support_lib_glue.SupportLibReflectionUtil
                                    .createWebViewProviderFactory();
                    java.lang.reflect.Method createWebViewM =
                            org.chromium.support_lib_boundary
                                    .WebViewProviderFactoryBoundaryInterface.class
                                    .getMethod("createWebView", WebView.class);
                    java.lang.reflect.InvocationHandler providerHandler =
                            (java.lang.reflect.InvocationHandler) ((java.lang.reflect.InvocationHandler)
                                    glueHandler).invoke(null, createWebViewM,
                                            new Object[] {webView});
                    java.lang.reflect.Method createChannelM =
                            org.chromium.support_lib_boundary
                                    .WebViewProviderBoundaryInterface.class
                                    .getMethod("createWebMessageChannel");
                    java.lang.reflect.InvocationHandler[] ports =
                            (java.lang.reflect.InvocationHandler[])
                                    providerHandler.invoke(null, createChannelM, null);
                    if (ports == null || ports.length != 2) {
                        throw new IllegalStateException(
                                "boundary createWebMessageChannel returned "
                                        + (ports == null ? "null" : ports.length));
                    }
                    bndPorts[0] = ports;
                    java.lang.reflect.Method setCallbackM =
                            org.chromium.support_lib_boundary
                                    .WebMessagePortBoundaryInterface.class
                                    .getMethod("setWebMessageCallback",
                                            java.lang.reflect.InvocationHandler.class);
                    java.lang.reflect.Method getDataM =
                            org.chromium.support_lib_boundary
                                    .WebMessageBoundaryInterface.class
                                    .getMethod("getData");
                    java.lang.reflect.InvocationHandler callback =
                            (proxy, method, args) -> {
                                if ("onMessage".equals(method.getName())) {
                                    try {
                                        bndData[0] = (String)
                                                ((java.lang.reflect.InvocationHandler)
                                                        args[1]).invoke(
                                                        null, getDataM, null);
                                    } catch (Throwable t) {
                                        bndError[0] = t;
                                    }
                                    bndDone.countDown();
                                    return null;
                                }
                                throw new UnsupportedOperationException(
                                        "boundary callback: " + method.getName());
                            };
                    ports[1].invoke(null, setCallbackM, new Object[] {callback});
                    java.lang.reflect.Method postMessageM =
                            org.chromium.support_lib_boundary
                                    .WebMessagePortBoundaryInterface.class
                                    .getMethod("postMessage",
                                            java.lang.reflect.InvocationHandler.class);
                    java.lang.reflect.InvocationHandler message =
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "getData":
                                        return "sinytra-boundary-echo";
                                    case "getMessagePayload":
                                        return null;
                                    case "getPorts":
                                        return new java.lang.reflect
                                                .InvocationHandler[0];
                                    default:
                                        throw new UnsupportedOperationException(
                                                "boundary message: "
                                                        + method.getName());
                                }
                            };
                    ports[1].invoke(null, postMessageM, new Object[] {message});
                } catch (Throwable t) {
                    bndError[0] = t;
                    bndDone.countDown();
                }
            });
            if (!bndDone.await(45, TimeUnit.SECONDS)) {
                throw new IllegalStateException("boundary port timeout");
            }
            if (bndError[0] != null) {
                throw new IllegalStateException("boundary port failed", bndError[0]);
            }
            if (!"sinytra-boundary-echo".equals(bndData[0])) {
                throw new IllegalStateException("boundary round-trip data: "
                        + bndData[0]);
            }
            // Close the unused half: the boundary close() must route into
            // MessageBridge and drop exactly one port (the earlier msgChannel
            // probe already left its own 2 bookkeeping ports, so the absolute
            // count is not 1 — assert the delta).
            final int portsBeforeClose = provider.messagePortCount();
            final CountDownLatch bndCloseDone = new CountDownLatch(1);
            final Throwable[] bndCloseError = new Throwable[1];
            activity.runOnUiThread(() -> {
                try {
                    java.lang.reflect.Method closeM =
                            org.chromium.support_lib_boundary
                                    .WebMessagePortBoundaryInterface.class
                                    .getMethod("close");
                    bndPorts[0][0].invoke(null, closeM, null);
                } catch (Throwable t) {
                    bndCloseError[0] = t;
                } finally {
                    bndCloseDone.countDown();
                }
            });
            bndCloseDone.await(10, TimeUnit.SECONDS);
            if (bndCloseError[0] != null) {
                throw new IllegalStateException("boundary close failed",
                        bndCloseError[0]);
            }
            if (provider.messagePortCount() != portsBeforeClose - 1) {
                throw new IllegalStateException("boundary close did not drop "
                        + "one port: before=" + portsBeforeClose
                        + " after=" + provider.messagePortCount());
            }
            out.append("PASS boundaryPort data=").append(bndData[0])
                    .append(" via=").append(jsReady ? "page" : "local")
                    .append(" bridgePorts=").append(provider.messagePortCount())
                    .append('\n');

            // --- P2-8 visual state callback fires on page stop ---
            final CountDownLatch vsDone = new CountDownLatch(1);
            final Throwable[] vsError = new Throwable[1];
            activity.runOnUiThread(() -> {
                try {
                    provider.insertVisualStateCallback(42L,
                            new WebView.VisualStateCallback() {
                                @Override
                                public void onComplete(long requestId) {
                                    if (requestId == 42L) {
                                        vsDone.countDown();
                                    }
                                }
                            });
                    provider.loadUrl("https://example.com/");
                } catch (Throwable t) {
                    vsError[0] = t;
                    vsDone.countDown();
                }
            });
            if (!vsDone.await(45, TimeUnit.SECONDS)) {
                throw new IllegalStateException("visual state timeout");
            }
            if (vsError[0] != null) {
                throw new IllegalStateException("visual state failed", vsError[0]);
            }
            out.append("PASS visualState\n");

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
