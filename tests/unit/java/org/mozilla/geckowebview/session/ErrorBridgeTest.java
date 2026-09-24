package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.webkit.WebViewClient;
import org.junit.Test;
import org.mozilla.geckoview.WebRequestError;

// Unit locks for the Gecko → WebView error-code translation. Assertions
// use the named WebViewClient constants (not raw numbers) so a wrong
// production mapping fails here instead of shipping as a magic number.
// The bad-host path is additionally device-verified (harness PASS
// loadError code=-2).
public final class ErrorBridgeTest {

    @Test
    public void sslFamily_mapsToFailedSslHandshake() {
        assertEquals(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_SECURITY_SSL));
        assertEquals(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_SECURITY_BAD_CERT));
        assertEquals(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_BAD_HSTS_CERT));
        assertTrue(ErrorBridge.isSslError(WebRequestError.ERROR_SECURITY_SSL));
        assertTrue(ErrorBridge.isSslError(
                WebRequestError.ERROR_SECURITY_BAD_CERT));
        assertTrue(ErrorBridge.isSslError(
                WebRequestError.ERROR_BAD_HSTS_CERT));
    }

    @Test
    public void networkFamily_mapsToHostLookupAndConnect() {
        assertEquals(WebViewClient.ERROR_TIMEOUT,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_NET_TIMEOUT));
        assertEquals(WebViewClient.ERROR_CONNECT,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_CONNECTION_REFUSED));
        assertEquals(WebViewClient.ERROR_CONNECT,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_PROXY_CONNECTION_REFUSED));
        assertEquals(WebViewClient.ERROR_HOST_LOOKUP,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_UNKNOWN_HOST));
        assertEquals(WebViewClient.ERROR_HOST_LOOKUP,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_UNKNOWN_PROXY_HOST));
        assertEquals(WebViewClient.ERROR_HOST_LOOKUP,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_NET_INTERRUPT));
        assertEquals(WebViewClient.ERROR_HOST_LOOKUP,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_NET_RESET));
        assertEquals(WebViewClient.ERROR_HOST_LOOKUP,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_OFFLINE));
    }

    @Test
    public void protocolAndFileFamily_mapsToNamedConstants() {
        assertEquals(WebViewClient.ERROR_REDIRECT_LOOP,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_REDIRECT_LOOP));
        assertEquals(WebViewClient.ERROR_UNSUPPORTED_SCHEME,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_UNKNOWN_PROTOCOL));
        assertEquals(WebViewClient.ERROR_BAD_URL,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_MALFORMED_URI));
        assertEquals(WebViewClient.ERROR_FILE_NOT_FOUND,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_FILE_NOT_FOUND));
        assertEquals(WebViewClient.ERROR_FILE,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_FILE_ACCESS_DENIED));
        assertEquals(WebViewClient.ERROR_UNSAFE_RESOURCE,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_UNSAFE_CONTENT_TYPE));
    }

    @Test
    public void unmappedAndUnknown_mapsToErrorUnknown() {
        assertEquals(WebViewClient.ERROR_UNKNOWN,
                ErrorBridge.toWebViewErrorCode(
                        WebRequestError.ERROR_PORT_BLOCKED));
        assertEquals(WebViewClient.ERROR_UNKNOWN,
                ErrorBridge.toWebViewErrorCode(9999));
    }

    @Test
    public void isSslError_isFalseForEverythingElse() {
        assertFalse(ErrorBridge.isSslError(WebRequestError.ERROR_NET_TIMEOUT));
        assertFalse(ErrorBridge.isSslError(WebRequestError.ERROR_UNKNOWN_HOST));
        assertFalse(ErrorBridge.isSslError(9999));
    }

    // P1 SSL: every Gecko SSL code surfaces onReceivedSslError with the
    // closest SslError primary (trust failure — Gecko gives no cert
    // details); non-SSL codes carry -1 so the fan-out keeps the plain
    // onReceivedError path.
    @Test
    public void sslPrimary_mapsTrustFailureAndGuardsNonSsl() {
        assertEquals(android.net.http.SslError.SSL_UNTRUSTED,
                ErrorBridge.toSslPrimaryError(
                        WebRequestError.ERROR_SECURITY_SSL));
        assertEquals(android.net.http.SslError.SSL_UNTRUSTED,
                ErrorBridge.toSslPrimaryError(
                        WebRequestError.ERROR_SECURITY_BAD_CERT));
        assertEquals(android.net.http.SslError.SSL_UNTRUSTED,
                ErrorBridge.toSslPrimaryError(
                        WebRequestError.ERROR_BAD_HSTS_CERT));
        assertEquals(-1, ErrorBridge.toSslPrimaryError(
                WebRequestError.ERROR_NET_TIMEOUT));
        assertEquals(-1, ErrorBridge.toSslPrimaryError(9999));
    }
}
