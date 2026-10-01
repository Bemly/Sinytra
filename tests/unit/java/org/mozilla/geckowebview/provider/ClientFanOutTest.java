package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the geolocation secure-context gate (Chromium parity:
// insecure origins are denied without prompting). The deny-before-prompt
// wiring on the decision object stays device-locked (needs a live
// GeckoResult + chrome client); the page-side effect is covered by the
// CTS Geolocation suite on the switched device.
public final class ClientFanOutTest {

    @Test
    public void https_isSecure() {
        assertTrue(ClientFanOut.isSecureOriginForGeolocation("https://example.com"));
        assertTrue(ClientFanOut.isSecureOriginForGeolocation(
                "https://sub.example.com:8443/path?q=1"));
    }

    @Test
    public void loopbackHttp_isSecure() {
        assertTrue(ClientFanOut.isSecureOriginForGeolocation("http://localhost/"));
        assertTrue(ClientFanOut.isSecureOriginForGeolocation(
                "http://localhost:34567/geo.html"));
        assertTrue(ClientFanOut.isSecureOriginForGeolocation(
                "http://127.0.0.1:8000/"));
        assertTrue(ClientFanOut.isSecureOriginForGeolocation("http://[::1]/"));
    }

    @Test
    public void plainHttp_isInsecure() {
        assertFalse(ClientFanOut.isSecureOriginForGeolocation("http://example.com/"));
        assertFalse(
                ClientFanOut.isSecureOriginForGeolocation("http://example.com:80/"));
        assertFalse(ClientFanOut.isSecureOriginForGeolocation(
                "http://sub.example.com/path"));
    }

    @Test
    public void lookalikeHosts_areInsecure() {
        // Prefix/suffix tricks must not pass as loopback.
        assertFalse(ClientFanOut.isSecureOriginForGeolocation(
                "http://localhost.example.com/"));
        assertFalse(ClientFanOut.isSecureOriginForGeolocation(
                "http://example.localhost/"));
        assertFalse(ClientFanOut.isSecureOriginForGeolocation(
                "http://127.0.0.1.example.com/"));
    }

    @Test
    public void malformed_isInsecure() {
        assertFalse(ClientFanOut.isSecureOriginForGeolocation(null));
        assertFalse(ClientFanOut.isSecureOriginForGeolocation(""));
        assertFalse(ClientFanOut.isSecureOriginForGeolocation("not-a-url"));
        assertFalse(ClientFanOut.isSecureOriginForGeolocation("file:///sdcard/"));
    }

    @Test
    public void realmOf_extractsBareRealm() {
        assertEquals("Android CTS", ClientFanOut.realmOf(
                "http://localhost:41387 is requesting your username and password."
                        + " The site says: \"Android CTS\""));
    }

    @Test
    public void realmOf_fallsBackToFullMessage() {
        assertEquals("", ClientFanOut.realmOf(null));
        assertEquals("plain", ClientFanOut.realmOf("plain"));
    }
}
