package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mozilla.geckowebview.session.InterceptBridge;

// Unit locks for the filter table: app prefixes are stored verbatim and
// the effective set always carries the universal prefix exactly once.
// Device side (harness intercept probes + CTS) locks that Gecko honors
// the pushed set.
public final class InterceptFilterTableTest {

    @Test
    public void empty_appendsUniversal() {
        InterceptFilterTable table = new InterceptFilterTable();
        assertArrayEquals(new String[] {"http"}, table.effective());
        assertEquals(0, table.appFilters().length);
    }

    @Test
    public void appFilters_verbatimPlusUniversalOnce() {
        InterceptFilterTable table = new InterceptFilterTable();
        table.set(new String[] {"https://body.example/"});
        assertArrayEquals(new String[] {"https://body.example/"},
                table.appFilters());
        assertArrayEquals(
                new String[] {"https://body.example/", "http"},
                table.effective());
    }

    @Test
    public void add_dedupes() {
        InterceptFilterTable table = new InterceptFilterTable();
        table.add("https://a.example/");
        table.add("https://a.example/");
        assertEquals(1, table.appFilters().length);
    }

    @Test
    public void explicitUniversal_notDuplicated() {
        InterceptFilterTable table = new InterceptFilterTable();
        table.set(new String[] {"http"});
        assertArrayEquals(new String[] {"http"}, table.effective());
    }

    @Test
    public void universal_matchesHttpAndHttps() {
        assertTrue(InterceptBridge.matchesFilterPrefix("http://h/p",
                new InterceptFilterTable().effective()));
        assertTrue(InterceptBridge.matchesFilterPrefix("https://h/p",
                new InterceptFilterTable().effective()));
    }
}
