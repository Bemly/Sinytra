package org.mozilla.geckowebview.storage;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.webkit.WebViewDatabase;

// android.webkit.WebViewDatabase implementation. Form/password data has no
// GeckoView equivalent (API_MAPPING.md §7), so this self-owned store keeps
// HTTP-auth credentials (the only part the provider backend reads) in
// private SharedPreferences; form data reports honest-empty.
public final class GeckoWebViewDatabaseImpl extends WebViewDatabase {
    private static final String PREFS = "sinytra_webdb";

    private final SharedPreferences mPrefs;

    public GeckoWebViewDatabaseImpl(@NonNull Context context) {
        mPrefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public boolean hasUsernamePassword() {
        return false;
    }

    @Override
    public void clearUsernamePassword() {
    }

    @Override
    public boolean hasHttpAuthUsernamePassword() {
        return !mPrefs.getAll().isEmpty();
    }

    @Override
    public void clearHttpAuthUsernamePassword() {
        mPrefs.edit().clear().apply();
    }

    @Override
    public void setHttpAuthUsernamePassword(String host, String realm,
            String username, String password) {
        if (host == null || realm == null) {
            return;
        }
        mPrefs.edit().putString(key(host, realm, "u"), username)
                .putString(key(host, realm, "p"), password).apply();
    }

    @Override
    @Nullable
    public String[] getHttpAuthUsernamePassword(String host, String realm) {
        if (host == null || realm == null) {
            return null;
        }
        String username = mPrefs.getString(key(host, realm, "u"), null);
        String password = mPrefs.getString(key(host, realm, "p"), null);
        if (username == null && password == null) {
            return null;
        }
        return new String[] {username, password};
    }

    @Override
    public boolean hasFormData() {
        return false;
    }

    @Override
    public void clearFormData() {
    }

    private static String key(String host, String realm, String field) {
        return host + "\n" + realm + "\n" + field;
    }
}
