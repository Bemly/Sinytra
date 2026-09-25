package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public final class P0RenderActivity extends Activity {
    private static final String TAG = "Sinytra/p0";
    private static final String URL = "https://example.com";

    private GeckoSession mSession;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Debug-only target override: am start ... --es url <target> drives
        // the canary at an arbitrary page (download/0006 repros etc.).
        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            url = URL;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        mStatus = new TextView(this);
        mStatus.setText("P0: creating runtime…");
        GeckoView view = new GeckoView(this);
        layout.addView(mStatus);
        layout.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(layout);

        mSession = new GeckoSession();
        mSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession session, String url) {
                Log.i(TAG, "onPageStart " + url);
                runOnUiThread(() -> mStatus.setText("loading: " + url));
            }

            @Override
            public void onPageStop(GeckoSession session, boolean success) {
                Log.i(TAG, "onPageStop success=" + success);
                runOnUiThread(() -> mStatus.setText(
                        success ? "P0 PASS: page stopped, success=true"
                                : "P0 FAIL: page stopped, success=false"));
            }

            @Override
            public void onProgressChange(GeckoSession session, int progress) {
                Log.i(TAG, "progress=" + progress);
            }
        });
        mSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession session, String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                    Boolean hasUserGesture) {
                Log.i(TAG, "location=" + url);
            }

            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBack) {
                Log.i(TAG, "canGoBack=" + canGoBack);
            }

            @Override
            public void onCanGoForward(GeckoSession session, boolean canGoForward) {
                Log.i(TAG, "canGoForward=" + canGoForward);
            }
        });
        // 0006 download investigation: observe the external-response dispatch
        // end to end (Gecko helper-app → ContentDelegate.onExternalResponse;
        // the provider's WebView-facing DownloadListener wiring is verified
        // separately in the P0Glue harness).
        mSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onExternalResponse(GeckoSession session,
                    org.mozilla.geckoview.WebResponse response) {
                Log.i(TAG, "onExternalResponse uri=" + response.uri
                        + " status=" + response.statusCode);
            }
        });

        GeckoRuntimeHolder.attachView(this, view, mSession);
        Log.i(TAG, "loading " + url);
        mSession.loadUri(url);
    }

    @Override
    protected void onDestroy() {
        if (mSession != null) {
            mSession.close();
        }
        super.onDestroy();
    }
}
