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
    /** Icon lookup by page URL (the shared icon store). Null = no icons. */
    interface IconLookup {
        @Nullable
        android.graphics.Bitmap lookup(@Nullable String url);
    }

    private final List<GeckoHistoryItem> mItems;
    private final int mCurrentIndex;
    @Nullable
    private final IconLookup mIcons;

    GeckoBackForwardList(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> history) {
        this(history, false, null);
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
        this(history, dropLeadingBlank, null);
    }

    GeckoBackForwardList(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> history,
            boolean dropLeadingBlank, @Nullable IconLookup icons) {
        this(translate(trim(history, dropLeadingBlank), icons),
                deriveIndex(trim(history, dropLeadingBlank), history), icons);
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
            @NonNull List<GeckoHistoryItem> items, int currentIndex,
            @Nullable IconLookup icons) {
        mItems = new ArrayList<>(items);
        mCurrentIndex = currentIndex;
        mIcons = icons;
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
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> history,
            @Nullable IconLookup icons) {
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
            items.add(new GeckoHistoryItem(uri, title, icons));
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
    @Nullable
    public WebHistoryItem getItemAtIndex(int index) {
        // Chromium returns null (not an exception) for out-of-range
        // indices — CTS WebBackForwardListTest asserts exactly that.
        if (index < 0 || index >= mItems.size()) {
            return null;
        }
        return mItems.get(index);
    }

    @Override
    public int getSize() {
        return mItems.size();
    }

    @Override
    protected android.webkit.WebBackForwardList clone() {
        return new GeckoBackForwardList(mItems, mCurrentIndex, mIcons);
    }

    private static final class GeckoHistoryItem extends WebHistoryItem {
        private final String mUrl;
        private final String mTitle;
        @Nullable
        private final IconLookup mIcons;

        GeckoHistoryItem(String url, String title,
                @Nullable IconLookup icons) {
            mUrl = url;
            mTitle = title;
            mIcons = icons;
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

        @Override
        @Nullable
        public android.graphics.Bitmap getFavicon() {
            // Shared store lookup (same object the provider's getFavicon
            // returns, so CTS sameAs holds once the fetch lands). Null
            // until fetched — a valid state.
            IconLookup icons = mIcons;
            if (icons == null) {
                return null;
            }
            try {
                return icons.lookup(mUrl);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        protected WebHistoryItem clone() {
            return new GeckoHistoryItem(mUrl, mTitle, mIcons);
        }
    }
}
