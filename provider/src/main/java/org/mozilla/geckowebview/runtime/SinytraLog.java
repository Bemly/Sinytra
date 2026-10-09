package org.mozilla.geckowebview.runtime;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Central process-wide leveled log gate. Levels mirror android.util.Log
// (VERBOSE < DEBUG < INFO < WARN < ERROR). Effective level per call:
// WARN and above always log (contract failures must surface everywhere);
// otherwise an explicit setLevel() wins (tests, harness); else the
// platform override `adb shell setprop log.tag.Sinytra <LEVEL>` (no
// rebuild; note: the property name cannot contain '/', so the override is
// process-global, not per-module); else debuggable builds log DEBUG+,
// release builds INFO+. So release carries I/W/E and drops D/V; debug
// carries D and drops V; VERBOSE only when explicitly enabled. Tag rule
// (AGENTS §7): always "Sinytra/<module>", never anything else.
// Bottom layer (AGENTS §2): depends on nothing of ours.
public final class SinytraLog {
    public static final int VERBOSE = Log.VERBOSE;
    public static final int DEBUG = Log.DEBUG;
    public static final int INFO = Log.INFO;
    public static final int WARN = Log.WARN;
    public static final int ERROR = Log.ERROR;

    // Explicit level override; -1 = automatic (setprop, then debuggable).
    private static volatile int sExplicitLevel = -1;
    private static volatile boolean sDebuggable;

    // Slash-free tag for the platform property override (see class header).
    private static final String GLOBAL_TAG = "Sinytra";

    private SinytraLog() {}

    // One-shot-ish process init (idempotent, last wins — every caller in a
    // process computes the same value). Call as early as a Context exists
    // (factory create/createWebView); logging before init behaves as
    // release (INFO+) which is the safe default.
    public static void init(boolean debuggable) {
        sDebuggable = debuggable;
    }

    // Explicit override, e.g. harness stress or unit tests. -1 restores
    // automatic. Callers must restore after tests (static process state).
    public static void setLevel(int level) {
        sExplicitLevel = level;
    }

    // Pure (JVM-locked): the gate decision without emitting.
    public static boolean isEnabled(@NonNull String tag, int level) {
        if (level >= WARN) {
            return true;
        }
        int explicitLevel = sExplicitLevel;
        if (explicitLevel >= 0) {
            return level >= explicitLevel;
        }
        // Process-global override (per-module tags contain '/', which is
        // illegal in property names — verified on device).
        if (Log.isLoggable(GLOBAL_TAG, level)) {
            return true;
        }
        return sDebuggable ? level >= DEBUG : level >= INFO;
    }

    public static void v(@NonNull String tag, @NonNull String msg) {
        if (isEnabled(tag, VERBOSE)) {
            Log.v(tag, msg);
        }
    }

    public static void d(@NonNull String tag, @NonNull String msg) {
        if (isEnabled(tag, DEBUG)) {
            Log.d(tag, msg);
        }
    }

    public static void d(@NonNull String tag, @NonNull String msg,
            @Nullable Throwable tr) {
        if (isEnabled(tag, DEBUG)) {
            Log.d(tag, msg, tr);
        }
    }

    public static void i(@NonNull String tag, @NonNull String msg) {
        if (isEnabled(tag, INFO)) {
            Log.i(tag, msg);
        }
    }

    public static void w(@NonNull String tag, @NonNull String msg) {
        if (isEnabled(tag, WARN)) {
            Log.w(tag, msg);
        }
    }

    public static void w(@NonNull String tag, @NonNull String msg,
            @Nullable Throwable tr) {
        if (isEnabled(tag, WARN)) {
            Log.w(tag, msg, tr);
        }
    }

    public static void e(@NonNull String tag, @NonNull String msg) {
        if (isEnabled(tag, ERROR)) {
            Log.e(tag, msg);
        }
    }

    public static void e(@NonNull String tag, @NonNull String msg,
            @Nullable Throwable tr) {
        if (isEnabled(tag, ERROR)) {
            Log.e(tag, msg, tr);
        }
    }
}
