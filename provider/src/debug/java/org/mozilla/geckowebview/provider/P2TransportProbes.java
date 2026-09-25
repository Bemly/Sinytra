package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.view.View;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;
import java.lang.reflect.InvocationHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// P2 transport + androidx glue probes, extracted from P0GlueActivity
// (file-size rule): saveState/restoreState, evaluateJavascript,
// javascript interface bookkeeping, message channel, render process,
// glue entry/features, boundary live-port round-trip, visual state,
// visual surface. Interception probes (0001-0003 + P2-4 deny) live in
// P2InterceptProbes, which the orchestrator runs right after this.
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
            // Framework-typed channel: android.webkit.SinytraWebMessagePort
            // is live (2026-09-25, same-package subclass over the @SystemApi
            // framework ctor) — the pair must come back non-null.
            if (msgBox[0] == null) {
                throw new IllegalStateException(
                        "msgChannel: framework ports null");
            }
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

            // --- P2-3 framework-typed port round-trip (SinytraWebMessagePort)
            // The framework face rides the same MessageBridge/JsBridge page
            // transport as the boundary probe above: post on ports[1], the
            // shim loops it back to the same port id, the callback fires.
            // Chromium parity locked here: onMessage receives the port
            // ITSELF (not null), and closed ports throw IllegalStateException.
            final WebMessagePort[][] fwPorts = new WebMessagePort[1][];
            final String[] fwData = new String[1];
            final Throwable[] fwError = new Throwable[1];
            final CountDownLatch fwDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    WebMessagePort[] ports = provider.createWebMessageChannel();
                    if (ports == null || ports.length != 2) {
                        throw new IllegalStateException(
                                "fw createWebMessageChannel returned "
                                        + (ports == null ? "null" : ports.length));
                    }
                    fwPorts[0] = ports;
                    ports[1].setWebMessageCallback(
                            new WebMessagePort.WebMessageCallback() {
                                @Override
                                public void onMessage(WebMessagePort port,
                                        WebMessage message) {
                                    if (port != fwPorts[0][1]) {
                                        fwError[0] = new IllegalStateException(
                                                "onMessage port != receiver");
                                    }
                                    fwData[0] = message.getData();
                                    fwDone.countDown();
                                }
                            });
                    ports[1].postMessage(new WebMessage("sinytra-fwport-echo"));
                } catch (Throwable t) {
                    fwError[0] = t;
                    fwDone.countDown();
                }
            });
            if (!fwDone.await(45, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fw port timeout");
            }
            if (fwError[0] != null) {
                throw new IllegalStateException("fw port failed", fwError[0]);
            }
            if (!"sinytra-fwport-echo".equals(fwData[0])) {
                throw new IllegalStateException("fw port round-trip data: "
                        + fwData[0]);
            }
            // Closed-port + callback-once semantics (Chromium parity):
            // close drops exactly one bridge port; postMessage after close
            // and a second setWebMessageCallback both throw ISE.
            final int fwPortsBeforeClose = provider.messagePortCount();
            final Throwable[] fwCloseError = new Throwable[1];
            final CountDownLatch fwCloseDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    WebMessagePort[] ports = fwPorts[0];
                    ports[0].close();
                    try {
                        ports[0].postMessage(new WebMessage("after-close"));
                        fwCloseError[0] = new IllegalStateException(
                                "postMessage after close did not throw");
                    } catch (IllegalStateException expected) {
                        // Chromium parity
                    }
                    try {
                        ports[1].setWebMessageCallback(
                                new WebMessagePort.WebMessageCallback() { });
                        fwCloseError[0] = new IllegalStateException(
                                "second setWebMessageCallback did not throw");
                    } catch (IllegalStateException expected) {
                        // Chromium parity
                    }
                } catch (Throwable t) {
                    fwCloseError[0] = t;
                } finally {
                    fwCloseDone.countDown();
                }
            });
            fwCloseDone.await(10, TimeUnit.SECONDS);
            if (fwCloseError[0] != null) {
                throw new IllegalStateException("fw port close failed",
                        fwCloseError[0]);
            }
            if (provider.messagePortCount() != fwPortsBeforeClose - 1) {
                throw new IllegalStateException("fw close did not drop one "
                        + "port: before=" + fwPortsBeforeClose + " after="
                        + provider.messagePortCount());
            }
            out.append("PASS fwPort data=").append(fwData[0])
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

            // --- Visual surface: the provider's GeckoViewHost child sits
            // in the WebView's view tree and owns the surface through its
            // own view lifecycle (the harness docks the WebView into the
            // window). Structural locks here; pixel truth is screencapped
            // out-of-band during the run.
            final Throwable[] surfError = new Throwable[1];
            final CountDownLatch surfDone = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    org.mozilla.geckoview.GeckoView gv = null;
                    for (int i = 0; i < webView.getChildCount(); i++) {
                        View c = webView.getChildAt(i);
                        if (c instanceof org.mozilla.geckoview.GeckoView) {
                            gv = (org.mozilla.geckoview.GeckoView) c;
                            break;
                        }
                    }
                    if (gv == null) {
                        throw new IllegalStateException(
                                "no GeckoView child (children="
                                        + webView.getChildCount() + ")");
                    }
                    if (!gv.isAttachedToWindow()) {
                        throw new IllegalStateException(
                                "GeckoView child not attached to window");
                    }
                    if (gv.getSession() == null) {
                        throw new IllegalStateException(
                                "GeckoView child session null");
                    }
                } catch (Throwable t) {
                    surfError[0] = t;
                } finally {
                    surfDone.countDown();
                }
            });
            surfDone.await(10, TimeUnit.SECONDS);
            if (surfError[0] != null) {
                throw new IllegalStateException("visual surface failed",
                        surfError[0]);
            }
            out.append("PASS visualSurface child=GeckoView attached=true ")
                    .append("session=live\n");
    }
}
