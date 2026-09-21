package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

// Translates GeckoSession.PermissionDelegate into WebChromeClient-shaped
// permission callbacks. Bridge only: no state cached here.
// NOTE: nested names (Callback, ContentPermission, MediaSource,
// MediaCallback, PERMISSION_GEOLOCATION) resolve through the implemented
// GeckoSession.PermissionDelegate interface.
public class PermissionBridge implements GeckoSession.PermissionDelegate {
    public interface Host {
        void onGeolocationPrompt(@NonNull String origin);
        void onPermissionRequest(@NonNull String origin, int geckoPermission);
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
        try {
            String origin = perm.uri != null ? perm.uri : "";
            if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                mHost.onGeolocationPrompt(origin);
            } else {
                mHost.onPermissionRequest(origin, perm.permission);
            }
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/permission",
                    "Host.onContentPermissionRequest threw", t);
        }
        return null;
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
