package org.mozilla.geckowebview.view;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckowebview.session.GeckoSessionBridge;

// GeckoView host owned by the provider layer (ARCHITECTURE.md §3: view hosts
// the session's surface/input; session bridge never touches the View).
// Thin wrapper today: construction + attach/detach helpers. Scroll/input/
// IME delegation grows here in P1 (GeckoScrollDelegate etc.).
public class GeckoViewHost extends GeckoView {
    private static final String TAG = "Sinytra/view";
    @Nullable
    private GeckoSessionBridge mBridge;

    public GeckoViewHost(@NonNull Context context) {
        super(context);
    }

    public GeckoViewHost(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void bind(@NonNull Context context, @NonNull GeckoSessionBridge bridge) {
        if (mBridge != null) {
            Log.w(TAG, "rebind: releasing previous session");
            release();
        }
        mBridge = bridge;
        bridge.attachTo(context, this);
    }

    public void release() {
        // GeckoView.setSession is @NonNull in GV 153: detach by closing the
        // session side instead of passing null (session.close unbinds it).
        if (mBridge != null) {
            mBridge.close();
            mBridge = null;
        }
    }

    @Nullable
    public GeckoSessionBridge bridge() {
        return mBridge;
    }
}
