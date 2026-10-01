// Sinytra JS bridge: page-world shim (MAIN world, injected by content.js).
// Runs with full page privileges: sees the page's own window objects
// (including addJavascriptInterface registrations) and can synchronously
// eval in page context. Talks to content.js (isolated world) only
// through the DOM attribute mailbox (never window.postMessage: every
// window message also fires the page's own onmessage, so markers would
// leak into page code as "[object Object]"). See content.js header for
// the mailbox design. Only postWebMessage delivery below is a genuine
// page-visible MessageEvent, by API contract.
//
// Mailbox shapes (JSON, single-writer queues with seq watermarks):
//
// Content -> shim (attribute "data-sinytra-c2s"):
//   {seq, id:<int>, kind:"eval", script:<string>}
//   {seq, id:<int>, kind:"call",
//      iface:<string>, method:<string>, args:<array>}
//   {seq, id:<int>, kind:"port",
//      port:<string>, data:<string>, origin:<string|null>}
//   {seq, id:<int>, kind:"register",
//      iface:<string>, methods:<string[]>}
//   {seq, id:<int>, kind:"unregister", iface:<string>}
//   {seq, id:<string>, kind:"stub-res", ok, value, error}
//
// Shim -> content (attribute "data-sinytra-s2c"):
//   {seq, id, ok:<bool>, value:<json-string|null>, error:<string|null>}
//   {seq, event:true, name:<string>, payload:<object>}
//   {seq, portDeliver:true, port, data, origin}
// Readiness: attribute "data-sinytra-shim" === "ready".
//
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
  // DOM attribute mailbox (see content.js header): no window.postMessage
  // anywhere in this file except the genuine port delivery below — page
  // onmessage therefore only ever sees real page messages (Chromium
  // parity; CTS PostMessageTest asserts the exact title).
  const C2S_ATTR = "data-sinytra-c2s";
  const S2C_ATTR = "data-sinytra-s2c";
  const READY_ATTR = "data-sinytra-shim";
  // Single-writer queues (the shim owns s2c): each write appends
  // {seq,...}; readers drain every entry above their watermark. A bare
  // last-value mailbox loses messages when two writes land in one task
  // (MutationObserver coalesces them). Observer refs stay reachable so
  // GC cannot silently stop observation.
  const MAX_QUEUE = 50;
  const s2cCounter = {next: 0};
  let c2sSeen = 0;
  let c2sObserver = null;
  const pendingResolves = new Map();

  function queueWrite(obj) {
    try {
      const root = document.documentElement;
      if (!root) {
        return;
      }
      let queue = [];
      if (root.hasAttribute(S2C_ATTR)) {
        try {
          const parsed = JSON.parse(root.getAttribute(S2C_ATTR));
          if (Array.isArray(parsed)) {
            queue = parsed;
          }
        } catch (e) {
        }
      }
      s2cCounter.next += 1;
      obj.seq = s2cCounter.next;
      queue.push(obj);
      while (queue.length > MAX_QUEUE) {
        queue.shift();
      }
      root.setAttribute(S2C_ATTR, JSON.stringify(queue));
    } catch (e) {
    }
  }

  if (window.__sinytraShim) {
    // Already installed (e.g. double injection): refresh the ready
    // marker (the content script observes it) and return.
    try {
      if (document.documentElement) {
        document.documentElement.setAttribute(READY_ATTR, "ready");
      }
    } catch (e) {
    }
    return;
  }

  // iface name -> {method name -> true} for registered Java interfaces.
  const interfaces = new Map();
  // Pending Java->page calls are always answered synchronously below;
  // no pending map needed here. Page->Java calls (stub invocations)
  // are correlated by the content script, which echoes our event id
  // back as a result message.

  function json(value) {
    try {
      return JSON.stringify(value === undefined ? null : value);
    } catch (e) {
      return null;
    }
  }

  function reply(id, ok, value, error) {
    queueWrite({res: true, id, ok: !!ok,
      value: value === undefined ? null : value,
      error: error === undefined ? null : error});
  }

  function emit(name, payload) {
    queueWrite({event: true, name, payload: payload || {}});
  }

  function makeStub(iface, method) {
    return function (...args) {
      return new Promise((resolve, reject) => {
        const callId = "c" + Math.random().toString(36).slice(2)
          + Date.now().toString(36);
        pendingResolves.set(callId, {resolve, reject});
        emit("iface-call",
          {callId, iface, method, args: args || []});
      });
    };
  }

  function resolveStubCall(d) {
    if (typeof d.id !== "string" || !pendingResolves.has(d.id)) {
      return false;
    }
    const pending = pendingResolves.get(d.id);
    pendingResolves.delete(d.id);
    if (d.ok) {
      let v = null;
      try {
        v = d.value !== null ? JSON.parse(d.value) : null;
      } catch (e) {
        v = null;
      }
      pending.resolve(v);
    } else {
      pending.reject(new Error(String(d.error || "java call failed")));
    }
    return true;
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
      queueWrite({portDeliver: true, port: msg.port,
        data: msg.data, origin: msg.origin || ""});
      reply(msg.id, true, json(true), null);
    } catch (e) {
      reply(msg.id, false, null, String((e && e.message) || e));
    }
  }

  // Content-script requests arrive through the DOM attribute mailbox
  // (see content.js header): the two worlds share a document but NOT a
  // JS heap, and window messages would leak into page onmessage. Observe
  // the mailbox (catch-up read covers a request written before we
  // observe); stub-res replies route via pendingResolves below.
  // Entries arrive pre-filtered by queueDrain (seq above watermark).
  function routeMailboxMessage(msg) {
    if (!msg) {
      return;
    }
    if (msg.kind === "stub-res") {
      resolveStubCall(msg);
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
  }

  function observeMailbox() {
    // Catch-up: requests written before we started observing.
    c2sSeen = queueDrain(c2sSeen);
    try {
      const root = document.documentElement;
      if (!root || c2sObserver) {
        return;
      }
      c2sObserver = new MutationObserver(() => {
        c2sSeen = queueDrain(c2sSeen);
      });
      c2sObserver.observe(root, {attributes: true});
    } catch (e) {
    }
  }

  function queueDrain(seen) {
    try {
      const root = document.documentElement;
      if (!root || !root.hasAttribute(C2S_ATTR)) {
        return seen;
      }
      let queue = null;
      try {
        const parsed = JSON.parse(root.getAttribute(C2S_ATTR));
        if (Array.isArray(parsed)) {
          queue = parsed;
        }
      } catch (e) {
      }
      if (!queue) {
        return seen;
      }
      let max = seen;
      for (const entry of queue) {
        if (entry && typeof entry.seq === "number" && entry.seq > seen) {
          if (entry.seq > max) {
            max = entry.seq;
          }
          try {
            routeMailboxMessage(entry);
          } catch (e) {
          }
        }
      }
      return max;
    } catch (e) {
      return seen;
    }
  }

  window.__sinytraShim = {
    register: registerInterface,
    unregister: unregisterInterface,
  };

  // Console hook: forward page console.* to Java as sinytra-event
  // {name:"console", payload:{level, message, line}} (CTS
  // WebChromeClientTest.testOnConsoleMessage asserts text, level and
  // exact line). Originals still run (page behavior unchanged). The
  // caller line comes from the second stack frame (the page call site,
  // not this wrapper); Gecko format "fn@url:line:col".
  if (!window.__sinytraConsoleHooked) {
    window.__sinytraConsoleHooked = true;
    const levels = {log: "log", info: "info", warn: "warn",
      error: "error", debug: "debug"};
    for (const method of Object.keys(levels)) {
      try {
        const original = window.console[method];
        if (typeof original !== "function") {
          continue;
        }
        window.console[method] = function (...args) {
          try {
            let line = 0;
            try {
              const stack = new Error().stack || "";
              const frames = stack.split("\n");
              if (frames.length > 1) {
                const m = frames[1].match(/:(\d+):\d*\s*$/);
                if (m) {
                  line = parseInt(m[1], 10) || 0;
                }
              }
            } catch (e) {
            }
            let message = "";
            try {
              message = args.length > 0 ? String(args[0]) : "";
            } catch (e) {
            }
            emit("console",
              {level: levels[method], message, line});
          } catch (e) {
          }
          return original.apply(window.console, args);
        };
      } catch (e) {
      }
    }
  }

  // Mark ready (the content script observes this attribute — no
  // window message, hence invisible to page onmessage) and start
  // observing the request mailbox.
  try {
    if (document.documentElement) {
      document.documentElement.setAttribute(READY_ATTR, "ready");
    }
  } catch (e) {
  }
  observeMailbox();
})();
