package org.mozilla.geckowebview.session;

import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewRenderProcess;
import android.webkit.WebViewRenderProcessClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.Executor;

// RenderProcessBridge: WebViewRenderProcessClient alignment surface.
// P2-7 spike result: Gecko crash signal today is ContentDelegate.onCrash
// (already fanned out as onReceivedError). Full RenderProcess semantics
// (terminate(), responsive/unresponsive, priority-at-exit) have no GV153
// equivalent — needs a firefox-patch for parity. Until then: own the
// client registration, report crash=true gone-details on onCrash, and
// answer getWebViewRenderProcess() with a stable token object.
public final class RenderProcessBridge {
    @Nullable
    private volatile Executor mExecutor;
    @Nullable
    private volatile WebViewRenderProcessClient mClient;
    // API 29 framework class: created lazily behind an SDK guard so the
    // bridge (constructed eagerly inside the provider) loads on 26-28 too.
    @Nullable
    private volatile SinytraRenderProcess mProcess;

    public void setClient(@Nullable Executor executor,
            @Nullable WebViewRenderProcessClient client) {
        mExecutor = executor;
        mClient = client;
    }

    @Nullable
    public WebViewRenderProcessClient getClient() {
        return mClient;
    }

    @Nullable
    public WebViewRenderProcess process() {
        SinytraRenderProcess process = mProcess;
        if (process == null) {
            if (android.os.Build.VERSION.SDK_INT < 29) {
                android.util.Log.w("Sinytra/render",
                        "process(): WebViewRenderProcess is API 29+");
                return null;
            }
            synchronized (this) {
                process = mProcess;
                if (process == null) {
                    process = new SinytraRenderProcess();
                    mProcess = process;
                }
            }
        }
        return process;
    }

    public void onGeckoCrash(@NonNull WebView view,
            @Nullable android.webkit.WebViewClient webViewClient) {
        WebViewRenderProcessClient client = mClient;
        RenderProcessGoneDetail detail = new RenderProcessGoneDetail() {
            @Override
            public boolean didCrash() {
                return true;
            }

            @Override
            public int rendererPriorityAtExit() {
                return android.webkit.WebView.RENDERER_PRIORITY_WAIVED;
            }
        };
        if (webViewClient != null) {
            try {
                if (webViewClient.onRenderProcessGone(view, detail)) {
                    return;
                }
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render",
                        "WebViewClient.onRenderProcessGone threw", t);
            }
        }
        if (client == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            android.util.Log.w("Sinytra/render",
                    "onGeckoCrash: renderer client is API 29+");
            return;
        }
        Executor executor = mExecutor;
        Runnable dispatch = () -> {
            try {
                client.onRenderProcessUnresponsive(view, process());
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render",
                        "onRenderProcessUnresponsive threw", t);
            }
        };
        if (executor != null) {
            try {
                executor.execute(dispatch);
                return;
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render", "executor threw", t);
            }
        }
        dispatch.run();
    }

    public void onResponsive(@NonNull WebView view, boolean responsive) {
        WebViewRenderProcessClient client = mClient;
        if (client == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            android.util.Log.w("Sinytra/render",
                    "onResponsive: renderer client is API 29+");
            return;
        }
        Executor executor = mExecutor;
        Runnable dispatch = () -> {
            try {
                if (responsive) {
                    client.onRenderProcessResponsive(view, process());
                } else {
                    client.onRenderProcessUnresponsive(view, process());
                }
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render", "responsive dispatch threw", t);
            }
        };
        if (executor != null) {
            try {
                executor.execute(dispatch);
                return;
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render", "executor threw", t);
            }
        }
        dispatch.run();
    }

    @androidx.annotation.RequiresApi(29)
    static final class SinytraRenderProcess extends WebViewRenderProcess {
        @Override
        public boolean terminate() {
            return false;
        }
    }
}
