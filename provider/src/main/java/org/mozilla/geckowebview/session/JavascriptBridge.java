package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// JavascriptBridge: addJavascriptInterface reflection surface.
// P2-2 spike result: GeckoView GV153 exposes NO Java-object-injection
// primitive — @JavascriptInterface semantics (method reflection,
// background-thread invocation, JSON marshalling, GC lifetime) need a
// firefox-patch (ARCHITECTURE.md §6.4, ROADMAP.md P2.2). Until then this
// class owns interface registration/removal bookkeeping plus the
// synchronous reflection dispatcher the patch will call into, so provider
// behavior (duplicate-name replace, remove, method filtering) is already
// testable without Gecko transport.
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

    public JavascriptBridge() {
        this.target = this;
        this.methods = new ConcurrentHashMap<>();
    }

    public void addInterface(@NonNull Object obj, @NonNull String name) {
        if (obj == null || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("object and name required");
        }
        mInterfaces.put(name, inspect(obj));
    }

    public void removeInterface(@NonNull String name) {
        mInterfaces.remove(name);
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
}
