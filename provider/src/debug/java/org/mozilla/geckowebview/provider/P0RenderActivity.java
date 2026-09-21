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

        GeckoRuntimeHolder.attachView(this, view, mSession);
        Log.i(TAG, "loading " + URL);
        mSession.loadUri(URL);
    }

    @Override
    protected void onDestroy() {
        if (mSession != null) {
            mSession.close();
        }
        super.onDestroy();
    }
}
