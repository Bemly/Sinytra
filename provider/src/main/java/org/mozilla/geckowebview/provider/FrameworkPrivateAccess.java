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
//
// Same mechanism, layout: WebView.setFrame delegates ENTIRELY (no super call
// in the framework — unlike onMeasure/onSizeChanged/onScrollChanged, where
// WebView calls super itself). Without super_setFrame the WebView frame stays
// (0,0,0,0); the GeckoView child still measures/lays out (AbsoluteLayout uses
// measured sizes) but its surface positions against the broken parent chain —
// blank screen behind the opaque window (framework-entry probe, 2026-09-26).
// Chromium's glue forwards super_setFrame likewise. requestFocus /
// dispatchKeyEvent / hover / generic motion / performLongClick are the same
// delegate-without-super set; forwarded for Chromium parity.
final class FrameworkPrivateAccess {
    private static final String TAG = "Sinytra/provider";

    @Nullable
    private final Object mAccess;
    private final java.util.HashMap<String, Method> mMethods = new java.util.HashMap<>();

    FrameworkPrivateAccess(@Nullable Object access) {
        mAccess = access;
    }

    boolean isPresent() {
        return mAccess != null;
    }

    @NonNull
    private Method method(@NonNull String name, Class<?>... types)
            throws NoSuchMethodException {
        Method cached = mMethods.get(name);
        if (cached == null) {
            cached = mAccess.getClass().getMethod(name, types);
            mMethods.put(name, cached);
        }
        return cached;
    }

    /** Writes the params into the View superclass; false if unavailable. */
    boolean superSetLayoutParams(@NonNull ViewGroup.LayoutParams params) {
        return invoke("super_setLayoutParams",
                new Class<?>[] {ViewGroup.LayoutParams.class}, new Object[] {params});
    }

    /** Writes the frame into the View superclass; false if unavailable. */
    boolean superSetFrame(int left, int top, int right, int bottom) {
        return invoke("super_setFrame",
                new Class<?>[] {int.class, int.class, int.class, int.class},
                new Object[] {left, top, right, bottom});
    }

    /** Focus through the View superclass (descendant search); false if unavailable. */
    boolean superRequestFocus(int direction, @Nullable android.graphics.Rect previouslyFocused) {
        return invoke("super_requestFocus",
                new Class<?>[] {int.class, android.graphics.Rect.class},
                new Object[] {direction, previouslyFocused});
    }

    boolean superDispatchKeyEvent(@NonNull android.view.KeyEvent event) {
        return invoke("super_dispatchKeyEvent",
                new Class<?>[] {android.view.KeyEvent.class}, new Object[] {event});
    }

    boolean superOnGenericMotionEvent(@NonNull android.view.MotionEvent event) {
        return invoke("super_onGenericMotionEvent",
                new Class<?>[] {android.view.MotionEvent.class}, new Object[] {event});
    }

    boolean superOnHoverEvent(@NonNull android.view.MotionEvent event) {
        return invoke("super_onHoverEvent",
                new Class<?>[] {android.view.MotionEvent.class}, new Object[] {event});
    }

    boolean superPerformLongClick() {
        return invoke("super_performLongClick", new Class<?>[0], new Object[0]);
    }

    private boolean invoke(@NonNull String name, @NonNull Class<?>[] types,
            @NonNull Object[] args) {
        Object access = mAccess;
        if (access == null) {
            return false;
        }
        try {
            return (Boolean) method(name, types).invoke(access, args);
        } catch (Throwable t) {
            Log.w(TAG, "PrivateAccess." + name + " failed", t);
            return false;
        }
    }
}
