package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import org.mozilla.geckoview.WebRequestError;

// Translates Gecko WebRequestError codes into android.webkit.WebViewClient
// ERROR_* constants. Pure function: no GeckoView calls, no state.
public final class ErrorBridge {
    private ErrorBridge() {}

    public static int toWebViewErrorCode(int geckoCode) {
        switch (geckoCode) {
            case WebRequestError.ERROR_SECURITY_SSL:
            case WebRequestError.ERROR_SECURITY_BAD_CERT:
            case WebRequestError.ERROR_BAD_HSTS_CERT:
                return -11;
            case WebRequestError.ERROR_NET_TIMEOUT:
                return -8;
            case WebRequestError.ERROR_CONNECTION_REFUSED:
            case WebRequestError.ERROR_PROXY_CONNECTION_REFUSED:
                return -6;
            case WebRequestError.ERROR_UNKNOWN_HOST:
            case WebRequestError.ERROR_UNKNOWN_PROXY_HOST:
                return -2;
            case WebRequestError.ERROR_NET_INTERRUPT:
            case WebRequestError.ERROR_NET_RESET:
            case WebRequestError.ERROR_OFFLINE:
                return -2;
            case WebRequestError.ERROR_REDIRECT_LOOP:
                return -9;
            case WebRequestError.ERROR_UNKNOWN_PROTOCOL:
                return -10;
            case WebRequestError.ERROR_MALFORMED_URI:
                return -12;
            case WebRequestError.ERROR_FILE_NOT_FOUND:
                return -14;
            case WebRequestError.ERROR_FILE_ACCESS_DENIED:
                return -13;
            case WebRequestError.ERROR_UNSAFE_CONTENT_TYPE:
                return -16;
            case WebRequestError.ERROR_PORT_BLOCKED:
                return -1;
            default:
                return -1;
        }
    }

    public static boolean isSslError(int geckoCode) {
        return geckoCode == WebRequestError.ERROR_SECURITY_SSL
                || geckoCode == WebRequestError.ERROR_SECURITY_BAD_CERT
                || geckoCode == WebRequestError.ERROR_BAD_HSTS_CERT;
    }

    // Maps a Gecko SSL error onto the closest SslError primary error for
    // WebViewClient.onReceivedSslError. -1 when the gecko error is not an
    // SSL failure. Constants are compile-time ints, so locks can reference
    // them on the JVM. Gecko's onLoadError carries no certificate (P1):
    // the SslError is built with a null certificate — getCertificate()
    // honestly returns null instead of a fabricated chain. All three Gecko
    // SSL codes map to SSL_UNTRUSTED: without cert details the trust
    // failure is the only defensible primary.
    public static int toSslPrimaryError(int geckoCode) {
        if (!isSslError(geckoCode)) {
            return -1;
        }
        return android.net.http.SslError.SSL_UNTRUSTED;
    }

    @NonNull
    public static String describe(int geckoCode) {
        return "Gecko error " + geckoCode;
    }
}
