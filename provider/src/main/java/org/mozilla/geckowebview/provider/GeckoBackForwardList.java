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
        mItems = new ArrayList<>(history.size());
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
            mItems.add(new GeckoHistoryItem(uri, title));
        }
        mCurrentIndex = current;
    }

    @Nullable
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
        GeckoBackForwardList copy = new GeckoBackForwardList(java.util.Collections.emptyList());
        copy.mItems.addAll(mItems);
        return copy;
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
