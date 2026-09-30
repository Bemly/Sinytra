package android.webkit;

import androidx.annotation.NonNull;

// Sinytra: framework-typed HttpAuthHandler wired to a live Gecko
// AuthPrompt (device framework.jar dex dump: Landroid/webkit/
// HttpAuthHandler is PUBLIC with PUBLIC ctor + PUBLIC cancel/proceed/
// useHttpAuthUsernamePassword — same-package subclass satisfies javac
// while the runtime ctor is public; same pattern as
// SinytraSslErrorHandler / SinytraWebMessagePort).
//
// Why self-written (AGENTS.md §4): the framework token is an empty
// shell — Chromium's glue completes the network challenge from the
// app's proceed()/cancel() answer. Our bridge must do the same, but
// the AuthPrompt lives in org.mozilla.* which this package must not
// import: the Decision callback is injected (same decision-injection
// style as SinytraWebMessagePort.Binding).
//
// proceed(user, pass) -> Decision.proceed(user, pass); cancel() ->
// Decision.cancel(). Exactly-once: late duplicates ignored loudly.
public class SinytraHttpAuthHandler extends HttpAuthHandler {
    public interface Decision {
        void proceed(@NonNull String username, @NonNull String password);

        void cancel();
    }

    @NonNull
    private final Decision mDecision;
    private final boolean mUsedStoredCredentials;
    private volatile boolean mAnswered;

    public SinytraHttpAuthHandler(@NonNull Decision decision,
            boolean usedStoredCredentials) {
        mDecision = decision;
        mUsedStoredCredentials = usedStoredCredentials;
    }

    @Override
    public void proceed(String username, String password) {
        if (!markAnswered("proceed")) {
            return;
        }
        final String user = username != null ? username : "";
        final String pass = password != null ? password : "";
        try {
            mDecision.proceed(user, pass);
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/session",
                    "auth decision proceed threw", t);
        }
    }

    @Override
    public void cancel() {
        if (!markAnswered("cancel")) {
            return;
        }
        try {
            mDecision.cancel();
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/session",
                    "auth decision cancel threw", t);
        }
    }

    @Override
    public boolean useHttpAuthUsernamePassword() {
        return mUsedStoredCredentials;
    }

    private boolean markAnswered(@NonNull String what) {
        if (mAnswered) {
            android.util.Log.w("Sinytra/session",
                    "auth handler already answered; ignoring " + what);
            return false;
        }
        mAnswered = true;
        return true;
    }
}
