package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// JavascriptBridge: addJavascriptInterface reflection surface + page-stub
// lifecycle over the JsBridge transport.
//
// P2-2 result: GV153 exposes NO Java-object-injection primitive
// (AAR javap confirmed) — @JavascriptInterface semantics (method
// reflection, background-thread invocation, JSON marshalling, GC
// lifetime) ride the built-in WebExtension instead (ARCHITECTURE.md
// section 6.4, ROADMAP.md P2.2): registerInterface installs an async
// window[iface] stub in page context (MAIN world, via content.js +
// page-shim.js); page->Java calls arrive as JsBridge PageEvents named
// "iface-call" and are answered via answerStubCall with the
// JSON-encoded return value.
//
// Divergences from Chromium, documented (never silently wrong):
// - Stub methods are ASYNC (return Promise): native messaging has no
//   synchronous round-trip. Page code written for sync Chromium
//   interfaces must await. A firefox-patch injecting synchronous
//   bindings would remove this gap.
// - Invocation runs on a background executor (Chromium parity:
//   @JavascriptInterface methods run off the UI thread); reflection
//   exceptions resolve the stub promise as a rejection, never crash.
// - Only @JavascriptInterface-annotated public methods are exposed;
//   overloads resolve by arity; duplicate-name add replaces; remove
//   uninstalls the page stub. Without transport (extension not ready)
//   bookkeeping still updates so provider behavior stays testable.
public final class JavascriptBridge {
    @NonNull
    public final Object target;
    @NonNull
    public final Map<String, Method> methods;

    private JavascriptBridge(@NonNull Object target,
            @NonNull Map<String, Method> methods) {
        this.target = target;
        this.methods = methods;
    }

    private final Map<String, JavascriptBridge> mInterfaces = new ConcurrentHashMap<>();
    @Nullable
    private volatile JsBridge mTransport;
    private final ExecutorService mCalls = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sinytra-jsiface");
        t.setDaemon(true);
        return t;
    });

    public JavascriptBridge() {
        this.target = this;
        this.methods = new ConcurrentHashMap<>();
    }

    // Bind the WebExtension transport. Called once by the provider after
    // the JsBridge is bound; the bridge stays usable without transport
    // (bookkeeping only) so unit behavior is testable.
    public void setTransport(@Nullable JsBridge bridge) {
        mTransport = bridge;
        if (bridge != null) {
            bridge.addPageEventListener(event -> {
                if ("iface-call".equals(event.optString("name", ""))) {
                    onIfaceCall(event.optJSONObject("payload"));
                }
            });
        }
    }

    public void addInterface(@NonNull Object obj, @NonNull String name) {
        if (obj == null || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("object and name required");
        }
        JavascriptBridge inspected = inspect(obj);
        mInterfaces.put(name, inspected);
        JsBridge bridge = mTransport;
        if (bridge != null && bridge.isReady()) {
            List<String> methodNames = new ArrayList<>();
            for (String key : inspected.methods.keySet()) {
                if (!key.contains("/")) {
                    methodNames.add(key);
                }
            }
            bridge.registerInterface(name, methodNames, value -> {
                if (value == null) {
                    android.util.Log.w("Sinytra/jsiface",
                            "register " + name + " failed: honest bookkeeping kept");
                }
            });
        }
    }

    public void removeInterface(@NonNull String name) {
        mInterfaces.remove(name);
        JsBridge bridge = mTransport;
        if (bridge != null && bridge.isReady()) {
            bridge.unregisterInterface(name, value -> {
            });
        }
    }

    @Nullable
    public JavascriptBridge lookup(@NonNull String name) {
        return mInterfaces.get(name);
    }

    public int interfaceCount() {
        return mInterfaces.size();
    }

    // Reflect @JavascriptInterface methods only (Chromium parity: only
    // annotated public methods are callable; overloads resolved by arity).
    @NonNull
    public static JavascriptBridge inspect(@NonNull Object obj) {
        Map<String, Method> found = new ConcurrentHashMap<>();
        for (Method method : obj.getClass().getMethods()) {
            if (method.getAnnotation(android.webkit.JavascriptInterface.class)
                    == null) {
                continue;
            }
            Method prev = found.get(method.getName());
            if (prev == null
                    || prev.getParameterTypes().length
                            != method.getParameterTypes().length) {
                found.put(method.getName() + "/" + method.getParameterTypes().length,
                        method);
            }
        }
        Map<String, Method> byArity = new ConcurrentHashMap<>(found);
        Map<String, Method> simple = new ConcurrentHashMap<>();
        for (Map.Entry<String, Method> entry : byArity.entrySet()) {
            String key = entry.getKey();
            simple.putIfAbsent(key.substring(0, key.lastIndexOf('/')), entry.getValue());
        }
        simple.putAll(byArity);
        return new JavascriptBridge(obj, simple);
    }

    @Nullable
    public Object invoke(@NonNull String method, @Nullable Object[] args)
            throws Exception {
        String key = args != null ? method + "/" + args.length : method;
        Method target = methods.get(key);
        if (target == null) {
            target = methods.get(method);
        }
        if (target == null) {
            throw new NoSuchMethodException(method);
        }
        return target.invoke(this.target, args);
    }

    // Page->Java stub call: {callId, iface, method, args}. Runs the
    // reflected method off the UI thread and answers through the bridge
    // so the page stub promise settles.
    private void onIfaceCall(@Nullable org.json.JSONObject payload) {
        if (payload == null) {
            return;
        }
        String callId = payload.optString("callId", null);
        String iface = payload.optString("iface", null);
        String method = payload.optString("method", null);
        if (callId == null || iface == null || method == null) {
            return;
        }
        JavascriptBridge target = mInterfaces.get(iface);
        JsBridge bridge = mTransport;
        if (target == null) {
            if (bridge != null) {
                bridge.answerStubCall(callId, false, null,
                        "no such interface: " + iface);
            }
            return;
        }
        org.json.JSONArray jsonArgs = payload.optJSONArray("args");
        Object[] args = jsonArgs != null ? new Object[jsonArgs.length()] : new Object[0];
        if (jsonArgs != null) {
            for (int i = 0; i < jsonArgs.length(); i++) {
                Object raw = jsonArgs.opt(i);
                args[i] = raw != null && raw != org.json.JSONObject.NULL
                        ? String.valueOf(raw) : null;
            }
        }
        final Object[] callArgs = args;
        mCalls.execute(() -> {
            JsBridge replyBridge = mTransport;
            try {
                Object out = target.invoke(method, callArgs);
                String json = jsonEncode(out);
                if (replyBridge != null) {
                    replyBridge.answerStubCall(callId, true, json, null);
                }
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/jsiface", "iface call threw", t);
                if (replyBridge != null) {
                    replyBridge.answerStubCall(callId, false, null,
                            String.valueOf(t.getMessage() != null ? t.getMessage() : t));
                }
            }
        });
    }

    @Nullable
    private static String jsonEncode(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            try {
                return new org.json.JSONObject().put("v", value).optString("v", "\"\"");
            } catch (Throwable ignored) {
                return "\"\"";
            }
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return new org.json.JSONObject().put("v", String.valueOf(value))
                    .optString("v", "\"\"");
        } catch (Throwable ignored) {
            return "null";
        }
    }
}
