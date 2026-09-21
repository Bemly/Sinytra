// Entry point androidx.webkit looks for via reflection:
//   Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil",
//                 false, getWebViewClassLoader())
//     .getDeclaredMethod("createWebViewProviderFactory").invoke(null)
// must return an InvocationHandler for WebViewProviderFactoryBoundaryInterface.
// This is Sinytra's own glue (P2-8, ROADMAP): same package+class+method name
// as Chromium's, implemented over Gecko. Never depend on Chromium classes.
package org.chromium.support_lib_glue;

import androidx.annotation.NonNull;
import java.lang.reflect.InvocationHandler;
import org.mozilla.geckowebview.compat.CompatWebViewFactory;
import org.mozilla.geckowebview.provider.GeckoWebViewFactoryProvider;

public final class SupportLibReflectionUtil {
    private static volatile GeckoWebViewFactoryProvider sFactory;

    private SupportLibReflectionUtil() {}

    public static InvocationHandler createWebViewProviderFactory() {
        GeckoWebViewFactoryProvider factory = sFactory;
        if (factory == null) {
            synchronized (SupportLibReflectionUtil.class) {
                factory = sFactory;
                if (factory == null) {
                    factory = new GeckoWebViewFactoryProvider();
                    sFactory = factory;
                }
            }
        }
        return CompatWebViewFactory.create(factory);
    }

    public static void setFactoryForTests(@NonNull GeckoWebViewFactoryProvider factory) {
        sFactory = factory;
    }
}
