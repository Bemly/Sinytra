package org.mozilla.geckowebview.provider;

import android.graphics.Bitmap;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckowebview.session.JsEvaluator;
import org.mozilla.geckowebview.storage.GeckoWebIconDatabase;

// Favicon surface, split out of GeckoWebViewProvider (AGENTS.md §7
// file-size rule). Owns the IconFetcher wiring, the provider
// getFavicon() lookup and the history-item icon lookup — all three
// read the same shared GeckoWebIconDatabase, so CTS sameAs holds
// once the fetch lands.
final class ProviderFavicon {
    interface Host {
        @NonNull
        WebView webView();

        @Nullable
        WebChromeClient chromeClient();

        @NonNull
        GeckoWebViewFactoryProvider factory();

        @NonNull
        JsEvaluator js();

        @Nullable
        String pageUrl();
    }

    @NonNull
    private final Host mHost;
    @NonNull
    private final IconFetcher mFetcher;

    ProviderFavicon(@NonNull Host host) {
        mHost = host;
        mFetcher = new IconFetcher(new IconFetcher.Host() {
            @Override
            public void evaluate(@NonNull String script,
                    @NonNull android.webkit.ValueCallback<String> callback) {
                mHost.js().evaluate(script, callback);
            }

            @Override
            @Nullable
            public String pageUrl() {
                return mHost.pageUrl();
            }

            @Override
            @Nullable
            public WebChromeClient chromeClient() {
                return mHost.chromeClient();
            }

            @Override
            @NonNull
            public WebView webView() {
                return mHost.webView();
            }

            @Override
            @NonNull
            public GeckoWebIconDatabase iconDatabase() {
                android.webkit.WebIconDatabase icons =
                        mHost.factory().icons(mHost.webView().getContext()
                                .getApplicationContext());
                if (icons instanceof GeckoWebIconDatabase) {
                    return (GeckoWebIconDatabase) icons;
                }
                throw new IllegalStateException(
                        "icon store is not provider-owned");
            }
        });
    }

    void onPageFinished() {
        mFetcher.onPageFinished();
    }

    @Nullable
    Bitmap getFavicon() {
        try {
            String url = mHost.pageUrl();
            if (url == null) {
                return null;
            }
            return lookup().lookup(url);
        } catch (Throwable t) {
            android.util.Log.d("Sinytra/icon", "getFavicon lookup threw", t);
            return null;
        }
    }

    @NonNull
    GeckoBackForwardList.IconLookup lookup() {
        return url -> {
            try {
                if (url == null) {
                    return null;
                }
                android.webkit.WebIconDatabase icons =
                        mHost.factory().icons(mHost.webView().getContext()
                                .getApplicationContext());
                if (icons instanceof GeckoWebIconDatabase) {
                    return ((GeckoWebIconDatabase) icons).getIcon(url);
                }
                return null;
            } catch (Throwable t) {
                return null;
            }
        };
    }
}
