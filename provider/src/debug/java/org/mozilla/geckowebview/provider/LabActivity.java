package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

// Interactive manual lab (debug-only; release APKs never contain it).
// A minimal browser shell on the real framework path — new WebView()
// through WebViewFactory like any third-party app — for triage that needs
// hands, not probes: arbitrary URLs, about: pages (about:about /
// about:config / about:support / about:buildconfig), and User-Agent
// override with end-to-end verification via navigator.userAgent.
// Not an acceptance gate (that is FrameworkEntryActivity); no verdicts,
// just a status line. Requires the device to be switched to us
// (DEVICE §5) or it silently exercises Chromium.
public final class LabActivity extends Activity {
    private static final String TAG = "Sinytra/lab";
    private static final String HOME = "https://example.com/";

    private EditText mUrlInput;
    private EditText mUaInput;
    private TextView mStatus;
    private WebView mWebView;
    private String mDefaultUa = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        mUrlInput = new EditText(this);
        mUrlInput.setHint("url or about:config");
        mUrlInput.setSingleLine(true);
        mUrlInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button go = new Button(this);
        go.setText("GO");
        urlRow.addView(mUrlInput);
        urlRow.addView(go);
        root.addView(urlRow);

        LinearLayout uaRow = new LinearLayout(this);
        uaRow.setOrientation(LinearLayout.HORIZONTAL);
        mUaInput = new EditText(this);
        mUaInput.setHint("User-Agent");
        mUaInput.setSingleLine(true);
        mUaInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button setUa = new Button(this);
        setUa.setText("SET");
        Button resetUa = new Button(this);
        resetUa.setText("RST");
        uaRow.addView(mUaInput);
        uaRow.addView(setUa);
        uaRow.addView(resetUa);
        root.addView(uaRow);

        LinearLayout aboutRow = new LinearLayout(this);
        aboutRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] shortcuts = {
                "about:about", "about:config", "about:support",
                "about:buildconfig",
        };
        for (String page : shortcuts) {
            Button b = new Button(this);
            b.setText(page.substring("about:".length()));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    load(page);
                }
            });
            aboutRow.addView(b);
        }
        root.addView(aboutRow);

        mStatus = new TextView(this);
        mStatus.setSingleLine(true);
        mStatus.setText("idle");
        root.addView(mStatus);

        mWebView = new WebView(this);
        mWebView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(mWebView);
        setContentView(root);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        mDefaultUa = String.valueOf(settings.getUserAgentString());
        mUaInput.setText(mDefaultUa);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url,
                    Bitmap favicon) {
                setStatus("started: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                setStatus("finished: " + url + " title=" + view.getTitle());
                Log.d(TAG, "onPageFinished url=" + url
                        + " title=" + view.getTitle());
            }
        });
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                setStatus(progress + "% " + view.getUrl());
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                setStatus("title: " + title);
            }
        });

        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(mUrlInput.getText().toString().trim());
            }
        });
        setUa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyUa(mUaInput.getText().toString());
            }
        });
        resetUa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mUaInput.setText(mDefaultUa);
                applyUa(mDefaultUa);
            }
        });

        mUrlInput.setText(HOME);
        // Cold-start entry only (am force-stop + am start --es url, or
        // launcher tap): onNewIntent delivery to the live instance was
        // twice verified inert on-device (2026-10-14, intent "delivered to
        // top-most instance" but no navigation), so no onNewIntent path —
        // relaunch cold instead of shipping an unverified route.
        String startUrl = getIntent().getStringExtra("url");
        load(startUrl != null && !startUrl.trim().isEmpty()
                ? startUrl.trim() : HOME);
    }

    // Scheme-aware: only bare hosts get https:// — about:/data:/file:
    // URLs pass through untouched (Chromium parity: the address bar never
    // rewrites a URL that already names its scheme).
    private void load(String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String url = raw;
        if (!raw.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            url = "https://" + raw;
        }
        mUrlInput.setText(url);
        setStatus("loading: " + url);
        Log.d(TAG, "loadUrl url=" + url);
        mWebView.loadUrl(url);
    }

    // UA override applies to subsequent loads; reload + read back
    // navigator.userAgent so the status line shows the engine-effective
    // value, not just the setter echo. Eval needs the page transport —
    // on transport-less documents (some about: pages) the readback
    // honestly reports unavailable instead of hanging.
    private void applyUa(String ua) {
        mWebView.getSettings().setUserAgentString(ua);
        Log.d(TAG, "setUserAgentString ua=" + ua);
        mWebView.reload();
        mWebView.evaluateJavascript("navigator.userAgent",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        setStatus("ua-effective: " + value);
                        Log.d(TAG, "navigator.userAgent=" + value);
                    }
                });
    }

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mStatus.setText(text);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            mWebView.destroy();
        }
        super.onDestroy();
    }
}
