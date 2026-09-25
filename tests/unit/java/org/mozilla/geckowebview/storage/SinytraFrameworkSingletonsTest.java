package org.mozilla.geckowebview.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.webkit.SinytraGeolocationPermissions;
import android.webkit.SinytraWebStorage;
import android.webkit.ValueCallback;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

// JVM locks for the provider-owned WebStorage / GeolocationPermissions
// (android.webkit same-package subclasses). Locks: delegation to the
// binding, null arguments never reach it (framework accepts nulls
// silently), getAllowed(null) answers false instead of dropping the
// callback. Framework-path identity (factory returns these, no getInstance
// recursion) is a device lock (FrameworkEntry probe after the switch).
public class SinytraFrameworkSingletonsTest {

    private static final class RecordingStorage implements SinytraWebStorage.Binding {
        final List<String> calls = new ArrayList<>();

        @Override
        public void getOrigins(ValueCallback<Map> callback) {
            calls.add("getOrigins");
            callback.onReceiveValue(Collections.emptyMap());
        }

        @Override
        public void getUsageForOrigin(String origin, ValueCallback<Long> callback) {
            calls.add("usage:" + origin);
            callback.onReceiveValue(0L);
        }

        @Override
        public void getQuotaForOrigin(String origin, ValueCallback<Long> callback) {
            calls.add("quota:" + origin);
            callback.onReceiveValue(0L);
        }

        @Override
        public void deleteOrigin(String origin) {
            calls.add("delete:" + origin);
        }

        @Override
        public void deleteAllData() {
            calls.add("deleteAll");
        }
    }

    private static final class RecordingGeo implements SinytraGeolocationPermissions.Binding {
        final List<String> calls = new ArrayList<>();

        @Override
        public void getOrigins(ValueCallback<Set<String>> callback) {
            calls.add("getOrigins");
            callback.onReceiveValue(Collections.emptySet());
        }

        @Override
        public void getAllowed(String origin, ValueCallback<Boolean> callback) {
            calls.add("allowed:" + origin);
            callback.onReceiveValue(Boolean.TRUE);
        }

        @Override
        public void clear(String origin) {
            calls.add("clear:" + origin);
        }

        @Override
        public void allow(String origin) {
            calls.add("allow:" + origin);
        }

        @Override
        public void clearAll() {
            calls.add("clearAll");
        }
    }

    @Test
    public void webStorage_delegatesEveryCall() {
        RecordingStorage binding = new RecordingStorage();
        SinytraWebStorage storage = new SinytraWebStorage(binding);
        final Object[] origins = new Object[1];
        storage.getOrigins(v -> origins[0] = v);
        storage.getUsageForOrigin("https://a.example", v -> {});
        storage.getQuotaForOrigin("https://a.example", v -> {});
        storage.deleteOrigin("https://a.example");
        storage.deleteAllData();
        assertEquals(List.of("getOrigins", "usage:https://a.example",
                "quota:https://a.example", "delete:https://a.example", "deleteAll"),
                binding.calls);
        assertSame(Collections.emptyMap(), origins[0]);
    }

    @Test
    public void webStorage_nullArgumentsNeverReachBinding() {
        RecordingStorage binding = new RecordingStorage();
        SinytraWebStorage storage = new SinytraWebStorage(binding);
        storage.getOrigins(null);
        storage.getUsageForOrigin(null, v -> {});
        storage.getUsageForOrigin("https://a.example", null);
        storage.getQuotaForOrigin(null, v -> {});
        storage.deleteOrigin(null);
        storage.setQuotaForOrigin("https://a.example", 1L);
        assertEquals(Collections.emptyList(), binding.calls);
    }

    @Test
    public void geolocation_delegatesEveryCall() {
        RecordingGeo binding = new RecordingGeo();
        SinytraGeolocationPermissions geo = new SinytraGeolocationPermissions(binding);
        final Boolean[] allowed = new Boolean[1];
        geo.getOrigins(v -> {});
        geo.getAllowed("https://a.example", v -> allowed[0] = v);
        geo.allow("https://a.example");
        geo.clear("https://a.example");
        geo.clearAll();
        assertEquals(List.of("getOrigins", "allowed:https://a.example",
                "allow:https://a.example", "clear:https://a.example", "clearAll"),
                binding.calls);
        assertEquals(Boolean.TRUE, allowed[0]);
    }

    @Test
    public void geolocation_nullOriginAnswersFalseWithoutBinding() {
        RecordingGeo binding = new RecordingGeo();
        SinytraGeolocationPermissions geo = new SinytraGeolocationPermissions(binding);
        final Boolean[] allowed = new Boolean[1];
        geo.getAllowed(null, v -> allowed[0] = v);
        geo.getAllowed("https://a.example", null);
        geo.allow(null);
        geo.clear(null);
        geo.getOrigins(null);
        assertEquals(Boolean.FALSE, allowed[0]);
        assertEquals(Collections.emptyList(), binding.calls);
    }
}
