package org.mozilla.geckowebview.provider;

import android.util.Log;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.Method;

// Opaque handle on the framework's WebView.PrivateAccess (the object the
// framework passes to createWebView). The type is a hidden inner class of
// android.webkit.WebView that android.jar does not ship, so it cannot be
// named at compile time (see framework-stubs WebViewFactoryProvider); its
// super_* methods are @SystemApi on device and are the legal channel for a
// provider to reach the View superclass implementations.
//
// Why needed (verified in the device framework.jar, 2026-09-26):
// WebView.setLayoutParams does ONLY mProvider.getViewDelegate()
// .setLayoutParams(params) — no super call. If the provider does not write
// the params through super_setLayoutParams, the WebView's mLayoutParams stays
// null and the parent's measure NPEs (PoC FrameworkEntry crash). Chromium's
// glue does the same write.
final class FrameworkPrivateAccess {
    private static final String TAG = "Sinytra/provider";

    @Nullable
    private final Object mAccess;
    @Nullable
    private volatile Method mSuperSetLayoutParams;

    FrameworkPrivateAccess(@Nullable Object access) {
        mAccess = access;
    }

    boolean isPresent() {
        return mAccess != null;
    }

    /** Writes the params into the View superclass; false if unavailable. */
    boolean superSetLayoutParams(@NonNull ViewGroup.LayoutParams params) {
        Object access = mAccess;
        if (access == null) {
            return false;
        }
        try {
            Method method = mSuperSetLayoutParams;
            if (method == null) {
                method = access.getClass().getMethod(
                        "super_setLayoutParams", ViewGroup.LayoutParams.class);
                mSuperSetLayoutParams = method;
            }
            method.invoke(access, params);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "PrivateAccess.super_setLayoutParams failed", t);
            return false;
        }
    }
}
