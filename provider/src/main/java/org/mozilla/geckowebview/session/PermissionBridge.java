package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

// Translates GeckoSession.PermissionDelegate into WebChromeClient-shaped
// permission callbacks. Bridge only: no state cached here.
//
// Decision flow (P1): every Host method receives a Decision that MUST be
// completed exactly once (allow or deny) — an unanswered decision leaves
// the page's permission promise pending forever. The fan-out completes
// deny on every fallback path (no chrome client, app threw, ignored
// prompt), matching Chromium where a missing WebChromeClient denies.
//
// Chromium-parity auto decisions live in autoDecision(): surfaces WebView
// has no prompt API for are decided silently instead of being shown
// through a fabricated prompt (storage/autoplay/EME = grant silently like
// Chromium WebView; notifications/XR/tracking/... = deny — no surface).
public class PermissionBridge implements GeckoSession.PermissionDelegate {
    public interface Decision {
        void allow();

        void deny();
    }

    public interface Host {
        // WebChromeClient.onGeolocationPermissionsShowPrompt path. The
        // GeolocationPermissions.Callback.invoke(origin, allow, retain)
        // routes into the decision; retain is accepted but has no Gecko
        // persistence primitive in P1 (logged).
        void onGeolocationPrompt(@NonNull String origin,
                @NonNull Decision decision);

        // No WebView prompt surface exists for these content permissions —
        // the fan-out denies loudly (never fabricates a PermissionRequest).
        void onPermissionRequest(@NonNull String origin, int geckoPermission,
                @NonNull Decision decision);

        void onAndroidPermissionsRequest(@NonNull String[] permissions,
                @NonNull GeckoSession.PermissionDelegate.Callback callback);

        void onMediaRequest(@NonNull String uri,
                @NonNull GeckoSession.PermissionDelegate.MediaSource[] video,
                @NonNull GeckoSession.PermissionDelegate.MediaSource[] audio,
                @NonNull GeckoSession.PermissionDelegate.MediaCallback callback);
    }

    private final Host mHost;

    public PermissionBridge(@NonNull Host host) {
        mHost = host;
    }

    // null = needs the app (prompt path). Otherwise the value completes
    // the GeckoResult directly. Constants are compile-time ints (JVM
    // locks reference them).
    @Nullable
    static Integer autoDecision(int geckoPermission) {
        switch (geckoPermission) {
            case GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION:
                return null;
            case GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE:
            case GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE:
            case GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE:
            case GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS:
                return GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW;
            default:
                // DESKTOP_NOTIFICATION / XR / TRACKING / STORAGE_ACCESS /
                // LOCAL_DEVICE_ACCESS / LOCAL_NETWORK_ACCESS / unknown:
                // no WebView surface, deny honestly.
                return GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY;
        }
    }

    @Override
    public void onAndroidPermissionsRequest(@NonNull GeckoSession session,
            @NonNull String[] permissions,
            @NonNull GeckoSession.PermissionDelegate.Callback callback) {
        try {
            mHost.onAndroidPermissionsRequest(permissions, callback);
        } catch (Throwable t) {
            try {
                callback.reject();
            } catch (Throwable ignored) {
            }
        }
    }

    @Nullable
    @Override
    public GeckoResult<Integer> onContentPermissionRequest(@NonNull GeckoSession session,
            @NonNull GeckoSession.PermissionDelegate.ContentPermission perm) {
        String origin = perm.uri != null ? perm.uri : "";
        Integer auto = autoDecision(perm.permission);
        if (auto != null) {
            android.util.Log.d("Sinytra/permission",
                    "auto decision perm=" + perm.permission + " -> " + auto);
            return GeckoResult.fromValue(auto);
        }
        final GeckoResult<Integer> pending = new GeckoResult<>();
        final Decision decision = new Decision() {
            @Override
            public void allow() {
                pending.complete(GeckoSession.PermissionDelegate.ContentPermission
                        .VALUE_ALLOW);
            }

            @Override
            public void deny() {
                pending.complete(GeckoSession.PermissionDelegate.ContentPermission
                        .VALUE_DENY);
            }
        };
        if (perm.permission
                == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
            try {
                mHost.onGeolocationPrompt(origin, decision);
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/permission",
                        "Host.onGeolocationPrompt threw", t);
                decision.deny();
            }
        } else {
            try {
                mHost.onPermissionRequest(origin, perm.permission, decision);
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/permission",
                        "Host.onPermissionRequest threw", t);
                decision.deny();
            }
        }
        return pending;
    }

    @Override
    public void onMediaPermissionRequest(@NonNull GeckoSession session,
            @NonNull String uri,
            @Nullable GeckoSession.PermissionDelegate.MediaSource[] video,
            @Nullable GeckoSession.PermissionDelegate.MediaSource[] audio,
            @NonNull GeckoSession.PermissionDelegate.MediaCallback callback) {
        try {
            mHost.onMediaRequest(uri,
                    video != null ? video
                            : new GeckoSession.PermissionDelegate.MediaSource[0],
                    audio != null ? audio
                            : new GeckoSession.PermissionDelegate.MediaSource[0],
                    callback);
        } catch (Throwable t) {
            try {
                callback.reject();
            } catch (Throwable ignored) {
            }
        }
    }
}
