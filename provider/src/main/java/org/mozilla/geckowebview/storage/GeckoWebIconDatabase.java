package org.mozilla.geckowebview.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.webkit.WebIconDatabase;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// android.webkit.WebIconDatabase implementation. GeckoView exposes no icon
// cache (API_MAPPING.md §7), so this is the allowed self-owned store:
// in-memory LRU + SharedPreferences URL→marker, icons themselves are not
// fetched here (requestIconForPageUrl reports null honestly).
public final class GeckoWebIconDatabase extends WebIconDatabase {
    private static final String PREFS = "sinytra_icons";
    private static final int MAX_MEMORY_ENTRIES = 64;

    private final SharedPreferences mPrefs;
    private final java.util.LinkedHashMap<String, Bitmap> mMemory =
            new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_MEMORY_ENTRIES;
                }
            };

    public GeckoWebIconDatabase(@NonNull Context context) {
        mPrefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void open(String path) {
    }

    @Override
    public void close() {
    }

    @Override
    public void removeAllIcons() {
        synchronized (mMemory) {
            mMemory.clear();
        }
        mPrefs.edit().clear().apply();
    }

    @Override
    public void requestIconForPageUrl(String url, IconListener listener) {
        if (listener == null) {
            return;
        }
        Bitmap icon;
        synchronized (mMemory) {
            icon = mMemory.get(url);
        }
        listener.onReceivedIcon(url, icon);
    }

    @Override
    public void retainIconForPageUrl(String url) {
    }

    @Override
    public void releaseIconForPageUrl(String url) {
    }

    public void putIcon(@NonNull String url, @Nullable Bitmap icon) {
        synchronized (mMemory) {
            if (icon == null) {
                mMemory.remove(url);
            } else {
                mMemory.put(url, icon);
            }
        }
        mPrefs.edit().putBoolean(url, icon != null).apply();
    }
}
