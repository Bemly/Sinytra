// Sinytra JS bridge: content script (isolated world, runs at
// document_start on every frame's top document, matches <all_urls>).
//
// Sits between the page-world shim (page-shim.js, injected as a <script>
// so it runs in MAIN world with page privileges) and the Java side
// (JsBridge, over native messaging "sinytra").
//
// Transport: mailbox + poll (NO background relay, NO Port posts
// app->page). Java Port.postMessage loops back to Java (parent routes
// PortMessageFromApp to the Java-side conduit, never to extension
// pages -- 7真机 rounds proved it), and Java has no one-off-send
// primitive. So the content script POLLS: it sendNativeMessages
// {kind:"poll"} and Java answers the poll's GeckoResult with the next
// queued app->page request (or {kind:"noop"} when the mailbox is
// empty). The script runs the request in the page shim, then
// sendNativeMessages {kind:"result"} back fire-and-forget. Page->Java
// events (interface stub calls, port replies) go up the same way as
// {kind:"event"} one-offs.
//
// Poll protocol (content -> Java, native message):
//   {kind:"poll"}
// Java answers the poll's GeckoResult with exactly one of:
//   {kind:"eval", id:<int>, script:<string>}
//   {kind:"stub-result", callId:<string>, ok:<bool>, value, error}
//   {kind:"port", id:<int>, port:<string>, data:<string>,
//      origin:<string|null>}
//   {kind:"register", id:<int>, iface:<string>, methods:<string[]>}
//   {kind:"unregister", id:<int>, iface:<string>}
//   {kind:"noop", id:0}   (mailbox empty: re-poll immediately)
//
// Content -> Java (native message, fire-and-forget, reply ignored):
//   {kind:"result", id:<int>, ok:<bool>, value:<json|null>,
//      error:<string|null>}
//   {kind:"event", name:<string>, payload:<object>}
//      (content-ready/shim-state pings, iface-call from a page stub,
//      port-deliver from the shim)
//
// Content <-> shim (window.postMessage, same document):
//   -> shim: {dir:"sinytra-req", id:<int|string>, kind, ...}
//   <- shim: {dir:"sinytra-res", id, ok, value, error}
//   <- shim: {dir:"sinytra-event", name, payload}
//
// Why two hops: content scripts run in an isolated JS world -- they can
// touch the DOM but NOT the page's window objects, so eval and interface
// stubs must live in a MAIN-world <script>. The content script only
// ferries messages and never executes page script itself.

"use strict";

const NATIVE_APP = "sinytra";

// Single in-flight poll invariant: exactly one {kind:"poll"}
// sendNativeMessage is outstanding at any time. poll() sends it;
// every settlement path (request / noop / error) re-invokes poll(),
// so the loop is self-perpetuating and never stacks.
//
// Document generation: every navigation spawns a FRESH content-script
// context (fresh heap, same document). A Java-held poll belongs to the
// OLD document once navigation commits -- the old context unloads and
// its promise rejects with "context is inactive/unloaded". Tag each
// poll with a per-context id; Java echoes it on the answer and drops
// polls from a previous generation instead of completing a dead
// promise (whose completion throws "Invalid event data for callback"
// parent-side). On navigation the old loop dies with its context; the
// new context starts a new loop with a new generation.
let pollInFlight = false;
let pollGeneration = 0;
try {
  pollGeneration = (Date.now() % 1000000) * 1000
    + Math.floor(Math.random() * 1000);
} catch (e) {
  pollGeneration += 1;
}

function sendToApp(obj) {
  try {
    browser.runtime.sendNativeMessage(NATIVE_APP, obj).catch(() => {});
  } catch (e) {
  }
}

function poll() {
  if (pollInFlight) {
    return;
  }
  pollInFlight = true;
  let answered = false;
  const settle = (fn) => {
    if (answered) {
      return;
    }
    answered = true;
    pollInFlight = false;
    try {
      fn();
    } finally {
      // Re-poll on the next task, NOT synchronously: a synchronous
      // re-poll inside the promise callback can complete the next poll
      // inside the same native round-trip, which the parent reports as
      // "Invalid event data for callback".
      setTimeout(poll, 0);
    }
  };
  let request;
  try {
    request = browser.runtime.sendNativeMessage(NATIVE_APP,
      {kind: "poll", gen: pollGeneration});
  } catch (e) {
    settle(() => {});
    return;
  }
  if (!request || typeof request.then !== "function") {
    settle(() => {});
    return;
  }
  request.then(
    (msg) => settle(() => onPollAnswer(msg)),
    // Rejection has two benign causes, both handled by re-polling:
    //  - navigation committed mid-poll (old context unloaded): this
    //    context is dead; the re-poll below throws/catches silently and
    //    the NEW context's loop takes over. No retry storm: the dead
    //    context cannot schedule work, its timer dies with it.
    //  - extension re-install (version bump): conduit torn down;
    //    re-poll re-establishes on the new conduit.
    () => settle(() => {}));
}

// A poll answer arrived (or the mailbox was empty / the poll timed out
// Java-side with noop): run it, then re-poll via settle(). Answers
// arrive as JSON STRINGS (Java completes the poll's GeckoResult with a
// String -- the only value shape that survives the parent->child
// conduit response intact); parse defensively, ignore garbage.
function onPollAnswer(raw) {
  let msg = raw;
  if (typeof msg === "string") {
    try {
      msg = JSON.parse(msg);
    } catch (e) {
      return;
    }
  }
  if (!msg || typeof msg.kind !== "string") {
    return;
  }
  if (msg.kind === "noop") {
    return;
  }
  if (msg.kind === "stub-result"
    && typeof msg.callId === "string") {
    window.postMessage(
      {dir: "sinytra-res", id: msg.callId,
        ok: !!msg.ok,
        value: msg.value === undefined ? null : msg.value,
        error: msg.error === undefined ? null : msg.error},
      "*");
    pendingCalls.delete(msg.callId);
    return;
  }
  if (typeof msg.id !== "number") {
    return;
  }
  const req = {dir: "sinytra-req", id: msg.id, kind: msg.kind};
  for (const k of ["script", "iface", "method", "args", "port", "data",
    "origin", "methods", "callId", "ok", "value", "error"]) {
    if (msg[k] !== undefined) {
      req[k] = msg[k];
    }
  }
  window.postMessage(req, "*");
}

// Startup ping: proves the content script is injected and the
// content->Java upward path works; then the poll loop starts. The ping
// carries this document's poll generation so Java can gate page-script
// answers to the live document (see JsBridge mCurrentGen).
try {
  sendToApp({kind: "event", name: "content-ready",
    payload: {url: String(location.href), gen: pollGeneration}});
} catch (e) {
}
poll();

// Numeric request ids (from Java) -> nothing stored here: the shim
// answers synchronously over window.postMessage and we forward the
// answer straight to the app. Only iface-call round-trips need
// correlation: the shim emits sinytra-event {name:"iface-call",
// payload:{callId,...}} and Java answers with a stub-result poll
// payload that we route back into the shim above.
const pendingCalls = new Map();

// Inject the page-world shim exactly once per document. NOTE: content
// scripts run in an ISOLATED world -- window flags set here are
// invisible to page-shim.js, and page-shim.js's window.__sinytraShim
// is invisible here. Cross-world signalling uses ONLY window.postMessage
// (shim announces itself with {dir:"sinytra-hello"}) and the DOM marker
// attribute below (shared document, visible from both worlds).
const SHIM_PING_MS = 500;
const SHIM_PING_ROUNDS = 4;
let shimPresent = false;
let shimPingsSent = 0;

function announceShimQuery() {
  window.postMessage({dir: "sinytra-hello-query"}, "*");
}

function ensureShim() {
  if (shimPresent) {
    return;
  }
  try {
    const marker = document.documentElement
      ? document.documentElement.getAttribute("data-sinytra-shim")
      : null;
    if (marker === "ready") {
      announceShimQuery();
      return;
    }
    const script = document.createElement("script");
    script.setAttribute("data-sinytra-shim-script", "1");
    script.src = browser.runtime.getURL("page-shim.js");
    script.onload = function () {
      script.remove();
      announceShimQuery();
    };
    script.onerror = function () {
      try {
        sendToApp({kind: "event", name: "shim-state",
          payload: {state: "script-onerror"}});
      } catch (e) {
      }
    };
    (document.head || document.documentElement).appendChild(script);
    if (document.documentElement) {
      document.documentElement.setAttribute("data-sinytra-shim",
        "injected");
    }
  } catch (e) {
    try {
      sendToApp({kind: "event", name: "shim-state",
        payload: {state: "inject-threw:" + String((e && e.message) || e)}});
    } catch (ignored) {
    }
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", ensureShim);
}
ensureShim();
const shimTimer = setInterval(() => {
  if (shimPresent) {
    clearInterval(shimTimer);
    return;
  }
  shimPingsSent += 1;
  if (shimPingsSent > SHIM_PING_ROUNDS) {
    clearInterval(shimTimer);
    try {
      sendToApp({kind: "event", name: "shim-state",
        payload: {state: "no-hello-after-retries"}});
    } catch (e) {
    }
    return;
  }
  announceShimQuery();
  ensureShim();
}, SHIM_PING_MS);

// Shim -> content: forward results and events to the app.
window.addEventListener("message", (event) => {
  if (event.source !== window) {
    return;
  }
  const d = event.data;
  if (!d || typeof d.dir !== "string") {
    return;
  }
  if (d.dir === "sinytra-hello") {
    shimPresent = true;
    try {
      if (document.documentElement) {
        document.documentElement.setAttribute("data-sinytra-shim",
          "ready");
      }
    } catch (e) {
    }
    return;
  }
  if (d.dir === "sinytra-res") {
    if (typeof d.id === "string" && pendingCalls.has(d.id)) {
      const callId = d.id;
      pendingCalls.delete(callId);
      window.postMessage(
        {dir: "sinytra-req", id: callId, kind: "stub-res",
          ok: d.ok, value: d.value, error: d.error},
        "*");
      return;
    }
    sendToApp({kind: "result", id: d.id, ok: !!d.ok,
      value: d.value === undefined ? null : d.value,
      error: d.error === undefined ? null : d.error});
    return;
  }
  if (d.dir === "sinytra-event") {
    if (d.name === "iface-call" && d.payload && d.payload.callId) {
      pendingCalls.set(d.payload.callId, true);
    }
    sendToApp({kind: "event", name: d.name, payload: d.payload || {}});
    return;
  }
  if (d.dir === "sinytra-port-deliver") {
    sendToApp({kind: "event", name: "port-deliver",
      payload: {port: d.port, data: d.data, origin: d.origin || ""}});
  }
});
