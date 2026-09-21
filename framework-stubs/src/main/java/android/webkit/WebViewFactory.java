// Compile-only stub for AOSP android14-release WebViewFactory.
// Only the members our provider touches: StartupTimestamps (read via
// WebViewDelegate.getStartupTimestamps) — the rest lives in framework.
// Method set verified against device framework.jar dex dump.
// See WebViewFactoryProvider.java header for stub policy.
package android.webkit;

public final class WebViewFactory {
    public static final class StartupTimestamps {
        StartupTimestamps() {}

        public long getWebViewLoadStart() {
            throw new RuntimeException("Stub!");
        }

        public long getCreateContextStart() {
            throw new RuntimeException("Stub!");
        }

        public long getCreateContextEnd() {
            throw new RuntimeException("Stub!");
        }

        public long getAddAssetsStart() {
            throw new RuntimeException("Stub!");
        }

        public long getAddAssetsEnd() {
            throw new RuntimeException("Stub!");
        }

        public long getGetClassLoaderStart() {
            throw new RuntimeException("Stub!");
        }

        public long getGetClassLoaderEnd() {
            throw new RuntimeException("Stub!");
        }

        public long getNativeLoadStart() {
            throw new RuntimeException("Stub!");
        }

        public long getNativeLoadEnd() {
            throw new RuntimeException("Stub!");
        }

        public long getProviderClassForNameStart() {
            throw new RuntimeException("Stub!");
        }

        public long getProviderClassForNameEnd() {
            throw new RuntimeException("Stub!");
        }
    }

    private WebViewFactory() {}
}
