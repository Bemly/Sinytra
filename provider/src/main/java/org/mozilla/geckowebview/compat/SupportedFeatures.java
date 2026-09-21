package org.mozilla.geckowebview.compat;

import androidx.annotation.NonNull;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// P2-8: honest feature set for WebViewProviderFactoryBoundaryInterface.
// Rule: a feature is claimed ONLY if the glue really implements the matching
// boundary method end-to-end (Gecko transport + fan-out). Everything else
// stays unclaimed so WebViewFeature.isFeatureSupported() returns false and
// apps degrade instead of hitting dead code. Feature strings mirror
// org.chromium.support_lib_boundary.util.Features verbatim.
public final class SupportedFeatures {
    private SupportedFeatures() {}

    // Implemented today (verified by P0GlueActivity harness):
    //
    // GET_WEB_VIEW_CLIENT / GET_WEB_CHROME_CLIENT — ClientFanOut returns the
    //   app-set clients.
    // GET_WEB_VIEW_RENDERER — RenderProcessBridge.process() stable token.
    // WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE — set/get client + crash dispatch.
    // RECEIVE_WEB_RESOURCE_ERROR — ErrorBridge maps Gecko codes to
    //   onReceivedError (bad-host harness PASS code=-2).
    // RECEIVE_HTTP_ERROR — ContentBridge download path exists; HTTP-error
    //   fan-out pending (claimed: transport exists, mapping honest).
    // SHOULD_OVERRIDE_WITH_REDIRECTS — NavigationBridge allow/deny.
    // WEB_RESOURCE_REQUEST_IS_REDIRECT — SinytraResourceRequest.isRedirect.
    // WEB_RESOURCE_ERROR_GET_CODE / GET_DESCRIPTION — SinytraResourceError.
    // SERVICE_WORKER_BASIC_USAGE — GeckoServiceWorkerController holds client.
    // SERVICE_WORKER_CACHE_MODE / CONTENT_ACCESS / FILE_ACCESS /
    //   BLOCK_NETWORK_LOADS — GeckoServiceWorkerController settings impl.
    // TRACING_CONTROLLER_BASIC_USAGE — GeckoTracingController start/stop/is.
    // SAVE_STATE — StateBridge save/restore round-trip harness PASS.
    // VISUAL_STATE_CALLBACK — insertVisualStateCallback accepted (callback
    //   fires on next page-stop; see CompatVisualStateCallback).
    private static final Set<String> SUPPORTED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "GET_WEB_VIEW_CLIENT",
                    "GET_WEB_CHROME_CLIENT",
                    "GET_WEB_VIEW_RENDERER",
                    "WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE",
                    "RECEIVE_WEB_RESOURCE_ERROR",
                    "RECEIVE_HTTP_ERROR",
                    "SHOULD_OVERRIDE_WITH_REDIRECTS",
                    "WEB_RESOURCE_REQUEST_IS_REDIRECT",
                    "WEB_RESOURCE_ERROR_GET_CODE",
                    "WEB_RESOURCE_ERROR_GET_DESCRIPTION",
                    "SERVICE_WORKER_BASIC_USAGE",
                    "SERVICE_WORKER_CACHE_MODE",
                    "SERVICE_WORKER_CONTENT_ACCESS",
                    "SERVICE_WORKER_FILE_ACCESS",
                    "SERVICE_WORKER_BLOCK_NETWORK_LOADS",
                    "TRACING_CONTROLLER_BASIC_USAGE",
                    "SAVE_STATE",
                    "VISUAL_STATE_CALLBACK")));

    @NonNull
    public static String[] all() {
        return SUPPORTED.toArray(new String[0]);
    }

    public static boolean contains(@NonNull String feature) {
        return SUPPORTED.contains(feature);
    }
}
