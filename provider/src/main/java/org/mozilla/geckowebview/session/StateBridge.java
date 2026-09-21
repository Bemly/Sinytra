package org.mozilla.geckowebview.session;

import android.os.Bundle;
import android.os.Parcel;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.mozilla.geckoview.GeckoSession;

// StateBridge: SessionState/HistoryList <-> Bundle/WebBackForwardList
// translation. Gecko owns history (ARCHITECTURE.md §4) — this class only
// translates, never re-implements a history stack.
public final class StateBridge {
    private static final String KEY_STATE_STRING = "sinytra.session_state";
    private static final String KEY_URLS = "sinytra.history_urls";
    private static final String KEY_TITLES = "sinytra.history_titles";
    private static final String KEY_INDEX = "sinytra.history_index";

    private StateBridge() {}

    // Persist the live snapshot into an outState Bundle. Prefers the
    // Parcelable SessionState round-trip; falls back to flat lists so a
    // state is still restorable even if the parcel path is unavailable.
    public static boolean saveInto(@NonNull Bundle outState,
            @Nullable GeckoSession.SessionState state,
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> snapshot) {
        if (state != null) {
            try {
                Parcel parcel = Parcel.obtain();
                try {
                    state.writeToParcel(parcel, 0);
                    parcel.setDataPosition(0);
                    GeckoSession.SessionState copy =
                            GeckoSession.SessionState.CREATOR.createFromParcel(parcel);
                    outState.putParcelable(KEY_STATE_STRING, copy);
                } finally {
                    parcel.recycle();
                }
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/state", "parcel save failed, using flat lists", t);
            }
        }
        ArrayList<String> urls = new ArrayList<>(snapshot.size());
        ArrayList<String> titles = new ArrayList<>(snapshot.size());
        int index = -1;
        if (!snapshot.isEmpty()) {
            if (snapshot instanceof GeckoSession.HistoryDelegate.HistoryList) {
                try {
                    index = ((GeckoSession.HistoryDelegate.HistoryList) snapshot)
                            .getCurrentIndex();
                } catch (UnsupportedOperationException e) {
                    index = snapshot.size() - 1;
                }
            } else {
                index = snapshot.size() - 1;
            }
            for (GeckoSession.HistoryDelegate.HistoryItem item : snapshot) {
                String uri;
                String title;
                try {
                    uri = item.getUri();
                } catch (UnsupportedOperationException e) {
                    uri = "";
                }
                try {
                    title = item.getTitle();
                } catch (UnsupportedOperationException e) {
                    title = null;
                }
                urls.add(uri != null ? uri : "");
                titles.add(title);
            }
        }
        outState.putStringArrayList(KEY_URLS, urls);
        outState.putStringArrayList(KEY_TITLES, titles);
        outState.putInt(KEY_INDEX, index);
        return true;
    }

    // Restore priority: Parcelable SessionState first (full Gecko state:
    // history + scroll + zoom + form data), flat lists as fallback signal
    // (caller re-navigates to the indexed URL).
    @Nullable
    public static GeckoSession.SessionState restoreParcelable(@NonNull Bundle inState) {
        try {
            android.os.Parcelable parcelable =
                    inState.getParcelable(KEY_STATE_STRING);
            if (parcelable instanceof GeckoSession.SessionState) {
                return (GeckoSession.SessionState) parcelable;
            }
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/state", "parcel restore failed", t);
        }
        return null;
    }

    @NonNull
    public static List<String> restoreUrls(@NonNull Bundle inState) {
        ArrayList<String> urls = inState.getStringArrayList(KEY_URLS);
        return urls != null ? urls : new ArrayList<>();
    }

    public static int restoreIndex(@NonNull Bundle inState) {
        return inState.getInt(KEY_INDEX, -1);
    }
}
