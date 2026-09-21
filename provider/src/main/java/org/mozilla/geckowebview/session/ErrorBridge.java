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

    @NonNull
    public static String describe(int geckoCode) {
        return "Gecko error " + geckoCode;
    }
}
