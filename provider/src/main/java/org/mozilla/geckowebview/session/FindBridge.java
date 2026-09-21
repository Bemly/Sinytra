package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.SessionFinder;

// Find-in-page over SessionFinder. Owns the in-flight find state machine
// (query + direction); results fan out to the host for FindListener.
public final class FindBridge {
    public interface Host {
        void onFindResult(int activeMatchOrdinal, int numberOfMatches, boolean done);
    }

    private final GeckoSession mSession;
    private final Host mHost;
    @Nullable
    private String mQuery;
    @Nullable
    private android.webkit.WebView.FindListener mListener;

    public FindBridge(@NonNull GeckoSession session, @NonNull Host host) {
        mSession = session;
        mHost = host;
    }

    public void setFindListener(@Nullable android.webkit.WebView.FindListener listener) {
        mListener = listener;
    }

    public void findAllAsync(@NonNull String find) {
        mQuery = find;
        try {
            SessionFinder finder = mSession.getFinder();
            finder.clear();
            GeckoResult<GeckoSession.FinderResult> result =
                    finder.find(find, GeckoSession.FINDER_FIND_FORWARD);
            result.accept(this::deliver, e -> deliverFailure());
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/find", "findAllAsync threw", t);
            deliverFailure();
        }
    }

    public void findNext(boolean forward) {
        String query = mQuery;
        if (query == null) {
            return;
        }
        try {
            int flags = forward ? GeckoSession.FINDER_FIND_FORWARD
                    : GeckoSession.FINDER_FIND_BACKWARDS;
            mSession.getFinder().find(query, flags).accept(this::deliver,
                    e -> deliverFailure());
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/find", "findNext threw", t);
            deliverFailure();
        }
    }

    public void clearMatches() {
        try {
            mSession.getFinder().clear();
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/find", "clearMatches threw", t);
        }
        mQuery = null;
    }

    private void deliver(@Nullable GeckoSession.FinderResult result) {
        int current = 0;
        int total = 0;
        if (result != null) {
            current = Math.max(0, result.current - 1);
            total = Math.max(0, result.total);
        }
        notifyListener(current, total, true);
    }

    private void deliverFailure() {
        notifyListener(0, 0, true);
    }

    private void notifyListener(int current, int total, boolean done) {
        try {
            mHost.onFindResult(current, total, done);
            android.webkit.WebView.FindListener listener = mListener;
            if (listener != null) {
                listener.onFindResultReceived(current, total, done);
            }
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/find", "FindListener threw", t);
        }
    }
}
