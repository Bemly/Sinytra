package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ServiceConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

// Unit locks for the tab-warmth keeper (BISECT thaw-verdict 2026-10-14: a
// serving tab frozen mid-navigation eats the whole load; thawing commits
// in ~1s). Non-creating BIND_IMPORTANT bindings hold live tabs important;
// the freezer class never sees them cached. Only the pure filter + the
// bind-skip/release/disconnect state machine are locked here (Context,
// PackageManager and real bindService need a live host: device-covered).
public final class ChildWarmthKeeperTest {
    private static final String PKG = "moe.bemly.geckowebview.debug";

    private static ChildWarmthKeeper.ServiceRef ref(String cls) {
        return new ChildWarmthKeeper.ServiceRef(PKG, cls);
    }

    // Fake binder: records binds/unbinds, programmable per-service outcome.
    private static final class FakeBinder implements ChildWarmthKeeper.Binder {
        final Map<ChildWarmthKeeper.ServiceRef, ServiceConnection> conns =
                new HashMap<>();
        final List<ChildWarmthKeeper.ServiceRef> bound = new ArrayList<>();
        final List<ChildWarmthKeeper.ServiceRef> unbound = new ArrayList<>();
        final Map<ChildWarmthKeeper.ServiceRef, Boolean> outcomes =
                new HashMap<>();

        @Override
        public boolean bind(ChildWarmthKeeper.ServiceRef svc,
                ServiceConnection conn) {
            boolean ok = outcomes.getOrDefault(svc, true);
            if (ok) {
                conns.put(svc, conn);
                bound.add(svc);
            }
            return ok;
        }

        @Override
        public void unbind(ChildWarmthKeeper.ServiceRef svc,
                ServiceConnection conn) {
            conns.remove(svc);
            unbound.add(svc);
        }
    }

    private static List<ChildWarmthKeeper.ServiceRef> services(String... cls) {
        List<ChildWarmthKeeper.ServiceRef> out = new ArrayList<>();
        for (String c : cls) {
            out.add(ref(c));
        }
        return out;
    }

    @Test
    public void filter_tabFamiliesMatch() {
        String base = "org.mozilla.gecko.process.GeckoChildProcessServices$";
        assertTrue(ChildWarmthKeeper.isTabService(base + "tab0"));
        assertTrue(ChildWarmthKeeper.isTabService(base + "tab39"));
        assertTrue(ChildWarmthKeeper.isTabService(base + "isolatedTab0"));
        assertTrue(ChildWarmthKeeper.isTabService(base + "isolatedTab12"));
    }

    @Test
    public void filter_singletonsAndMalformedMiss() {
        String base = "org.mozilla.gecko.process.GeckoChildProcessServices$";
        assertFalse(ChildWarmthKeeper.isTabService(base + "gpu"));
        assertFalse(ChildWarmthKeeper.isTabService(base + "socket"));
        assertFalse(ChildWarmthKeeper.isTabService(base + "rdd"));
        assertFalse(ChildWarmthKeeper.isTabService(base + "zygoteTab"));
        assertFalse(ChildWarmthKeeper.isTabService(base + "gmplugin"));
        assertFalse(ChildWarmthKeeper.isTabService(base + "utility"));
        assertFalse(ChildWarmthKeeper.isTabService(base + "tab"));
        assertFalse(ChildWarmthKeeper.isTabService(base + "tabX"));
        assertFalse(ChildWarmthKeeper.isTabService(base + "tab-1"));
        assertFalse(ChildWarmthKeeper.isTabService(
                "org.mozilla.gecko.process.Other$tab0"));
        assertFalse(ChildWarmthKeeper.isTabService(null));
        assertFalse(ChildWarmthKeeper.isTabService(""));
    }

    @Test
    public void sweep_bindsOnlyUnbound() {
        String base = "org.mozilla.gecko.process.GeckoChildProcessServices$";
        FakeBinder binder = new FakeBinder();
        ChildWarmthKeeper keeper = new ChildWarmthKeeper(
                services(base + "tab0", base + "tab1"), binder);
        keeper.assure();
        assertEquals(2, keeper.liveCount());
        assertEquals(2, binder.bound.size());
        // Steady state: nothing new bound.
        keeper.assure();
        assertEquals(2, keeper.liveCount());
        assertEquals(2, binder.bound.size());
    }

    @Test
    public void sweep_failedStaysUnboundAndRetries() {
        String base = "org.mozilla.gecko.process.GeckoChildProcessServices$";
        ChildWarmthKeeper.ServiceRef tab0 = ref(base + "tab0");
        FakeBinder binder = new FakeBinder();
        binder.outcomes.put(tab0, false);
        ChildWarmthKeeper keeper = new ChildWarmthKeeper(
                services(base + "tab0"), binder);
        keeper.assure();
        assertEquals(0, keeper.liveCount());
        // Next sweep retries (service came up meanwhile).
        binder.outcomes.put(tab0, true);
        keeper.assure();
        assertEquals(1, keeper.liveCount());
    }

    @Test
    public void release_unbindsAndIsIdempotent() {
        String base = "org.mozilla.gecko.process.GeckoChildProcessServices$";
        FakeBinder binder = new FakeBinder();
        ChildWarmthKeeper keeper = new ChildWarmthKeeper(
                services(base + "tab0", base + "isolatedTab0"), binder);
        keeper.assure();
        assertEquals(2, keeper.liveCount());
        keeper.release();
        assertEquals(0, keeper.liveCount());
        assertEquals(2, binder.unbound.size());
        keeper.release();
        assertEquals(2, binder.unbound.size());
    }

    @Test
    public void disconnect_marksUnboundForNextSweep() {
        String base = "org.mozilla.gecko.process.GeckoChildProcessServices$";
        ChildWarmthKeeper.ServiceRef tab0 = ref(base + "tab0");
        FakeBinder binder = new FakeBinder();
        ChildWarmthKeeper keeper = new ChildWarmthKeeper(
                services(base + "tab0"), binder);
        keeper.assure();
        assertEquals(1, keeper.liveCount());
        // Process died: the connection drops, the next sweep re-binds.
        binder.conns.get(tab0).onServiceDisconnected(null);
        assertEquals(0, keeper.liveCount());
        keeper.assure();
        assertEquals(1, keeper.liveCount());
        assertEquals(2, binder.bound.size());
    }

    @Test
    public void sweep_emptyListNoOps() {
        FakeBinder binder = new FakeBinder();
        ChildWarmthKeeper keeper = new ChildWarmthKeeper(
                new ArrayList<ChildWarmthKeeper.ServiceRef>(), binder);
        keeper.assure();
        assertEquals(0, keeper.liveCount());
        assertTrue(binder.bound.isEmpty());
        keeper.release();
        assertTrue(binder.unbound.isEmpty());
    }
}
