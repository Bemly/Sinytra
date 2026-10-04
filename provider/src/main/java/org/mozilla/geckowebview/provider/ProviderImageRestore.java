package org.mozilla.geckowebview.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckowebview.session.JsEvaluator;

// In-place image restore for image-policy loosening (CTS WebSettingsTest
// image families poll for the re-render without reloading).
//
// Why here and not a navigation: provider-initiated navigations violate the
// loader's exclusivity contract (CTS WebViewSyncLoader fails any PageStart
// it did not ask for), so loosening must re-render with zero navigation.
// Why eval and not only the Gecko child path: the child restore rides
// SettingsUpdate, which only fires on bundle change — a loosening via
// blockNetworkImage (allowImages unchanged) never dispatches, so the child
// never hears it. The provider snippet is evidence-gated in-page (no
// backups and no blank images = no-op) and idempotent with the child path
// (both move backup->src; second run finds nothing to do).
//
// Documented gap: pages with JavaScript disabled cannot run the page-shim
// transport (extension content script needs script execution); the child
// docshell path remains their only gate. No CTS coverage.
final class ProviderImageRestore {
    // Restores sanitized images (data-sinytra-src back to src) and refetches
    // blank-failed images with intact sources; returns the acted-on count
    // (logged, not asserted — the test poll observes the title instead).
    // Best-effort subframe walk (cross-origin access throws, skipped).
    @NonNull
    static final String RESTORE_SCRIPT =
            "(function(){var n=0;"
                    + "function imgs(d){var r=[];try{r=d.querySelectorAll('img');}"
                    + "catch(e){}return r;}"
                    + "function fix(im){try{"
                    + "var b=im.getAttribute('data-sinytra-src');"
                    + "if(b!==null){im.setAttribute('src',b);"
                    + "im.removeAttribute('data-sinytra-src');"
                    + "var bs=im.getAttribute('data-sinytra-srcset');"
                    + "if(bs!==null){im.setAttribute('srcset',bs);"
                    + "im.removeAttribute('data-sinytra-srcset');}return 1;}"
                    + "if(im.complete&&im.naturalWidth===0){"
                    + "var c=im.getAttribute('src');"
                    + "if(c){im.setAttribute('src','');im.setAttribute('src',c);"
                    + "return 1;}}}catch(e){}return 0;}"
                    + "try{var a=imgs(document);"
                    + "for(var i=0;i<a.length;i++){n+=fix(a[i]);}}catch(e){}"
                    + "try{for(var f=0;f<window.frames.length;f++){try{"
                    + "var d=window.frames[f].document;var s=imgs(d);"
                    + "for(var j=0;j<s.length;j++){n+=fix(s[j]);}}catch(e){}}}"
                    + "catch(e){}return n;})()";

    private ProviderImageRestore() {}

    /**
     * Fires the restore snippet, fire-and-forget: the caller (image-policy
     * setter path) must never block on page JS, and the test poll observes
     * the title change when restored images fire onload. Transport-down
     * degrades to today's behavior (logged). Safe from any thread
     * (JsBridge mailbox is synchronized, honest-null when unbound).
     */
    static void fire(@NonNull JsEvaluator js) {
        try {
            js.evaluate(RESTORE_SCRIPT, ProviderImageRestore::logResult);
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/session", "restore eval threw", t);
        }
    }

    private static void logResult(@Nullable String value) {
        android.util.Log.d("Sinytra/session", "restore eval count=" + value);
    }
}
