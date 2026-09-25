package android.webkit;

import androidx.annotation.NonNull;
import java.util.Map;

// Sinytra: the provider-owned WebStorage singleton.
//
// Why a subclass (AGENTS.md §4, framework same-package adapter): the factory
// must return a WebStorage from getWebStorage(). Returning
// WebStorage.getInstance() only works while some OTHER provider is the
// system one — once Sinytra is switched in (BOOTSTRAP §2), getInstance()
// routes through WebViewFactory.getProvider() back into our own factory and
// recurses (PoC device log, 2026-09-22). The public SDK android.jar strips
// the ctor to package-private, but the device framework's WebStorage() is
// PUBLIC (dexdump of /system/framework/framework.jar, 2026-09-26) — same
// pattern as SinytraSslErrorHandler / SinytraWebMessagePort: package
// placement satisfies javac, the real public ctor satisfies the runtime.
//
// No state here: every call goes through the injected Binding (storage/
// facade over GeckoView's StorageController); no org.mozilla dependency.
public final class SinytraWebStorage extends WebStorage {
    /** Provider-side implementation (GeckoWebStorageFacade). */
    public interface Binding {
        void getOrigins(@NonNull ValueCallback<Map> callback);

        void getUsageForOrigin(@NonNull String origin, @NonNull ValueCallback<Long> callback);

        void getQuotaForOrigin(@NonNull String origin, @NonNull ValueCallback<Long> callback);

        void deleteOrigin(@NonNull String origin);

        void deleteAllData();
    }

    @NonNull
    private final Binding mBinding;

    public SinytraWebStorage(@NonNull Binding binding) {
        mBinding = binding;
    }

    @Override
    public void getOrigins(ValueCallback<Map> callback) {
        if (callback != null) {
            mBinding.getOrigins(callback);
        }
    }

    @Override
    public void getUsageForOrigin(String origin, ValueCallback<Long> callback) {
        if (origin != null && callback != null) {
            mBinding.getUsageForOrigin(origin, callback);
        }
    }

    @Override
    public void getQuotaForOrigin(String origin, ValueCallback<Long> callback) {
        if (origin != null && callback != null) {
            mBinding.getQuotaForOrigin(origin, callback);
        }
    }

    @Override
    @Deprecated
    public void setQuotaForOrigin(String origin, long quota) {
        // Deprecated no-op in the framework (quota is engine-managed); Gecko
        // manages quota itself too.
    }

    @Override
    public void deleteOrigin(String origin) {
        if (origin != null) {
            mBinding.deleteOrigin(origin);
        }
    }

    @Override
    public void deleteAllData() {
        mBinding.deleteAllData();
    }
}
