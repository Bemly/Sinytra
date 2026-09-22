package org.mozilla.geckowebview.session;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

// JsBridge: built-in WebExtension transport for evaluateJavascript,
// addJavascriptInterface dispatch and WebMessage posts.
//
// Why an extension (API_MAPPING.md section 7, ARCHITECTURE.md section 6.4):
// GV153 exposes NO JS-eval primitive on GeckoSession (AAR javap confirmed:
// no evaluat/script API on GeckoSession). Mozilla's documented embedding
// path for "run JS in page + talk back to Java" is a privileged built-in
// WebExtension with nativeMessaging + content scripts (see content.js and
// page-shim.js under src/main/assets/sinytra-js). No firefox-patch needed:
// ensureBuiltIn + SessionController.setMessageDelegate are public API.
//
// Transport design (mailbox + poll, NO Port posts app->page):
//
// Java Port.postMessage can only loop back to Java (parent routes
// PortMessageFromApp via castPortMessage("port", {source:true}) to the
// Java-side EmbedderPort conduit itself; the background page never sees
// it -- 7真机 rounds proved it: posted at Java, zero background/content
// observations, upward pings live). There is also no Java->content
// one-off-send primitive. So the app->page direction CANNOT push.
//
// Instead both directions rendezvous through onMessage request/response:
//
//   Page -> Java (content poll): content.js sends
//     {kind:"poll"} via runtime.sendNativeMessage("sinytra")
//     -> session MessageDelegate.onMessage. Java's delegate returns a
//     GeckoResult<Object> that it completes LATER, when a request is
//     enqueued (or immediately with {kind:"noop"} when the mailbox is
//     empty). Completing the GeckoResult delivers the payload as the
//     sendNativeMessage promise resolution in the content script.
//     The content script runs the request in the page shim, then
//     sendNativeMessages the {kind:"result"} back (fire-and-forget,
//     delegate returns null).
//   Java -> page: evaluate()/registerInterface()/etc. just enqueue into
//     the session mailbox; the next poll (or a woken poll) picks it up.
//
// This is textbook long-polling over an API designed exactly for it
// (GeckoResult = async reply; MessagingExample returns null only
// because it has nothing to say later). No hidden API, no reflection,
// no tabs permission, no Port posts app->page.
//
// Wiring (all UI thread per GeckoView contract):
//   bind(runtime, session)
//    -> ensureBuiltIn("resource://android/assets/sinytra-js/",
//                     "sinytra-js@bemly.moe")   (once per process)
//    -> session.getWebExtensionController()
//         .setMessageDelegate(ext, sessionDelegate, "sinytra")  (per session:
//       poll/result/event traffic from THIS session's content scripts;
//       sender.session identifies the session)
//
// Message protocol: see assets/sinytra-js/content.js header. Every request
// carries an int id; replies echo it. Pending Java callbacks are keyed by
// id and completed on the thread Gecko delivers onMessage (UI thread).
// WebView evaluateJavascript callbacks run on the caller's thread on
// Chromium -- we invoke inline on the delivery thread instead; callers
// must be thread-safe, same constraint as Chromium.
//
// Failure semantics (honest, never silently wrong):
// - Extension not installed / session delegate not bound yet: eval
//   reports null through the callback (P2-1 honest-null preserved).
// - Page gone / shim missing / content script not polling (e.g. no
//   matching content script on the page): the poll never arrives, the
//   request sits in the mailbox until EVAL_TIMEOUT_MS, then the callback
//   gets null, pending entry dropped, loud log.
// - Timeout (no reply in EVAL_TIMEOUT_MS): callback gets null.
public final class JsBridge {
    private static final String TAG = "Sinytra/js";
    private static final String EXTENSION_LOCATION =
            "resource://android/assets/sinytra-js/";
    private static final String EXTENSION_ID = "sinytra-js@bemly.moe";
    static final String NATIVE_APP = "sinytra";

    private static final long EVAL_TIMEOUT_MS = 30000L;
    // A poll with an empty mailbox is held this long before Java answers
    // {kind:"noop"} (long-poll window). Short enough that page answers
    // stay interactive, long enough that an idle page costs ~1 native
    // round-trip per window instead of a hot spin.
    private static final long POLL_HOLD_MS = 25000L;

    private static final Object LOCK = new Object();
    @Nullable
    private static volatile WebExtension sExtension;
    private static volatile boolean sInstallStarted;
    private static final java.util.List<GeckoSession> sPendingSessions =
            new java.util.ArrayList<>();
    private static final Set<GeckoSession> sBoundSessions =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final ScheduledExecutorService sTimeouts =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sinytra-js-timeout");
                t.setDaemon(true);
                return t;
            });

    public interface EvalCallback {
        void onResult(@Nullable String jsonEncoded);
    }

    public interface PageEventListener {
        void onPageEvent(@NonNull JSONObject event);
    }

    private final Set<PageEventListener> mPageEventListeners =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void addPageEventListener(@NonNull PageEventListener listener) {
        mPageEventListeners.add(listener);
    }

    public void removePageEventListener(@NonNull PageEventListener listener) {
        mPageEventListeners.remove(listener);
    }

    /** @deprecated multiplexed fan-out: use {@link #addPageEventListener}. */
    @Deprecated
    public void setPageEventListener(@Nullable PageEventListener listener) {
        mPageEventListeners.clear();
        if (listener != null) {
            mPageEventListeners.add(listener);
        }
    }

    private static final class Pending {
        @NonNull
        final EvalCallback callback;
        @Nullable
        volatile ScheduledFuture<?> timeout;

        Pending(@NonNull EvalCallback callback) {
            this.callback = callback;
        }
    }

    // One queued app->page request. The wire form is a JSONObject with
    // {kind, id, ...fields}; the content script forwards it into the
    // page shim verbatim (minus nothing) and posts the shim's answer
    // back as {kind:"result", id, ok, value, error}.
    private static final class QueuedRequest {
        final int id;
        @NonNull
        final String kind;
        @NonNull
        final JSONObject payload;

        QueuedRequest(int id, @NonNull String kind,
                @NonNull JSONObject payload) {
            this.id = id;
            this.kind = kind;
            this.payload = payload;
        }
    }

    // A held content poll: the GeckoResult<Object> returned from
    // onMessage for {kind:"poll"}. Completing it with a request payload
    // delivers the payload as the sendNativeMessage resolution.
    private static final class HeldPoll {
        @NonNull
        final GeckoResult<Object> result;
        @Nullable
        volatile ScheduledFuture<?> noopTimer;
        volatile long gen;

        HeldPoll(@NonNull GeckoResult<Object> result) {
            this.result = result;
        }
    }

    private final AtomicInteger mNextId = new AtomicInteger(1);
    private final Map<Integer, Pending> mPending = new ConcurrentHashMap<>();
    // Session mailbox: FIFO of app->page requests awaiting a content poll.
    // Guarded by itself; drained only on the Gecko delivery thread
    // (onMessage) and appended from any thread.
    private final Deque<QueuedRequest> mMailbox = new ArrayDeque<>();
    // At most one held poll per session: the content script keeps exactly
    // one poll in flight (it re-polls on every resolution).
    @Nullable
    private volatile HeldPoll mHeldPoll;
    // Live document generation: the gen of the most recent content-ready
    // event. holdPoll/wakeHeldPoll answer queued page-script requests
    // only to polls from this generation; older generations get noop so
    // dead documents never run script after navigation.
    private volatile long mCurrentGen;
    @Nullable
    private volatile GeckoSession mSession;

    public JsBridge() {
    }

    public void bind(@NonNull GeckoRuntime runtime, @NonNull GeckoSession session) {
        mSession = session;
        sOwners.put(session, this);
        if (sBoundSessions.contains(session)) {
            return;
        }
        Log.i(TAG, "bind: session=" + session + " installing extension");
        ensureInstalled(runtime, session);
    }

    // Ready to round-trip: extension installed + this session's delegate
    // bound. (No Port needed anymore -- mailbox+poll carries app->page.)
    public boolean isReady() {
        return sExtension != null && mSession != null
                && sBoundSessions.contains(mSession);
    }

    public void evaluate(@NonNull String script, @NonNull EvalCallback callback) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("script", script);
            postRequest("eval", payload, callback);
        } catch (Throwable t) {
            Log.w(TAG, "evaluate payload threw", t);
            callback.onResult(null);
        }
    }

    public void registerInterface(@NonNull String iface,
            @NonNull java.util.List<String> methods,
            @NonNull EvalCallback callback) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("iface", iface);
            payload.put("methods", new JSONArray(methods));
            postRequest("register", payload, callback);
        } catch (Throwable t) {
            Log.w(TAG, "register payload threw", t);
            callback.onResult(null);
        }
    }

    public void unregisterInterface(@NonNull String iface,
            @NonNull EvalCallback callback) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("iface", iface);
            postRequest("unregister", payload, callback);
        } catch (Throwable t) {
            Log.w(TAG, "unregister payload threw", t);
            callback.onResult(null);
        }
    }

    public void answerStubCall(@NonNull String callId, boolean ok,
            @Nullable String jsonValue, @Nullable String error) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("callId", callId);
            payload.put("ok", ok);
            payload.put("value", jsonValue != null ? jsonValue : JSONObject.NULL);
            payload.put("error", error != null ? error : JSONObject.NULL);
            postRequest("stub-result", payload, value -> {
            });
        } catch (Throwable t) {
            Log.w(TAG, "answerStubCall threw", t);
        }
    }

    public void postToPage(@NonNull String portId, @NonNull String data,
            @Nullable String origin) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("port", portId);
            payload.put("data", data);
            payload.put("origin", origin != null ? origin : JSONObject.NULL);
            postRequest("port", payload, value -> {
            });
        } catch (Throwable t) {
            Log.w(TAG, "postToPage threw", t);
        }
    }

    // Enqueue an app->page request and wake a held poll if one is waiting.
    // Never blocks, never throws to the caller: without transport the
    // callback answers honest-null immediately; with transport the
    // EVAL_TIMEOUT_MS timer answers honest-null if no content reply
    // arrives in time. On timeout the request is REMOVED from the
    // mailbox so a later poll never picks up a dead id (stale wakeups
    // would otherwise complete a dead generation's poll while the live
    // document waits).
    private void postRequest(@NonNull String kind, @NonNull JSONObject fields,
            @NonNull EvalCallback callback) {
        if (sExtension == null || mSession == null
                || !sBoundSessions.contains(mSession)) {
            Log.w(TAG, kind + " before transport ready: honest-null");
            callback.onResult(null);
            return;
        }
        int id = mNextId.getAndIncrement();
        Pending pending = new Pending(callback);
        mPending.put(id, pending);
        pending.timeout = sTimeouts.schedule(() -> {
            Pending dropped = mPending.remove(id);
            if (dropped != null) {
                removeFromMailbox(id);
                Log.w(TAG, kind + " timeout id=" + id);
                try {
                    dropped.callback.onResult(null);
                } catch (Throwable t) {
                    Log.w(TAG, "timeout callback threw", t);
                }
            }
        }, EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try {
            JSONObject wire = new JSONObject();
            wire.put("kind", kind);
            wire.put("id", id);
            java.util.Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                wire.put(key, fields.get(key));
            }
            synchronized (mMailbox) {
                mMailbox.addLast(new QueuedRequest(id, kind, wire));
            }
            wakeHeldPoll();
        } catch (Throwable t) {
            Log.w(TAG, kind + " enqueue threw", t);
            mPending.remove(id);
            ScheduledFuture<?> timeout = pending.timeout;
            if (timeout != null) {
                timeout.cancel(false);
            }
            callback.onResult(null);
        }
    }

    private void removeFromMailbox(int id) {
        synchronized (mMailbox) {
            mMailbox.removeIf(req -> req.id == id);
        }
    }

    // Hand the oldest queued request to a waiting content poll, if both
    // exist. Runs on the Gecko delivery thread or the enqueueing thread;
    // the poll result is completed exactly once (held ref cleared first).
    // Generation gate: the held poll belongs to the document generation
    // that opened it (held.gen). If a navigation promoted a NEWER live
    // generation since (mCurrentGen advanced via content-ready), this
    // stale wake must NOT complete the old result -- its context is dead
    // and completing it resolves into the void while the live document
    // waits. Re-queue at the head for the live generation's poll.
    private void wakeHeldPoll() {
        HeldPoll held = mHeldPoll;
        if (held == null) {
            return;
        }
        if (held.gen != mCurrentGen) {
            // Stale generation: leave the mailbox for the live poll;
            // noop the dead one so its context settles.
            mHeldPoll = null;
            ScheduledFuture<?> timer = held.noopTimer;
            if (timer != null) {
                timer.cancel(false);
            }
            try {
                held.result.complete(noopPayloadString());
            } catch (Throwable ignored) {
            }
            return;
        }
        QueuedRequest next;
        synchronized (mMailbox) {
            next = mMailbox.pollFirst();
        }
        if (next == null) {
            return;
        }
        if (!mPending.containsKey(next.id)) {
            // Timed out between enqueue and wake: drop, try the next one.
            wakeHeldPoll();
            return;
        }
        if (mHeldPoll != held) {
            // Replaced while working: put the request back.
            synchronized (mMailbox) {
                mMailbox.addFirst(next);
            }
            return;
        }
        mHeldPoll = null;
        ScheduledFuture<?> noopTimer = held.noopTimer;
        if (noopTimer != null) {
            noopTimer.cancel(false);
        }
        try {
            held.result.complete(next.payload.toString());
        } catch (Throwable t) {
            Log.w(TAG, "held poll complete threw", t);
            synchronized (mMailbox) {
                mMailbox.addFirst(next);
            }
        }
    }

    private void ensureInstalled(@NonNull GeckoRuntime runtime,
            @NonNull GeckoSession session) {
        WebExtension installed = sExtension;
        if (installed != null) {
            bindSessionDelegate(session, installed);
            return;
        }
        synchronized (LOCK) {
            installed = sExtension;
            if (installed != null) {
                bindSessionDelegate(session, installed);
                return;
            }
            if (sInstallStarted) {
                sPendingSessions.add(session);
                return;
            }
            sInstallStarted = true;
            sPendingSessions.add(session);
        }
        try {
            runtime.getWebExtensionController()
                    .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                    .accept(ext -> {
                        Log.i(TAG, "ensureBuiltIn installed: id=" + ext.id
                                + " location=" + ext.location);
                        synchronized (LOCK) {
                            sExtension = ext;
                        }
                        onExtensionReady(ext);
                    }, e -> {
                        synchronized (LOCK) {
                            sInstallStarted = false;
                            sPendingSessions.clear();
                        }
                        Log.w(TAG, "ensureBuiltIn failed: honest-null until retry", e);
                    });
        } catch (Throwable t) {
            synchronized (LOCK) {
                sInstallStarted = false;
                sPendingSessions.clear();
            }
            Log.w(TAG, "ensureBuiltIn threw", t);
        }
    }

    private static void onExtensionReady(@NonNull WebExtension ext) {
        java.util.List<GeckoSession> sessions;
        synchronized (LOCK) {
            sessions = new java.util.ArrayList<>(sPendingSessions);
            sPendingSessions.clear();
        }
        // No runtime delegate needed: all traffic is per-session
        // (content poll/result/event via SessionController delegates).
        // The background page is a static relay with no Java contact.
        for (GeckoSession session : sessions) {
            try {
                bindSessionDelegate(session, ext);
            } catch (Throwable t) {
                Log.w(TAG, "bindSessionDelegate threw", t);
            }
        }
    }

    private static void bindSessionDelegate(@NonNull GeckoSession session,
            @NonNull WebExtension ext) {
        if (sBoundSessions.contains(session)) {
            return;
        }
        try {
            session.getWebExtensionController().setMessageDelegate(ext,
                    new WebExtension.MessageDelegate() {
                        @Nullable
                        @Override
                        public GeckoResult<Object> onMessage(
                                @NonNull String nativeApp,
                                @NonNull Object message,
                                @NonNull WebExtension.MessageSender sender) {
                            JsBridge owner = findOwner(session);
                            if (owner != null) {
                                return owner.handleSessionMessage(message);
                            }
                            return null;
                        }

                        @Override
                        public void onConnect(@NonNull WebExtension.Port port) {
                            // Content scripts in this build use one-off
                            // sendNativeMessage only; a native Port open
                            // from content has nothing to deliver into --
                            // disconnect so no half-channel lingers.
                            try {
                                port.disconnect();
                            } catch (Throwable ignored) {
                            }
                        }
                    }, NATIVE_APP);
            sBoundSessions.add(session);
            Log.i(TAG, "session delegate bound: " + session);
        } catch (Throwable t) {
            Log.w(TAG, "setMessageDelegate(session) threw", t);
        }
    }

    private static final Map<GeckoSession, JsBridge> sOwners =
            new ConcurrentHashMap<>();

    private static JsBridge findOwner(@NonNull GeckoSession session) {
        return sOwners.get(session);
    }

    // Session content traffic. Returns a pending GeckoResult for polls
    // (completed later with a request, or with noop after POLL_HOLD_MS),
    // null for everything else (fire-and-forget result/event).
    @Nullable
    private GeckoResult<Object> handleSessionMessage(@NonNull Object message) {
        try {
            JSONObject obj = toJson(message);
            if (obj == null) {
                return null;
            }
            String kind = obj.optString("kind", "");
            if ("poll".equals(kind)) {
                return holdPoll(obj.optLong("gen", 0));
            }
            if ("result".equals(kind)) {
                onResult(obj);
                return null;
            }
            if ("event".equals(kind)) {
                onEvent(obj);
                return null;
            }
            Log.w(TAG, "unknown session message kind: " + kind);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "handleSessionMessage threw", t);
            return null;
        }
    }

    // A content poll arrived: answer immediately if the mailbox is
    // non-empty, else hold the result until a request lands or
    // POLL_HOLD_MS elapses (then answer noop so the content script
    // re-polls instead of hanging forever). Values completed into the
    // GeckoResult are marshalled by JNI EventCallback::Resolve
    // (libxul "Invalid event data for %s" on type mismatch), so they
    // must be plain JSON-compatible Strings -- never a bare JSONObject
    // (whose peer is a hand-built bundle the native marshaller
    // rejects). Hence every completion below passes a JSON STRING.
    //
    // GENERATION CONTRACT with wakeHeldPoll: holdPoll answers a queued
    // request immediately ONLY when the poll's generation is still the
    // live one (gen == mCurrentGen). Otherwise the poll belongs to a
    // document that already navigated away: answer noop so the dead
    // context settles instead of running page script in a document the
    // user has left. wakeHeldPoll applies the same gate before
    // completing a held poll.
    @NonNull
    private GeckoResult<Object> holdPoll(long gen) {
        if (gen != mCurrentGen) {
            return GeckoResult.fromValue((Object) noopPayloadString());
        }
        QueuedRequest next;
        synchronized (mMailbox) {
            next = mMailbox.pollFirst();
            while (next != null && !mPending.containsKey(next.id)) {
                // Stale (timed-out) head: drop it, look at the next one.
                next = mMailbox.pollFirst();
            }
        }
        if (next != null) {
            return GeckoResult.fromValue(
                    (Object) next.payload.toString());
        }
        // Replace any previously held poll (content keeps exactly one in
        // flight, but a stale duplicate must never leak a result that
        // never completes: answer the old one noop).
        HeldPoll previous = mHeldPoll;
        mHeldPoll = null;
        if (previous != null) {
            ScheduledFuture<?> timer = previous.noopTimer;
            if (timer != null) {
                timer.cancel(false);
            }
            try {
                previous.result.complete(noopPayloadString());
            } catch (Throwable ignored) {
            }
        }
        GeckoResult<Object> result = new GeckoResult<>();
        HeldPoll held = new HeldPoll(result);
        held.gen = gen;
        mHeldPoll = held;
        held.noopTimer = sTimeouts.schedule(() -> {
            if (mHeldPoll == held) {
                mHeldPoll = null;
                try {
                    result.complete(noopPayloadString());
                } catch (Throwable ignored) {
                }
            }
        }, POLL_HOLD_MS, TimeUnit.MILLISECONDS);
        return result;
    }

    @NonNull
    private static String noopPayloadString() {
        return "{\"kind\":\"noop\",\"id\":0}";
    }

    private void onResult(@NonNull JSONObject obj) {
        int id = obj.optInt("id", -1);
        Pending pending = mPending.remove(id);
        if (pending == null) {
            return;
        }
        ScheduledFuture<?> timeout = pending.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
        // The request left the mailbox when handed to the poll; a retry
        // or duplicate answer must not resolve twice (remove is idempotent).
        boolean ok = obj.optBoolean("ok", false);
        String value = null;
        if (ok && !obj.isNull("value")) {
            value = obj.optString("value", null);
        }
        final String result = value;
        try {
            pending.callback.onResult(result);
        } catch (Throwable t) {
            Log.w(TAG, "eval callback threw", t);
        }
    }

    private void onEvent(@NonNull JSONObject obj) {
        // content-ready carries the poll generation of a (new) document:
        // promote it to mCurrentGen BEFORE fanning out, so any poll from
        // this generation queued behind the event is treated as live.
        if ("content-ready".equals(obj.optString("name", ""))) {
            JSONObject payload = obj.optJSONObject("payload");
            long gen = payload != null ? payload.optLong("gen", 0) : 0;
            if (gen != 0) {
                mCurrentGen = gen;
            }
        }
        for (PageEventListener listener : mPageEventListeners) {
            try {
                listener.onPageEvent(obj);
            } catch (Throwable t) {
                Log.w(TAG, "page event listener threw", t);
            }
        }
    }

    @Nullable
    private static JSONObject toJson(@NonNull Object message) {
        if (message instanceof JSONObject) {
            return (JSONObject) message;
        }
        if (message instanceof String) {
            try {
                return new JSONObject((String) message);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}
