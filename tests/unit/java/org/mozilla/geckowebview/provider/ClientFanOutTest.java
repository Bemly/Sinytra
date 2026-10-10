package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.DownloadListener;
import org.junit.Test;

// Unit locks for the geolocation secure-context gate (Chromium parity:
// insecure origins are denied without prompting). The deny-before-prompt
// wiring on the decision object stays device-locked (needs a live
// GeckoResult + chrome client); the page-side effect is covered by the
// CTS Geolocation suite on the switched device.
public final class ClientFanOutTest {

    @Test
    public void https_isSecure() {
        assertTrue(ClientPrompts.isSecureOriginForGeolocation("https://example.com"));
        assertTrue(ClientPrompts.isSecureOriginForGeolocation(
                "https://sub.example.com:8443/path?q=1"));
    }

    @Test
    public void loopbackHttp_isSecure() {
        assertTrue(ClientPrompts.isSecureOriginForGeolocation("http://localhost/"));
        assertTrue(ClientPrompts.isSecureOriginForGeolocation(
                "http://localhost:34567/geo.html"));
        assertTrue(ClientPrompts.isSecureOriginForGeolocation(
                "http://127.0.0.1:8000/"));
        assertTrue(ClientPrompts.isSecureOriginForGeolocation("http://[::1]/"));
    }

    @Test
    public void plainHttp_isInsecure() {
        assertFalse(ClientPrompts.isSecureOriginForGeolocation("http://example.com/"));
        assertFalse(
                ClientPrompts.isSecureOriginForGeolocation("http://example.com:80/"));
        assertFalse(ClientPrompts.isSecureOriginForGeolocation(
                "http://sub.example.com/path"));
    }

    @Test
    public void lookalikeHosts_areInsecure() {
        // Prefix/suffix tricks must not pass as loopback.
        assertFalse(ClientPrompts.isSecureOriginForGeolocation(
                "http://localhost.example.com/"));
        assertFalse(ClientPrompts.isSecureOriginForGeolocation(
                "http://example.localhost/"));
        assertFalse(ClientPrompts.isSecureOriginForGeolocation(
                "http://127.0.0.1.example.com/"));
    }

    @Test
    public void malformed_isInsecure() {
        assertFalse(ClientPrompts.isSecureOriginForGeolocation(null));
        assertFalse(ClientPrompts.isSecureOriginForGeolocation(""));
        assertFalse(ClientPrompts.isSecureOriginForGeolocation("not-a-url"));
        assertFalse(ClientPrompts.isSecureOriginForGeolocation("file:///sdcard/"));
    }

    @Test
    public void realmOf_extractsBareRealm() {
        assertEquals("Android CTS", ClientPrompts.realmOf(
                "http://localhost:41387 is requesting your username and password."
                        + " The site says: \"Android CTS\""));
    }

    @Test
    public void realmOf_extractsCurlyQuotedRealm() {
        assertEquals("Android CTS", ClientPrompts.realmOf(
                "http://localhost:41387 is requesting your username and password."
                        + " The site says: \u201CAndroid CTS\u201D"));
    }

    @Test
    public void realmOf_fallsBackToFullMessage() {
        assertEquals("", ClientPrompts.realmOf(null));
        assertEquals("plain", ClientPrompts.realmOf("plain"));
    }

    @Test
    public void terminalProgress_firesOnSuccessBelow100() {
        assertTrue(ClientFanOut.shouldFireTerminalProgress(true, 0));
        assertTrue(ClientFanOut.shouldFireTerminalProgress(true, 15));
        assertTrue(ClientFanOut.shouldFireTerminalProgress(true, 99));
    }

    @Test
    public void terminalProgress_skippedAt100() {
        assertFalse(ClientFanOut.shouldFireTerminalProgress(true, 100));
    }

    @Test
    public void terminalProgress_skippedOnFailure() {
        assertFalse(ClientFanOut.shouldFireTerminalProgress(false, 0));
        assertFalse(ClientFanOut.shouldFireTerminalProgress(false, 50));
    }

    // shouldOverride consult locks (API-24+ contract): the Request overload
    // — never the legacy string overload directly. A legacy take-over impl
    // returning true for every https URL must NOT fire when the app's
    // Request overload says false (device-verified 2026-10-10 black page).

    private static final class RecordingClient extends WebViewClient {
        WebResourceRequest lastRequest;
        boolean requestAnswer;
        boolean stringAnswer;
        boolean throwOnRequest;

        @Override
        public boolean shouldOverrideUrlLoading(WebView view,
                WebResourceRequest request) {
            lastRequest = request;
            if (throwOnRequest) {
                throw new RuntimeException("probe");
            }
            return requestAnswer;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return stringAnswer;
        }
    }

    private static final class StubOwner implements ClientFanOut.Owner {
        final RecordingClient client = new RecordingClient();

        @Override
        public WebView webView() {
            return null;
        }

        @Override
        public GeckoWebViewFactoryProvider factory() {
            return null;
        }

        @Override
        public org.mozilla.geckowebview.session.GeckoSessionBridge
                ownerBridge() {
            return null;
        }

        @Override
        public WebViewClient webViewClient() {
            return client;
        }

        @Override
        public WebChromeClient webChromeClient() {
            return null;
        }

        @Override
        public DownloadListener downloadListener() {
            return null;
        }

        @Override
        public void setFileChooserCallback(ValueCallback<Uri[]> callback) {
        }

        @Override
        public ValueCallback<Uri[]> fileChooserCallback() {
            return null;
        }

        @Override
        public void fireVisualState() {
        }

        @Override
        public String[] interceptFilters() {
            return new String[0];
        }

        @Override
        public org.mozilla.geckowebview.settings.GeckoWebSettings
                webSettingsState() {
            return null;
        }

        @Override
        public void repushResponseFilters() {
        }

        @Override
        public void fetchFavicon() {
        }

        @Override
        public String[] effectiveInterceptFilters() {
            return new String[0];
        }

        @Override
        public org.mozilla.geckowebview.session.RenderProcessBridge
                renderProcess() {
            return null;
        }
    }

    private static WebResourceRequest facade() {
        return facade(false);
    }

    private static WebResourceRequest facade(final boolean redirect) {
        // Minimal WebResourceRequest (SinytraResourceRequest's ctor is
        // session-package-visible; the fan-out only reads the interface).
        return new WebResourceRequest() {
            @Override
            public android.net.Uri getUrl() {
                return null;
            }

            @Override
            public boolean isForMainFrame() {
                return true;
            }

            @Override
            public boolean isRedirect() {
                return redirect;
            }

            @Override
            public boolean hasGesture() {
                return false;
            }

            @Override
            public String getMethod() {
                return "GET";
            }

            @Override
            public java.util.Map<String, String> getRequestHeaders() {
                return java.util.Collections.emptyMap();
            }
        };
    }

    @Test
    public void overrideRequest_trueWinsOverLegacyStringFalse() {
        StubOwner owner = new StubOwner();
        owner.client.requestAnswer = true;
        owner.client.stringAnswer = false;
        ClientFanOut fanOut = new ClientFanOut(owner);
        assertTrue(fanOut.shouldOverrideRequest(facade()));
        assertTrue(owner.client.lastRequest.isForMainFrame());
        assertFalse(owner.client.lastRequest.isRedirect());
    }

    @Test
    public void overrideRequest_falseWinsOverLegacyStringTrue() {
        // The regression lock: a legacy take-over string impl returning
        // true must NOT deny when the Request overload says false.
        StubOwner owner = new StubOwner();
        owner.client.requestAnswer = false;
        owner.client.stringAnswer = true;
        ClientFanOut fanOut = new ClientFanOut(owner);
        assertFalse(fanOut.shouldOverrideRequest(facade()));
    }

    @Test
    public void overrideRequest_throwingDegradesToAllow() {
        StubOwner owner = new StubOwner();
        owner.client.throwOnRequest = true;
        ClientFanOut fanOut = new ClientFanOut(owner);
        assertFalse(fanOut.shouldOverrideRequest(facade()));
    }

    @Test
    public void overrideRequest_redirectFlagReachesApp() {
        StubOwner owner = new StubOwner();
        ClientFanOut fanOut = new ClientFanOut(owner);
        fanOut.shouldOverrideRequest(facade(true));
        assertNotNull(owner.client.lastRequest);
        assertTrue(owner.client.lastRequest.isRedirect());
    }
}
