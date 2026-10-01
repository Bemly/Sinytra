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
        this(history, false);
    }

    // dropLeadingBlank: drop a pristine leading about:blank document entry
    // (see GeckoSessionBridge.mExplicitAboutLoad). The trimmed list drives
    // both items and index: a HistoryList current index shifts down by one
    // when entry 0 is dropped; a current index of 0 (the blank itself)
    // becomes empty (-1/null). Known edge, unchanged: raw goBack() can
    // still land on the phantom blank (navigation targets use untrimmed
    // Gecko indices); only the reported list is Chromium-shaped.
    GeckoBackForwardList(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> history,
            boolean dropLeadingBlank) {
        this(translate(trim(history, dropLeadingBlank)),
                deriveIndex(trim(history, dropLeadingBlank), history));
    }

    private static List<GeckoSession.HistoryDelegate.HistoryItem> trim(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> history,
            boolean dropLeadingBlank) {
        if (!dropLeadingBlank || history.isEmpty()) {
            return history;
        }
        String first = uriOf(history.get(0));
        if (first == null || first.isEmpty() || "about:blank".equalsIgnoreCase(first)) {
            return history.subList(1, history.size());
        }
        return history;
    }

    @Nullable
    private static String uriOf(
            @NonNull GeckoSession.HistoryDelegate.HistoryItem item) {
        try {
            return item.getUri();
        } catch (UnsupportedOperationException e) {
            return null;
        }
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
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> trimmed,
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> original) {
        int current = -1;
        if (original instanceof GeckoSession.HistoryDelegate.HistoryList) {
            try {
                current = ((GeckoSession.HistoryDelegate.HistoryList) original)
                        .getCurrentIndex();
                if (trimmed.size() != original.size()) {
                    // Exactly one leading entry was dropped.
                    current -= 1;
                }
            } catch (UnsupportedOperationException e) {
                current = -1;
            }
        }
        if (current < 0 && !trimmed.isEmpty()) {
            current = trimmed.size() - 1;
        }
        if (current >= trimmed.size()) {
            current = trimmed.isEmpty() ? -1 : trimmed.size() - 1;
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
