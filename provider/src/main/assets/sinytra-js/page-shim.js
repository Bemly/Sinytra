// Sinytra JS bridge: page-world shim (MAIN world, injected by content.js).
// Runs with full page privileges: sees the page's own window objects
// (including addJavascriptInterface registrations) and can synchronously
// eval in page context. Talks to content.js (isolated world) only through
// window.postMessage with the "sinytra-*" dir markers below.
//
// Protocol (window.postMessage, origin-agnostic — the bridge is internal
// to this extension; a hostile page can already spoof its own DOM):
//
// Content -> shim (request):
//   {dir:"sinytra-req", id:<int>, kind:"eval", script:<string>}
//   {dir:"sinytra-req", id:<int>, kind:"call",
//      iface:<string>, method:<string>, args:<array>}
//   {dir:"sinytra-req", id:<int>, kind:"port",
//      port:<string>, data:<string>, origin:<string|null>}
//   {dir:"sinytra-req", id:<int>, kind:"register",
//      iface:<string>, methods:<string[]>}
//   {dir:"sinytra-req", id:<int>, kind:"unregister", iface:<string>}
//
// Shim -> content (reply, echoes id):
//   {dir:"sinytra-res", id:<int>, ok:<bool>,
//      value:<json-string|null>, error:<string|null>}
//
// Shim -> content (unsolicited: page calls into a registered Java
// interface, or page JS posts back on a WebMessage port):
//   {dir:"sinytra-event", name:<string>, payload:<object>}
//
// Eval runs via window.eval so it executes in page context with access
// to page globals. Return values are JSON-stringified; undefined,
// functions and DOM nodes map to null with ok=true (Chromium parity:
// evaluateJavascript reports JSON-encoded "null").
//
// Java interface stubs: window[iface] = {method: (...args) => Promise}.
// The Promise resolves with the JSON-decoded Java return value. This is
// necessarily ASYNC (native messaging has no synchronous round-trip),
// which diverges from Chromium's synchronous addJavascriptInterface —
// documented, pending a firefox-patch that injects synchronous bindings.
//
// postWebMessage delivery: dispatches a MessageEvent('message',
// {data, origin}) on window, matching what page code listening with
// window.addEventListener('message') expects from Chromium.

"use strict";

(function () {
  if (window.__sinytraShim) {
    // Already installed (e.g. double injection): re-announce so the
    // content script's hello-query resolves even on the second run.
    try {
      window.postMessage({dir: "sinytra-hello"}, "*");
    } catch (e) {
    }
    return;
  }

  // iface name -> {method name -> true} for registered Java interfaces.
  const interfaces = new Map();
  // Pending Java->page calls that went through window.postMessage are
  // always answered synchronously below; no pending map needed here.
  // Page->Java calls (stub invocations) are correlated by the content
  // script, which echoes our event id back as a result message.

  function json(value) {
    try {
      return JSON.stringify(value === undefined ? null : value);
    } catch (e) {
      return null;
    }
  }

  function reply(id, ok, value, error) {
    window.postMessage(
      {dir: "sinytra-res", id, ok: !!ok,
        value: value === undefined ? null : value,
        error: error === undefined ? null : error},
      "*");
  }

  function emit(name, payload) {
    window.postMessage(
      {dir: "sinytra-event", name, payload: payload || {}},
      "*");
  }

  function makeStub(iface, method) {
    return function (...args) {
      return new Promise((resolve, reject) => {
        const callId = "c" + Math.random().toString(36).slice(2)
          + Date.now().toString(36);
        const onRes = (event) => {
          const d = event.data;
          if (!d || d.dir !== "sinytra-res" || d.id !== callId) {
            return;
          }
          window.removeEventListener("message", onRes);
          if (d.ok) {
            let v = null;
            try {
              v = d.value !== null ? JSON.parse(d.value) : null;
            } catch (e) {
              v = null;
            }
            resolve(v);
          } else {
            reject(new Error(String(d.error || "java call failed")));
          }
        };
        window.addEventListener("message", onRes);
        emit("iface-call",
          {callId, iface, method, args: args || []});
      });
    };
  }

  function registerInterface(iface, methods) {
    unregisterInterface(iface);
    const stub = {};
    const table = {};
    for (const m of methods || []) {
      stub[m] = makeStub(iface, m);
      table[m] = true;
    }
    interfaces.set(iface, table);
    try {
      window[iface] = stub;
    } catch (e) {
      // Non-writable global (page defined it first): report, keep table
      // so calls still resolve against the page object where possible.
    }
  }

  function unregisterInterface(iface) {
    interfaces.delete(iface);
    try {
      if (window[iface] && window[iface].__sinytraStub) {
        delete window[iface];
      }
    } catch (e) {
    }
  }

  function handleEval(msg) {
    let value = null;
    try {
      // Indirect eval in page context: sees page globals, caller's
      // strictness does not leak into the evaluated code.
      // eslint-disable-next-line no-eval
      const run = window.eval;
      value = run(msg.script);
      reply(msg.id, true, json(value), null);
    } catch (e) {
      reply(msg.id, false, null, String((e && e.message) || e));
    }
  }

  function handleCall(msg) {
    // Java -> page: invoke window[iface][method](...args). Used by tests
    // and by future WebMessage-adjacent flows; the normal interface
    // direction (page -> Java) goes through stubs + sinytra-event.
    try {
      const holder = window[msg.iface];
      const fn = holder ? holder[msg.method] : undefined;
      if (typeof fn !== "function") {
        reply(msg.id, false, null,
          "no such method: " + msg.iface + "." + msg.method);
        return;
      }
      const out = fn.apply(holder, msg.args || []);
      if (out && typeof out.then === "function") {
        out.then(
          (v) => reply(msg.id, true, json(v), null),
          (e) => reply(msg.id, false, null,
            String((e && e.message) || e)));
      } else {
        reply(msg.id, true, json(out), null);
      }
    } catch (e) {
      reply(msg.id, false, null, String((e && e.message) || e));
    }
  }

  function handlePort(msg) {
    // postWebMessage from the app: deliver to page listeners as a
    // MessageEvent, like Chromium's postMessageToMainFrame.
    try {
      const event = new MessageEvent("message", {
        data: msg.data,
        origin: msg.origin || "",
      });
      try {
        Object.defineProperty(event, "ports", {value: []});
      } catch (e) {
      }
      window.dispatchEvent(event);
      try {
        window.postMessage(
          {dir: "sinytra-port-deliver", port: msg.port,
            data: msg.data, origin: msg.origin || ""},
          "*");
      } catch (e) {
      }
      reply(msg.id, true, json(true), null);
    } catch (e) {
      reply(msg.id, false, null, String((e && e.message) || e));
    }
  }

  // Answer content-script presence queries (see content.js hello
  // protocol): the two worlds share a document but NOT a JS heap, so
  // window.postMessage is the only cross-world signal.
  window.addEventListener("message", (event) => {
    const probe = event.data;
    if (probe && probe.dir === "sinytra-hello-query") {
      try {
        window.postMessage({dir: "sinytra-hello"}, "*");
      } catch (e) {
      }
      return;
    }
    const msg = probe;
    if (!msg || msg.dir !== "sinytra-req") {
      return;
    }
    switch (msg.kind) {
      case "eval":
        handleEval(msg);
        break;
      case "call":
        handleCall(msg);
        break;
      case "port":
        handlePort(msg);
        break;
      case "register":
        try {
          registerInterface(msg.iface, msg.methods || []);
          // Mark so unregister can tell our stub from a page object.
          try {
            Object.defineProperty(window[msg.iface], "__sinytraStub",
              {value: true, configurable: true});
          } catch (e) {
          }
          reply(msg.id, true, json(true), null);
        } catch (e) {
          reply(msg.id, false, null, String((e && e.message) || e));
        }
        break;
      case "unregister":
        try {
          unregisterInterface(msg.iface);
          reply(msg.id, true, json(true), null);
        } catch (e) {
          reply(msg.id, false, null, String((e && e.message) || e));
        }
        break;
      default:
        reply(msg.id, false, null, "unknown kind: " + msg.kind);
        break;
    }
  });

  window.__sinytraShim = {
    register: registerInterface,
    unregister: unregisterInterface,
  };

  // Unprompted announcement: the content script may already be listening
  // (it injects us), so say hello immediately; it also queries on a
  // timer, which the listener above answers.
  try {
    window.postMessage({dir: "sinytra-hello"}, "*");
  } catch (e) {
  }
})();
