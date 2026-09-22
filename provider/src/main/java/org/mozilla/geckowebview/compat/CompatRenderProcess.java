package org.mozilla.geckowebview.compat;

import android.webkit.WebViewRenderProcess;
import android.webkit.WebViewRenderProcessClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import org.chromium.support_lib_boundary.IsomorphicObjectBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewRendererBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewRendererClientBoundaryInterface;

// Renderer boundary pair: WebViewRendererBoundaryInterface over the stable
// provider token (terminate()=false until the P2 patch), and the renderer
// client pair in both directions (framework->boundary unwrap, boundary->
// framework wrap for setWebViewRendererClient).
public final class CompatRenderProcess {
    private CompatRenderProcess() {}

    @NonNull
    public static InvocationHandler create(@Nullable WebViewRenderProcess process) {
        return new RendererStub(process);
    }

    private static final class RendererStub implements InvocationHandler {
        @Nullable
        private final WebViewRenderProcess mToken;

        RendererStub(@Nullable WebViewRenderProcess token) {
            mToken = token;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "terminate":
                    // WebViewRenderProcess.terminate is API 29+; on 26-28 no
                    // framework renderer token can exist (the class did not
                    // exist), so the honest answer is the same: false.
                    return mToken != null
                            && android.os.Build.VERSION.SDK_INT >= 29
                            && mToken.terminate();
                case "getOrCreatePeer":
                    return mToken;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "CompatRenderProcess";
                default:
                    throw new UnsupportedOperationException(
                            "CompatRenderProcess: " + method.getName());
            }
        }
    }

    @Nullable
    public static InvocationHandler clientHandler(
            @Nullable WebViewRenderProcessClient client) {
        if (client == null) {
            return null;
        }
        return new ForwardingClient(client);
    }

    public static final class ForwardingClient implements InvocationHandler {
        @NonNull
        private final WebViewRenderProcessClient mClient;

        public ForwardingClient(@NonNull WebViewRenderProcessClient client) {
            mClient = client;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            // WebViewRenderProcessClient and its callbacks are API 29+; on
            // 26-28 no framework renderer client can exist, so forwarding
            // is honestly a no-op there.
            boolean api29 = android.os.Build.VERSION.SDK_INT >= 29;
            switch (method.getName()) {
                case "onRendererUnresponsive":
                    if (api29) {
                        mClient.onRenderProcessUnresponsive(
                                (android.webkit.WebView) args[0],
                                (WebViewRenderProcess) unwrap(args[1]));
                    }
                    return null;
                case "onRendererResponsive":
                    if (api29) {
                        mClient.onRenderProcessResponsive(
                                (android.webkit.WebView) args[0],
                                (WebViewRenderProcess) unwrap(args[1]));
                    }
                    return null;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "CompatRenderProcessClient";
                default:
                    throw new UnsupportedOperationException(
                            "CompatRenderProcessClient: " + method.getName());
            }
        }

        @Nullable
        private static Object unwrap(@Nullable Object handler) {
            if (!(handler instanceof InvocationHandler)) {
                return handler;
            }
            try {
                Object delegate = org.chromium.support_lib_boundary.util
                        .BoundaryInterfaceReflectionUtil
                        .getDelegateFromInvocationHandler((InvocationHandler) handler);
                return delegate != null ? delegate : handler;
            } catch (Throwable t) {
                return handler;
            }
        }
    }

    // Boundary client -> framework client, for setWebViewRendererClient.
    // API 29+: the framework class does not exist below Q, so callers must
    // guard on Build.VERSION.SDK_INT (setCompatRendererClient does).
    @androidx.annotation.RequiresApi(29)
    @NonNull
    public static WebViewRenderProcessClient wrapClient(
            @NonNull InvocationHandler boundary, @Nullable Executor executor) {
        return new WebViewRenderProcessClient() {
            @Override
            public void onRenderProcessUnresponsive(android.webkit.WebView view,
                    WebViewRenderProcess renderer) {
                dispatch(boundary, "onRendererUnresponsive", view, renderer, executor);
            }

            @Override
            public void onRenderProcessResponsive(android.webkit.WebView view,
                    WebViewRenderProcess renderer) {
                dispatch(boundary, "onRendererResponsive", view, renderer, executor);
            }
        };
    }

    private static void dispatch(@NonNull InvocationHandler boundary,
            @NonNull String method, android.webkit.WebView view,
            WebViewRenderProcess renderer, @Nullable Executor executor) {
        Runnable task = () -> {
            try {
                Method target = null;
                for (Method candidate : WebViewRendererClientBoundaryInterface.class
                        .getMethods()) {
                    if (candidate.getName().equals(method)) {
                        target = candidate;
                        break;
                    }
                }
                if (target == null) {
                    return;
                }
                InvocationHandler rendererHandler = create(renderer);
                boundary.invoke(null, target, new Object[] {view, rendererHandler});
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/compat", "renderer dispatch threw", t);
            }
        };
        if (executor != null) {
            try {
                executor.execute(task);
                return;
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/compat", "renderer executor threw", t);
            }
        }
        task.run();
    }
}
