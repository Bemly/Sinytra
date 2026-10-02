package org.mozilla.geckowebview.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebRequestInfo;
import org.mozilla.geckowebview.session.GeckoSessionBridge;
import org.mozilla.geckowebview.session.InterceptBridge;
import org.mozilla.geckowebview.session.ResponseBridge;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// shouldInterceptRequest interception surface, split out of
// GeckoWebViewProvider (AGENTS.md §7 file-size rule). Owns the filter
// table, the loadData one-shot bodies and the 0001 response bridge;
// the provider keeps thin delegates so the probe/compat call surface
// (setInterceptFilters, loadDataWithBaseURL) does not move.
final class Interception {
    interface Host {
        @NonNull
        org.mozilla.geckowebview.session.GeckoSessionBridge bridge();

        @NonNull
        GeckoWebSettings settings();

        boolean destroyed();
    }

    @NonNull
    private final Host mHost;
    @NonNull
    private final InterceptFilterTable mFilters = new InterceptFilterTable();
    @NonNull
    private final LoadDataHandler mLoadData;
    @NonNull
    private final ResponseBridge mResponses;

    Interception(@NonNull Host host, @NonNull InterceptBridge intercept) {
        mHost = host;
        mLoadData = new LoadDataHandler(new LoadDataHandler.Host() {
            @Override
            public void navigateTo(@NonNull String url) {
                mHost.bridge().loadUrl(url);
            }

            @Override
            public void loadDataUri(byte[] data, @NonNull String mimeType) {
                mHost.bridge().session().load(
                        new GeckoSession.Loader().data(data, mimeType));
            }

            @Override
            public void addInterceptFilter(@NonNull String prefix) {
                mFilters.add(prefix);
                pushEffectiveFilters();
            }

            @Override
            public void logWarning(@NonNull String message) {
                android.util.Log.w(TAG, message);
            }
        });
        final InterceptBridge query = intercept;
        mResponses = new ResponseBridge(new ResponseBridge.Host() {
            @Override
            @NonNull
            public String[] getFilters() {
                return mFilters.effective();
            }

            @Override
            @Nullable
            public ResponseBridge.WebResourceResponseHolder shouldIntercept(
                    @NonNull WebRequestInfo info) {
                // loadData one-shot first (internal, never consults the app).
                LoadDataHandler.OneShotBody oneShot = info.uri != null
                        ? mLoadData.consume(info.uri) : null;
                if (oneShot != null) {
                    return new ResponseBridge.WebResourceResponseHolder(
                            oneShot.mimeType, "utf-8", 200,
                            new java.io.ByteArrayInputStream(oneShot.bytes));
                }
                // Settings policy before the app (Chromium never calls
                // shouldInterceptRequest for policy-blocked loads):
                // navigations defer to the LoadRequest DENY path, which
                // cancels instead of substituting.
                if (!info.isNavigation && info.uri != null) {
                    String page = info.triggerUri != null
                            && !info.triggerUri.isEmpty() ? info.triggerUri
                                    : mHost.bridge().getUrl();
                    if (LoadPolicy.blockSubresource(info.contentPolicyType,
                            info.uri, page, mHost.settings())) {
                        return new ResponseBridge.WebResourceResponseHolder(
                                "text/plain", "utf-8", 200,
                                new java.io.ByteArrayInputStream(new byte[0]));
                    }
                }
                // 0002: the necko query carries the request surface —
                // isTopLevel maps to WebResourceRequest.isForMainFrame,
                // method/headers pass through unchanged.
                android.webkit.WebResourceResponse app = query.queryApp(
                        info.uri, false, false, info.isTopLevel, info.method,
                        info.requestHeaders);
                if (app == null) {
                    return null;
                }
                return new ResponseBridge.WebResourceResponseHolder(
                        app.getMimeType(), app.getEncoding(),
                        app.getStatusCode(), app.getData());
            }
        });
    }

    private static final String TAG = "Sinytra/provider";

    @NonNull
    ResponseBridge responses() {
        return mResponses;
    }

    @NonNull
    String[] appFilters() {
        return mFilters.appFilters();
    }

    @NonNull
    String[] effectiveFilters() {
        return mFilters.effective();
    }

    void setFilters(@NonNull String[] filters) {
        mFilters.set(filters);
        // Re-dispatch the delegate: setResponseDelegate re-pushes filters
        // to the Gecko interception controller (effective set always
        // carries the universal prefix).
        pushEffectiveFilters();
    }

    // Re-push the effective filters on demand. The ResponseFilters event
    // is fire-and-forget: dispatched before the parent JS module
    // (GeckoViewNavigation) finishes onInit, it lands on no listener and
    // is lost silently — necko then consults an empty table forever
    // (CTS PostMessage family: about:blank completes, the http upgrade
    // never even reaches ShouldPrepare). The first PageStart proves the
    // module pipeline is alive, so the provider re-pushes once there;
    // repeats carry identical prefixes and are idempotent C++-side.
    //
    // The PageStart repush alone is not enough: a filter-waiting channel
    // may never produce a PageStart (CTS single-test runs observed
    // observer-registered → onLoadRequest → 20s silence, no PageStop).
    // Every push therefore also schedules time-based retries covering a
    // slow onInit window. Blind time retries are not enough either: a
    // cold CTS process can stall JS init past any fixed delay (no
    // ensureBuiltIn, no PageStart at all). The retries are hence gated on
    // transport liveness (JsBridge.isReady: extension installed + session
    // delegate bound — the same parent-JS/extension bring-up the filter
    // module rides on): while the pipeline is not alive the retry backs
    // off instead of pushing into the void, so a slow init still gets a
    // push the moment it completes. A newer push supersedes pending
    // retries via the generation guard (repeats stay idempotent C++-side).
    // Best-effort: without a Looper (JVM unit tests) no retries run and
    // only the synchronous push executes.
    static final int MAX_RETRY_ATTEMPTS = 5;

    private int mPushGeneration;
    @Nullable
    private Readiness mReadiness;

    /** Transport-liveness gate for filter retries (provider wires JsBridge). */
    interface Readiness {
        boolean isReady();
    }

    // Pure (JVM-testable): attempt is 1-based; 500ms doubling to an 8s cap
    // covers a slow onInit window (~15s total) without spamming.
    static long retryDelayMs(int attempt) {
        long delay = 500L << (attempt - 1);
        return Math.min(delay, 8000L);
    }

    // Pure (JVM-testable): a scheduled retry is live only when its
    // generation still matches the latest push.
    static boolean isRepushLive(int scheduledGeneration,
            int currentGeneration) {
        return scheduledGeneration == currentGeneration;
    }

    void setTransportReady(@Nullable Readiness readiness) {
        mReadiness = readiness;
    }

    void repushFilters() {
        pushEffectiveFilters();
    }

    void loadDataWithBaseURL(@Nullable String baseUrl, @Nullable String data,
            @Nullable String mimeType, @Nullable String encoding,
            @Nullable String historyUrl) {
        mLoadData.loadDataWithBaseURL(baseUrl, data, mimeType, encoding,
                historyUrl);
    }

    private void pushEffectiveFilters() {
        if (mHost.destroyed()) {
            return;
        }
        try {
            mHost.bridge().session().setResponseDelegate(mResponses);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "pushEffectiveFilters threw", t);
            return;
        }
        final int generation = ++mPushGeneration;
        scheduleRetry(generation, 1);
    }

    private void scheduleRetry(final int generation, final int attempt) {
        if (attempt > MAX_RETRY_ATTEMPTS) {
            return;
        }
        final android.os.Handler handler;
        try {
            handler = new android.os.Handler(
                    android.os.Looper.getMainLooper());
        } catch (Throwable t) {
            // No Looper (JVM unit tests): retries are skipped, the
            // synchronous push above already ran.
            return;
        }
        try {
            handler.postDelayed(() -> {
                if (!isRepushLive(generation, mPushGeneration)
                        || mHost.destroyed()) {
                    return;
                }
                Readiness readiness = mReadiness;
                if (readiness != null) {
                    boolean alive;
                    try {
                        alive = readiness.isReady();
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "readiness threw", t);
                        return;
                    }
                    if (!alive) {
                        // Pipeline not up yet: back off, do not push into
                        // the void (the dispatch would be lost silently).
                        scheduleRetry(generation, attempt + 1);
                        return;
                    }
                }
                try {
                    mHost.bridge().session()
                            .setResponseDelegate(mResponses);
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "filter retry threw", t);
                }
            }, retryDelayMs(attempt));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "scheduleRetry threw", t);
        }
    }
}
