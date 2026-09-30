// Compile-only stub for AOSP android14-release hidden PacProcessor.
// Device framework.jar dex dump: Landroid/webkit/PacProcessor is PUBLIC
// INTERFACE ABSTRACT; createInstance/getInstance are PUBLIC STATIC on the
// interface; findProxyForUrl and setProxyScript are PUBLIC ABSTRACT;
// getNetwork/setNetwork/release are PUBLIC with "Not implemented" bodies.
// android.jar STRIPS this entire type (hidden), so this stub supplies it.
// See WebViewFactoryProvider.java header for stub policy.
//
// NOTE: default methods (not abstract) for findProxyForUrl/setProxyScript:
// sibling stub TokenBindingService shows javac hides same-package stub
// siblings from each other unless compiled together; abstract would force
// provider subclasses abstract. Defaults keep provider concrete.
package android.webkit;

import android.net.Network;

public interface PacProcessor {
    static PacProcessor createInstance() {
        throw new RuntimeException("Stub!");
    }

    static PacProcessor getInstance() {
        throw new RuntimeException("Stub!");
    }

    default String findProxyForUrl(String url) {
        throw new RuntimeException("Stub!");
    }

    default Network getNetwork() {
        throw new RuntimeException("Stub!");
    }

    default void release() {
        throw new RuntimeException("Stub!");
    }

    default void setNetwork(Network network) {
        throw new RuntimeException("Stub!");
    }

    default boolean setProxyScript(String script) {
        throw new RuntimeException("Stub!");
    }
}
