package org.mozilla.geckowebview.provider;

import android.content.Intent;
import android.net.Uri;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import org.mozilla.geckoview.GeckoSession;

// Framework token objects handed to app callbacks: PermissionRequest,
// FileChooserParams, reflective HttpAuthHandler/JsResult factories.
// Split out of GeckoWebViewProvider (AGENTS.md §7 file-size rule).
final class ProviderAdapters {
    private ProviderAdapters() {}

    static final class SinytraPermissionRequest extends PermissionRequest {
        private final String mOrigin;

        SinytraPermissionRequest(String origin) {
            mOrigin = origin;
        }

        @Override
        public Uri getOrigin() {
            try {
                return Uri.parse(mOrigin);
            } catch (Throwable t) {
                return Uri.EMPTY;
            }
        }

        @Override
        public String[] getResources() {
            return new String[0];
        }

        @Override
        public void grant(String[] resources) {
        }

        @Override
        public void deny() {
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

    static final class SinytraClientCertRequest
            extends android.webkit.ClientCertRequest {
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
}
