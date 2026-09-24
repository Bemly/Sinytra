package org.mozilla.geckowebview.provider;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoSession;

// Framework token objects handed to app callbacks: PermissionRequest,
// FileChooserParams, reflective HttpAuthHandler/JsResult factories.
// Split out of GeckoWebViewProvider (AGENTS.md §7 file-size rule).
final class ProviderAdapters {
    private ProviderAdapters() {}

    // Framework-typed PermissionRequest for media (camera/mic) prompts.
    // This is the ONLY PermissionRequest surface Chromium WebView exposes
    // (onPermissionRequest is device-capture). grant()/deny() route the
    // app's decision back into the Gecko MediaCallback — before P1 this
    // was an empty shell and a granted permission left the page hanging.
    static final class SinytraMediaPermissionRequest extends PermissionRequest {
        private final Uri mOrigin;
        private final GeckoSession.PermissionDelegate.MediaSource[] mVideo;
        private final GeckoSession.PermissionDelegate.MediaSource[] mAudio;
        private final GeckoSession.PermissionDelegate.MediaCallback mCallback;

        SinytraMediaPermissionRequest(String origin,
                GeckoSession.PermissionDelegate.MediaSource[] video,
                GeckoSession.PermissionDelegate.MediaSource[] audio,
                GeckoSession.PermissionDelegate.MediaCallback callback) {
            mOrigin = Uri.parse(origin);
            mVideo = video;
            mAudio = audio;
            mCallback = callback;
        }

        @Override
        public Uri getOrigin() {
            try {
                return mOrigin;
            } catch (Throwable t) {
                return Uri.EMPTY;
            }
        }

        @Override
        public String[] getResources() {
            // Chromium resource contract: VIDEO/AUDIO_CAPTURE keys.
            boolean wantVideo = mVideo.length > 0;
            boolean wantAudio = mAudio.length > 0;
            if (wantVideo && wantAudio) {
                return new String[] {PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE};
            }
            if (wantVideo) {
                return new String[] {PermissionRequest.RESOURCE_VIDEO_CAPTURE};
            }
            if (wantAudio) {
                return new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE};
            }
            return new String[0];
        }

        @Override
        public void grant(String[] resources) {
            boolean video = false;
            boolean audio = false;
            if (resources != null) {
                for (String r : resources) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                        video = true;
                    } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE
                            .equals(r)) {
                        audio = true;
                    }
                }
            }
            try {
                if (video || audio) {
                    mCallback.grant(
                            video && mVideo.length > 0 ? mVideo[0] : null,
                            audio && mAudio.length > 0 ? mAudio[0] : null);
                } else {
                    mCallback.reject();
                }
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/permission", "media grant routing threw", t);
            }
        }

        @Override
        public void deny() {
            try {
                mCallback.reject();
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/permission", "media deny routing threw", t);
            }
        }
    }

    static final class SinytraFileChooserParams extends WebChromeClient.FileChooserParams {
        private final GeckoSession.PromptDelegate.FilePrompt mPrompt;

        SinytraFileChooserParams(GeckoSession.PromptDelegate.FilePrompt prompt) {
            mPrompt = prompt;
        }

        @Override
        public int getMode() {
            return mPrompt.type
                    == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
                    ? MODE_OPEN_MULTIPLE : MODE_OPEN;
        }

        @Override
        public String[] getAcceptTypes() {
            return mPrompt.mimeTypes != null ? mPrompt.mimeTypes : new String[0];
        }

        @Override
        public boolean isCaptureEnabled() {
            return false;
        }

        @Override
        public CharSequence getTitle() {
            return mPrompt.title != null ? mPrompt.title : "";
        }

        @Override
        public String getFilenameHint() {
            return "";
        }

        @Override
        public Intent createIntent() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            String[] types = getAcceptTypes();
            if (types.length == 1) {
                intent.setType(types[0]);
            } else {
                intent.setType("*/*");
                if (types.length > 1) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                }
            }
            if (getMode() == MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            return intent;
        }
    }

    static final class SinytraClientCertRequest extends android.webkit.ClientCertRequest {
        @Override
        public String[] getKeyTypes() {
            return new String[0];
        }

        @Override
        public java.security.Principal[] getPrincipals() {
            return new java.security.Principal[0];
        }

        @Override
        public String getHost() {
            return "";
        }

        @Override
        public int getPort() {
            return -1;
        }

        @Override
        public void proceed(java.security.PrivateKey privateKey,
                java.security.cert.X509Certificate[] chain) {
        }

        @Override
        public void ignore() {
        }

        @Override
        public void cancel() {
        }
    }

    // Framework-typed WebMessagePort over a MessageBridge.Port.
    //
    // DESIGN RECORD (2026-09-22, javac-verified): the framework ctor is
    // package-private (android14 javap shows `WebMessagePort()` with no
    // access modifier), so subclassing from org.mozilla.* does NOT
    // compile ("not public, cannot be accessed from outside package").
    // PermissionRequest/RenderProcess subclassing compiles ONLY because
    // those ctors are public; WebMessagePort is the odd one out.
    // Chromium subclasses INSIDE android.webkit (WebMessagePortImpl) --
    // we cannot ship an android.webkit class from the provider APK
    // without colliding with the framework, and framework-stubs is
    // compileOnly (never packaged, never on the device). Reflection on
    // the package-private ctor ALSO fails on-device (hidden-API
    // enforcement: InstantiationException on the abstract class path,
    // verified in earlier harness rounds as fwNull=true).
    //
    // Therefore: NO concrete WebMessagePort exists in this build.
    // createWebMessageChannel returns real MessageBridge ports through
    // the boundary interface (CompatSmallBoundaries.LiveMessagePort,
    // which IS functional for androidx.webkit clients), while the
    // framework-typed createWebMessageChannel keeps its honest null +
    // loud log until ONE of these lands: (a) an AOSP patch making the
    // ctor public/hidden-API-allowlisted, or (b) a framework-side
    // WebMessagePortFactory hook. Option (b) is the P2 patch queue item
    // below; this factory documents the decision point.
    //
    // Entanglement today: MessageBridge.createChannel pairs ports;
    // postMessage routes into the JsBridge transport toward the page
    // shim; boundary clients get full function via LiveMessagePort.
    static final class WebMessagePortFactory {
        private WebMessagePortFactory() {}
    }
}
