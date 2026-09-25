package android.webkit;

import androidx.annotation.NonNull;
import java.util.Set;

// Sinytra: the provider-owned GeolocationPermissions singleton.
//
// Same reasoning as SinytraWebStorage: GeolocationPermissions.getInstance()
// recurses into our own factory once Sinytra is the system provider, so the
// factory must own the object. android.jar hides the ctor (package-private
// stub), the device framework's GeolocationPermissions() is PUBLIC
// (@SystemApi "Only for use by WebViewProvider implementations"; dexdump of
// /system/framework/framework.jar, 2026-09-26). Package placement satisfies
// javac; the real public ctor satisfies the runtime.
//
// No state here: calls go through the injected Binding (storage/
// GeckoGeolocationStore, persisted allow-list); no org.mozilla dependency.
public final class SinytraGeolocationPermissions extends GeolocationPermissions {
    /** Provider-side implementation (GeckoGeolocationStore). */
    public interface Binding {
        void getOrigins(@NonNull ValueCallback<Set<String>> callback);

        void getAllowed(@NonNull String origin, @NonNull ValueCallback<Boolean> callback);

        void clear(@NonNull String origin);

        void allow(@NonNull String origin);

        void clearAll();
    }

    @NonNull
    private final Binding mBinding;

    public SinytraGeolocationPermissions(@NonNull Binding binding) {
        mBinding = binding;
    }

    @Override
    public void getOrigins(ValueCallback<Set<String>> callback) {
        if (callback != null) {
            mBinding.getOrigins(callback);
        }
    }

    @Override
    public void getAllowed(String origin, ValueCallback<Boolean> callback) {
        if (callback == null) {
            return;
        }
        if (origin == null) {
            callback.onReceiveValue(Boolean.FALSE);
            return;
        }
        mBinding.getAllowed(origin, callback);
    }

    @Override
    public void clear(String origin) {
        if (origin != null) {
            mBinding.clear(origin);
        }
    }

    @Override
    public void allow(String origin) {
        if (origin != null) {
            mBinding.allow(origin);
        }
    }

    @Override
    public void clearAll() {
        mBinding.clearAll();
    }
}
