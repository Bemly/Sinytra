package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.mozilla.geckoview.GeckoSession;

// Unit locks for GeckoBackForwardList translation (ARCHITECTURE.md §4:
// Gecko owns the history — this class only translates). Index derivation
// is the core invariant: a real HistoryList's getCurrentIndex is honored,
// a plain List falls back to the last entry, empty stays -1.
//
// Not lockable on the JVM: the SessionState parcel path (framework Parcel
// is stubbed) — the saveState/restoreState round-trip stays device-locked
// by the P0GlueActivity harness probes.
public final class GeckoBackForwardListTest {

    private static GeckoSession.HistoryDelegate.HistoryItem item(
            final String uri, final String title) {
        return new GeckoSession.HistoryDelegate.HistoryItem() {
            @Override
            public String getUri() {
                return uri;
            }

            @Override
            public String getTitle() {
                return title;
            }
        };
    }

    private static List<GeckoSession.HistoryDelegate.HistoryItem> items(
            String... uris) {
        List<GeckoSession.HistoryDelegate.HistoryItem> list = new ArrayList<>();
        for (String uri : uris) {
            list.add(item(uri, "title-" + uri));
        }
        return list;
    }

    @Test
    public void plainList_currentIndexFallsBackToLast() {
        GeckoBackForwardList list = new GeckoBackForwardList(
                items("https://a.example", "https://b.example"));
        assertEquals(2, list.getSize());
        assertEquals(1, list.getCurrentIndex());
        assertEquals("https://a.example", list.getItemAtIndex(0).getUrl());
        assertEquals("https://b.example", list.getItemAtIndex(1).getUrl());
        assertSame(list.getItemAtIndex(1), list.getCurrentItem());
        assertEquals("title-https://b.example", list.getCurrentItem().getTitle());
    }

    private static final class FixedIndexList
            extends java.util.AbstractList<
                    GeckoSession.HistoryDelegate.HistoryItem>
            implements GeckoSession.HistoryDelegate.HistoryList {
        private final List<GeckoSession.HistoryDelegate.HistoryItem> mBacking;
        private final int mCurrentIndex;

        FixedIndexList(List<GeckoSession.HistoryDelegate.HistoryItem> backing,
                int currentIndex) {
            mBacking = backing;
            mCurrentIndex = currentIndex;
        }

        @Override
        public GeckoSession.HistoryDelegate.HistoryItem get(int index) {
            return mBacking.get(index);
        }

        @Override
        public int size() {
            return mBacking.size();
        }

        @Override
        public int getCurrentIndex() {
            return mCurrentIndex;
        }
    }

    @Test
    public void historyList_currentIndexIsHonored() {
        GeckoBackForwardList list = new GeckoBackForwardList(
                new FixedIndexList(items("https://a.example",
                        "https://b.example", "https://c.example"), 1));
        assertEquals(3, list.getSize());
        assertEquals(1, list.getCurrentIndex());
        assertEquals("https://b.example", list.getCurrentItem().getUrl());
    }

    @Test
    public void emptyList_indexMinusOneAndNullCurrent() {
        GeckoBackForwardList list = new GeckoBackForwardList(
                Collections.emptyList());
        assertEquals(0, list.getSize());
        assertEquals(-1, list.getCurrentIndex());
        assertNull(list.getCurrentItem());
    }

    @Test
    public void uriTitleAreTranslated_verbatim() {
        GeckoBackForwardList list = new GeckoBackForwardList(
                Collections.singletonList(item("https://x.example", "X")));
        assertEquals("https://x.example", list.getItemAtIndex(0).getUrl());
        assertEquals("https://x.example",
                list.getItemAtIndex(0).getOriginalUrl());
        assertEquals("X", list.getItemAtIndex(0).getTitle());
    }

    @Test
    public void historyItemThrowingUnsupportedOp_mapsHonest() {
        // A HistoryItem whose accessors throw (GV default-impl pattern):
        // the translation must degrade to empty uri / null title, never
        // propagate.
        GeckoSession.HistoryDelegate.HistoryItem hostile =
                new GeckoSession.HistoryDelegate.HistoryItem() {
                    @Override
                    public String getUri() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public String getTitle() {
                        throw new UnsupportedOperationException();
                    }
                };
        GeckoBackForwardList list = new GeckoBackForwardList(
                Arrays.asList(hostile));
        assertEquals(1, list.getSize());
        assertEquals("", list.getItemAtIndex(0).getUrl());
        assertNull(list.getItemAtIndex(0).getTitle());
    }

    @Test
    public void clone_isIndependentCopy() {
        GeckoBackForwardList original = new GeckoBackForwardList(
                items("https://a.example", "https://b.example"));
        GeckoBackForwardList copy = (GeckoBackForwardList) original.clone();
        assertEquals(original.getSize(), copy.getSize());
        assertEquals(original.getCurrentIndex(), copy.getCurrentIndex());
        assertEquals(original.getCurrentItem().getUrl(),
                copy.getCurrentItem().getUrl());
        assertEquals("https://a.example", copy.getItemAtIndex(0).getUrl());
    }
}
