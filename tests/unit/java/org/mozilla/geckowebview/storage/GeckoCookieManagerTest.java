package org.mozilla.geckowebview.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.webkit.ValueCallback;
import androidx.annotation.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.mozilla.geckoview.ContentBlocking;

// JVM locks for the cookie facade policy table and honest-degradation
// guards. The jar round-trip itself needs a live Gecko (GeckoResult class
// init dies on the JVM — same constraint as the deny-value path) and is
// locked by the device harness cookieJar probe instead.
public class GeckoCookieManagerTest {

    // --- policy table: engine reality always matches what the facade
    // reports (third-party=false unless the app opted in, WebView default
    // for apps targeting L+; our targetSdk=34). ---

    @Test
    public void behavior_acceptFalse_mapsToRejectAll() {
        assertEquals(ContentBlocking.CookieBehavior.ACCEPT_NONE,
                GeckoCookieManager.cookieBehaviorFor(false, Boolean.TRUE));
        assertEquals(ContentBlocking.CookieBehavior.ACCEPT_NONE,
                GeckoCookieManager.cookieBehaviorFor(false, Boolean.FALSE));
        assertEquals(ContentBlocking.CookieBehavior.ACCEPT_NONE,
                GeckoCookieManager.cookieBehaviorFor(false, null));
    }

    @Test
    public void behavior_thirdPartyOptIn_mapsToAcceptAll() {
        assertEquals(ContentBlocking.CookieBehavior.ACCEPT_ALL,
                GeckoCookieManager.cookieBehaviorFor(true, Boolean.TRUE));
    }

    @Test
    public void behavior_thirdPartyUntouchedOrOptOut_mapsToRejectForeign() {
        assertEquals(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY,
                GeckoCookieManager.cookieBehaviorFor(true, null));
        assertEquals(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY,
                GeckoCookieManager.cookieBehaviorFor(true, Boolean.FALSE));
    }

    // --- facade flag round-trip on a runtime-less manager (no jar access on
    // the JVM; these only exercise the process-local reporting side). ---

    @Test
    public void acceptCookie_flagRoundTrip() {
        GeckoCookieManager manager = new GeckoCookieManager(null);
        assertTrue(manager.acceptCookie());
        manager.setAcceptCookie(false);
        assertFalse(manager.acceptCookie());
    }

    @Test
    public void thirdParty_defaultFalse_optInRoundTrip() {
        GeckoCookieManager manager = new GeckoCookieManager(null);
        // WebView targetSdk>=21 default: third-party rejected.
        assertFalse(manager.acceptThirdPartyCookies(null));
        manager.setAcceptThirdPartyCookies(null, true);
        assertTrue(manager.acceptThirdPartyCookies(null));
        manager.setAcceptThirdPartyCookies(null, false);
        assertFalse(manager.acceptThirdPartyCookies(null));
    }

    // --- file-scheme gate (Chromium parity: accepted by default; shut gate
    // makes file: URLs behave like an empty jar). The framework reaches
    // these through the hidden *Impl methods (device framework.jar); a
    // missing override was the WEBVIEW TEST AbstractMethodError. ---

    @Test
    public void fileScheme_defaultAccepted_flagRoundTrip() {
        GeckoCookieManager manager = new GeckoCookieManager(null);
        assertTrue(manager.allowFileSchemeCookies());
        manager.setAcceptFileSchemeCookies(false);
        assertFalse(manager.allowFileSchemeCookies());
        manager.setAcceptFileSchemeCookies(true);
        assertTrue(manager.allowFileSchemeCookies());
    }

    @Test
    public void isFileScheme_matchesCaseInsensitively_nullSafe() {
        assertTrue(GeckoCookieManager.isFileScheme("file:///android_asset/x.html"));
        assertTrue(GeckoCookieManager.isFileScheme("FILE:///x"));
        assertFalse(GeckoCookieManager.isFileScheme("https://example.com/"));
        assertFalse(GeckoCookieManager.isFileScheme(null));
    }

    @Test
    public void fileScheme_blocked_getReturnsEmpty() {
        GeckoCookieManager manager = new GeckoCookieManager(null);
        manager.setAcceptFileSchemeCookies(false);
        assertEquals("", manager.getCookie("file:///android_asset/x.html"));
    }

    @Test
    public void fileScheme_blocked_setCallbackReceivesFalse() {
        final AtomicReference<Boolean> value = new AtomicReference<>();
        ValueCallback<Boolean> callback = new ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(@Nullable Boolean result) {
                value.set(result);
            }
        };
        GeckoCookieManager manager = new GeckoCookieManager(null);
        manager.setAcceptFileSchemeCookies(false);
        manager.setCookie("file:///x.html", "a=1", callback);
        assertEquals(Boolean.FALSE, value.get());
    }

    @Test
    public void impl_hiddenFileSchemeMethods_roundTrip() {
        GeckoCookieManager manager = new GeckoCookieManager(null);
        GeckoCookieManagerImpl impl = new GeckoCookieManagerImpl(manager);
        impl.setAcceptFileSchemeCookiesImpl(false);
        assertFalse(impl.allowFileSchemeCookiesImpl());
    }

    // --- null-arg guards must degrade honestly before any runtime
    // resolution (GeckoRuntime is not touchable on the JVM). ---

    @Test
    public void getCookie_nullUrl_returnsEmpty() {
        assertEquals("", new GeckoCookieManager(null).getCookie(null));
    }

    @Test
    public void setCookie_nullUrlOrValue_callbackReceivesFalse() {        final AtomicBoolean got = new AtomicBoolean(true);
        final AtomicReference<Boolean> value = new AtomicReference<>();
        ValueCallback<Boolean> callback = new ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(@Nullable Boolean result) {
                got.set(true);
                value.set(result);
            }
        };
        GeckoCookieManager manager = new GeckoCookieManager(null);
        manager.setCookie(null, "a=1", callback);
        assertTrue(got.get());
        assertEquals(Boolean.FALSE, value.get());
        got.set(false);
        manager.setCookie("https://example.com/", null, callback);
        assertTrue(got.get());
        assertEquals(Boolean.FALSE, value.get());
    }

    // --- runtime-less void setCookie must return silently (no controller
    // on the JVM, never touches a Looper). The main-thread fire-and-forget
    // branch and the cross-op completion chain need a live GeckoResult +
    // main looper, so they are device-harness covered (cookieJar probe +
    // third-party host runs), same as the jar round-trip itself. ---
    @Test
    public void setCookie_validArgsNoController_returnsSilently() {
        GeckoCookieManager manager = new GeckoCookieManager(null);
        manager.setCookie("https://example.com/", "a=1");
        manager.setCookie("file:///x.html", "a=1");
    }
}
