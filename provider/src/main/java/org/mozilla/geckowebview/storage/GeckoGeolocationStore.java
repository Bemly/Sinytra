package org.mozilla.geckowebview.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.ValueCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

// Geolocation origin allow-list store, bound into the framework-typed
// android.webkit.SinytraGeolocationPermissions the factory hands out.
// Why self-written (AGENTS §4): GeckoView keeps no WebView-style
// "origin always allowed" list; persisted in private SharedPreferences.
public final class GeckoGeolocationStore
        implements android.webkit.SinytraGeolocationPermissions.Binding {
    private static final String PREFS = "sinytra_geo";
    private static final String KEY_ALLOWED = "allowed";

    private final SharedPreferences mPrefs;

    public GeckoGeolocationStore(@NonNull Context context) {
        mPrefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void getOrigins(@NonNull ValueCallback<Set<String>> callback) {
        callback.onReceiveValue(new HashSet<>(
                mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>())));
    }

    @Override
    public void getAllowed(@NonNull String origin,
            @NonNull ValueCallback<Boolean> callback) {
        callback.onReceiveValue(
                mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>()).contains(origin));
    }

    @Override
    public void clear(@NonNull String origin) {
        Set<String> allowed = new HashSet<>(
                mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>()));
        if (allowed.remove(origin)) {
            mPrefs.edit().putStringSet(KEY_ALLOWED, allowed).apply();
        }
    }

    @Override
    public void allow(@NonNull String origin) {
        if (origin == null) {
            return;
        }
        Set<String> allowed = new HashSet<>(
                mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>()));
        if (allowed.add(origin)) {
            mPrefs.edit().putStringSet(KEY_ALLOWED, allowed).apply();
        }
    }

    @Override
    public void clearAll() {
        mPrefs.edit().remove(KEY_ALLOWED).apply();
    }

    @NonNull
    public Set<String> snapshot() {
        return new HashSet<>(mPrefs.getStringSet(KEY_ALLOWED, new HashSet<>()));
    }
}
