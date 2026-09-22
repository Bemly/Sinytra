package org.mozilla.geckowebview.provider;

import android.webkit.WebHistoryItem;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.mozilla.geckoview.GeckoSession;

// Read-only WebBackForwardList translated from Gecko's SessionState/HistoryList.
// Gecko owns history (ARCHITECTURE.md §4) — this class only translates.
final class GeckoBackForwardList extends android.webkit.WebBackForwardList {
    private final List<GeckoHistoryItem> mItems;
    private final int mCurrentIndex;

    GeckoBackForwardList(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> history) {
        this(translate(history), deriveIndex(history));
    }

    // Value copy for clone(): shares the immutable GeckoHistoryItem
    // instances (they are value holders) but owns a fresh list so later
    // mutations never cross. Preserves the current index — the framework
    // contract for WebBackForwardList.clone()/copyBackForwardList().
    private GeckoBackForwardList(
            @NonNull List<GeckoHistoryItem> items, int currentIndex) {
        mItems = new ArrayList<>(items);
        mCurrentIndex = currentIndex;
    }

    private static int deriveIndex(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> history) {
        int current = -1;
        if (history instanceof GeckoSession.HistoryDelegate.HistoryList) {
            try {
                current = ((GeckoSession.HistoryDelegate.HistoryList) history)
                        .getCurrentIndex();
            } catch (UnsupportedOperationException e) {
                current = -1;
            }
        }
        if (current < 0 && !history.isEmpty()) {
            current = history.size() - 1;
        }
        return current;
    }

    @NonNull
    private static List<GeckoHistoryItem> translate(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> history) {
        List<GeckoHistoryItem> items =
                new ArrayList<>(history.size());
        for (GeckoSession.HistoryDelegate.HistoryItem item : history) {
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
            items.add(new GeckoHistoryItem(uri, title));
        }
        return items;
    }

    @Override
    public WebHistoryItem getCurrentItem() {
        if (mCurrentIndex < 0 || mCurrentIndex >= mItems.size()) {
            return null;
        }
        return mItems.get(mCurrentIndex);
    }

    @Override
    public int getCurrentIndex() {
        return mCurrentIndex;
    }

    @Override
    public WebHistoryItem getItemAtIndex(int index) {
        return mItems.get(index);
    }

    @Override
    public int getSize() {
        return mItems.size();
    }

    @Override
    protected android.webkit.WebBackForwardList clone() {
        return new GeckoBackForwardList(mItems, mCurrentIndex);
    }

    private static final class GeckoHistoryItem extends WebHistoryItem {
        private final String mUrl;
        private final String mTitle;

        GeckoHistoryItem(String url, String title) {
            mUrl = url;
            mTitle = title;
        }

        @Override
        public String getUrl() {
            return mUrl;
        }

        @Override
        public String getOriginalUrl() {
            return mUrl;
        }

        @Override
        public String getTitle() {
            return mTitle;
        }

        @Nullable
        @Override
        public android.graphics.Bitmap getFavicon() {
            return null;
        }

        @Override
        protected WebHistoryItem clone() {
            return new GeckoHistoryItem(mUrl, mTitle);
        }
    }
}
