package org.mozilla.geckowebview.provider;

import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;

// Debug-only host-main-looper watchdog (permanent CTS triage tool).
//
// Failure mode it catches: the host main thread wedges (e.g. inside a
// synchronous Gecko IPC during surface/activity teardown) and stops
// consuming input events. The device then reports
// "Input dispatching timed out ... Waited 60000ms for
// FocusEvent(hasFocus=true)" and the instrumentation dies WITHOUT a
// verdict — indistinguishable from a product hang without stacks.
// The watchdog logs the wedged stacks (main first) to logcat under
// Sinytra/watchdog so the blocker is identifiable after the fact.
//
// Debug-gated like the 0005 Java gate: installed only when the host is
// debuggable (provider debug builds). Release builds never install it:
// no thread, no logging, no overhead.
final class MainWatchdog {
    private static final String TAG = "Sinytra/watchdog";
    static final long CHECK_INTERVAL_MS = 2000;
    static final long STUCK_THRESHOLD_MS = 10000;

    private static final java.util.concurrent.atomic.AtomicBoolean
            sInstalled = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Pure (JVM-testable): FLAG_DEBUGGABLE set in the app flags.
    static boolean flagsDebuggable(int flags) {
        return (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    // Pure (JVM-testable): stuck when the last main-loop ack is older than
    // the threshold. Strictly greater: exactly-at-threshold is not stuck.
    static boolean isStuck(long nowMs, long lastAckMs) {
        return nowMs - lastAckMs > STUCK_THRESHOLD_MS;
    }

    static void installIfDebuggable(@NonNull android.content.Context context) {
        if (!sInstalled.compareAndSet(false, true)) {
            return;
        }
        // Gate on the PROVIDER build, not the host: the watchdog must run
        // inside CTS/real-host processes (non-debuggable) whenever the
        // installed provider itself is a debug build. The provider identity
        // comes from the framework's current-webview-package answer.
        boolean debuggable = false;
        try {
            android.content.pm.PackageInfo current =
                    android.webkit.WebView.getCurrentWebViewPackage();
            if (current != null) {
                android.content.pm.ApplicationInfo info = context
                        .getPackageManager().getApplicationInfo(
                                current.packageName, 0);
                debuggable = info != null && flagsDebuggable(info.flags);
            }
        } catch (Throwable t) {
            return;
        }
        if (!debuggable) {
            return;
        }
        final Handler main;
        try {
            main = new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            return;
        }
        Thread watcher = new Thread(() -> watchLoop(main),
                "Sinytra-watchdog");
        watcher.setDaemon(true);
        try {
            watcher.start();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "watchdog start threw", t);
        }
    }

    private static void watchLoop(@NonNull Handler main) {
        final long[] lastAck = {SystemClock.uptimeMillis()};
        final boolean[] inEpisode = {false};
        while (true) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                main.post(() -> {
                    lastAck[0] = SystemClock.uptimeMillis();
                });
            } catch (Throwable t) {
                return;
            }
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (isStuck(now, lastAck[0])) {
                if (!inEpisode[0]) {
                    inEpisode[0] = true;
                    dumpStacks(now - lastAck[0]);
                }
            } else {
                inEpisode[0] = false;
            }
        }
    }

    private static void dumpStacks(long stuckMs) {
        android.util.Log.w(TAG, "main looper stuck for " + stuckMs
                + "ms; dumping stacks");
        java.util.Map<Thread, StackTraceElement[]> all;
        try {
            all = Thread.getAllStackTraces();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "getAllStackTraces threw", t);
            return;
        }
        Thread mainThread = Looper.getMainLooper().getThread();
        if (mainThread != null) {
            StackTraceElement[] mainStack = all.remove(mainThread);
            logThread(mainThread, mainStack, Integer.MAX_VALUE);
        }
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry
                : all.entrySet()) {
            // Main first above; everyone else gets head frames only —
            // full dumps of 100+ threads would flood logcat.
            logThread(entry.getKey(), entry.getValue(), 20);
        }
        android.util.Log.w(TAG, "stack dump done");
    }

    private static void logThread(Thread thread,
            StackTraceElement[] stack, int maxFrames) {
        StringBuilder sb = new StringBuilder();
        sb.append("thread ").append(thread.getName())
                .append(" id=").append(thread.getId())
                .append(" state=").append(thread.getState());
        android.util.Log.w(TAG, sb.toString());
        if (stack == null) {
            return;
        }
        int frames = Math.min(stack.length, maxFrames);
        for (int i = 0; i < frames; i++) {
            android.util.Log.w(TAG, "    at " + stack[i]);
        }
        if (stack.length > frames) {
            android.util.Log.w(TAG, "    ... (" + (stack.length - frames)
                    + " more)");
        }
    }
}
