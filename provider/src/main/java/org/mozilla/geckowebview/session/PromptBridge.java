package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

// Translates GeckoSession.PromptDelegate file/auth/JS-dialog prompts into
// WebChromeClient/WebViewClient-shaped callbacks. Bridge only: no state.
public class PromptBridge implements GeckoSession.PromptDelegate {
    public interface Host {
        void onFileChooserRequest(
                @NonNull GeckoSession.PromptDelegate.FilePrompt prompt);
        void onHttpAuthRequest(
                @NonNull GeckoSession.PromptDelegate.AuthPrompt prompt);
        void onJsAlert(@NonNull String title, @NonNull String message);
        boolean onJsConfirm(@NonNull String title, @NonNull String message);
        @Nullable
        String onJsPrompt(@NonNull String title, @NonNull String message,
                @Nullable String defaultValue);
    }

    private final Host mHost;

    public PromptBridge(@NonNull Host host) {
        mHost = host;
    }

    @Nullable
    @Override
    public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onFilePrompt(
            @NonNull GeckoSession session,
            @NonNull GeckoSession.PromptDelegate.FilePrompt prompt) {
        try {
            mHost.onFileChooserRequest(prompt);
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/prompt", "Host.onFileChooserRequest threw", t);
        }
        return null;
    }

    @Nullable
    @Override
    public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onAuthPrompt(
            @NonNull GeckoSession session,
            @NonNull GeckoSession.PromptDelegate.AuthPrompt prompt) {
        try {
            mHost.onHttpAuthRequest(prompt);
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/prompt", "Host.onHttpAuthRequest threw", t);
        }
        return null;
    }

    @Nullable
    @Override
    public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onAlertPrompt(
            @NonNull GeckoSession session,
            @NonNull GeckoSession.PromptDelegate.AlertPrompt prompt) {
        try {
            String title = prompt.title != null ? prompt.title : "";
            String message = prompt.message != null ? prompt.message : "";
            mHost.onJsAlert(title, message);
            return GeckoResult.fromValue(prompt.dismiss());
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/prompt", "Host.onJsAlert threw", t);
            return null;
        }
    }

    @Nullable
    @Override
    public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onButtonPrompt(
            @NonNull GeckoSession session,
            @NonNull GeckoSession.PromptDelegate.ButtonPrompt prompt) {
        try {
            String title = prompt.title != null ? prompt.title : "";
            String message = prompt.message != null ? prompt.message : "";
            boolean confirmed = mHost.onJsConfirm(title, message);
            return GeckoResult.fromValue(confirmed
                    ? prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)
                    : prompt.dismiss());
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/prompt", "Host.onJsConfirm threw", t);
            return null;
        }
    }

    @Nullable
    @Override
    public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onTextPrompt(
            @NonNull GeckoSession session,
            @NonNull GeckoSession.PromptDelegate.TextPrompt prompt) {
        try {
            String title = prompt.title != null ? prompt.title : "";
            String message = prompt.message != null ? prompt.message : "";
            String value = mHost.onJsPrompt(title, message, prompt.defaultValue);
            return GeckoResult.fromValue(
                    value != null ? prompt.confirm(value) : prompt.dismiss());
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/prompt", "Host.onJsPrompt threw", t);
            return null;
        }
    }
}
