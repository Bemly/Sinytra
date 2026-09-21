package org.mozilla.geckowebview.compat;

import android.net.Uri;
import android.webkit.ValueCallback;
import androidx.annotation.NonNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.chromium.support_lib_boundary.StaticsBoundaryInterface;

// StaticsBoundaryInterface over GeckoWebViewFactoryProvider.Statics:
// initSafeBrowsing reports false (Gecko ContentBlocking is runtime-level,
// no SafeBrowsing init handshake); allowlist/whitelist report false;
// privacy-policy URL empty; multiprocess true (Gecko child processes are
// always multiprocess); variations header empty.
public final class CompatStatics implements InvocationHandler {
    @NonNull
    private final android.webkit.WebViewFactoryProvider.Statics mStatics;

    private CompatStatics(
            @NonNull android.webkit.WebViewFactoryProvider.Statics statics) {
        mStatics = statics;
    }

    @NonNull
    public static InvocationHandler create(
            @NonNull android.webkit.WebViewFactoryProvider.Statics statics) {
        return new CompatStatics(statics);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        switch (name) {
            case "initSafeBrowsing":
                mStatics.initSafeBrowsing((android.content.Context) args[0],
                        (ValueCallback<Boolean>) args[1]);
                return null;
            case "setSafeBrowsingAllowlist":
                mStatics.setSafeBrowsingWhitelist(
                        new java.util.ArrayList<>((Set<String>) args[0]),
                        (ValueCallback<Boolean>) args[1]);
                return null;
            case "setSafeBrowsingWhitelist":
                mStatics.setSafeBrowsingWhitelist((List<String>) args[0],
                        (ValueCallback<Boolean>) args[1]);
                return null;
            case "getSafeBrowsingPrivacyPolicyUrl":
                return mStatics.getSafeBrowsingPrivacyPolicyUrl();
            case "isMultiProcessEnabled":
                return Boolean.TRUE;
            case "getVariationsHeader":
                return "";
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "CompatStatics";
            default:
                throw new UnsupportedOperationException(
                        "CompatStatics: " + name);
        }
    }

    @SuppressWarnings("unused")
    public static boolean isMultiProcessEnabledStatic() {
        return true;
    }

    @SuppressWarnings("unused")
    public static Uri privacyPolicyUrl(
            @NonNull android.webkit.WebViewFactoryProvider.Statics statics) {
        return statics.getSafeBrowsingPrivacyPolicyUrl();
    }
}
