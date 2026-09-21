package org.mozilla.geckowebview.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.ValueCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

// Geolocation origin allow-list store. GeolocationPermissions itself is NOT
// subclassed (package-private ctor in android.jar); the factory returns the
// host framework's getInstance() for the singleton, and this store backs
// the provider-side allow/clear bookkeeping in private SharedPreferences.
public final class GeckoGeolocationStore {
    private static final String PREFS = "sinytra_geo";
    private static final String KEY_ALLOWED = "allowed";

    private final SharedPreferences mPrefs;

    public GeckoGeolocationStore(@NonNull Context context) {
        mPrefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void getOrigins(@NonNull ValueCallback<Set<String>> callback) {
        callback.onReceiveValue(new HashSet<>(
                mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>())));
    }

    public void getAllowed(@NonNull String origin,
            @NonNull ValueCallback<Boolean> callback) {
        callback.onReceiveValue(
                mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>()).contains(origin));
    }

    public void clear(@NonNull String origin) {
        Set<String> allowed = new HashSet<>(
                mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>()));
        if (allowed.remove(origin)) {
            mPrefs.edit().putStringSet(KEY_ALLOWED, allowed).apply();
        }
    }

    public void allow(String origin) {
        if (origin == null) {
            return;
        }
        Set<String> allowed = new HashSet<>(
                mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>()));
        if (allowed.add(origin)) {
            mPrefs.edit().putStringSet(KEY_ALLOWED, allowed).apply();
        }
    }

    public void clearAll() {
        mPrefs.edit().remove(KEY_ALLOWED).apply();
    }

    @NonNull
    public Set<String> snapshot() {
        return new HashSet<>(mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>()));
    }

    @Nullable
    public static android.webkit.GeolocationPermissions frameworkInstance() {
        try {
            return android.webkit.GeolocationPermissions.getInstance();
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/storage",
                    "GeolocationPermissions.getInstance threw", t);
            return null;
        }
    }
}
