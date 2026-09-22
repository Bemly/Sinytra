// Sinytra JS bridge: background script (privileged built-in extension).
// Currently a no-op placeholder: all transport is mailbox + poll
// between content.js and Java (JsBridge), with no background relay.
//
// Why no relay: Java Port.postMessage loops back to Java (parent routes
// PortMessageFromApp via castPortMessage("port", {source:true}) to the
// Java-side EmbedderPort conduit itself; the background page never sees
// it -- 7真机 rounds proved it), and Java has no one-off-send primitive
// to wake the background page. So the background page cannot forward
// anything: content.js polls Java directly via runtime.sendNativeMessage
// {kind:"poll"}, answered through the poll's GeckoResult.
//
// Kept (rather than removed from manifest.json) so the extension keeps
// a background page context: connectNative here gives Java a live Port
// object (MessageDelegate.onConnect fires), which isReady() uses as a
// liveness signal alongside the session delegate binding. The Port is
// never posted through app->page.
"use strict";

try {
  browser.runtime.connectNative("sinytra");
} catch (e) {
}
