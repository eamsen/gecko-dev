package org.mozilla.geckoview.test.util;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.test.TestCrashHandler;

import android.os.Process;
import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.test.InstrumentationRegistry;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

public class RuntimeCreator {
    private static GeckoRuntime sRuntime;
    public static AtomicInteger sTestSupport = new AtomicInteger(0);
    public static final WebExtension TEST_SUPPORT_WEB_EXTENSION =
            new WebExtension("resource://android/assets/web_extensions/test-support/",
                    "test-support@mozilla.com",
                    WebExtension.Flags.ALLOW_CONTENT_MESSAGING);

    private static WebExtension.Port sBackgroundPort;

    private static WebExtension.PortDelegate sPortDelegate;

    private static WebExtension.MessageDelegate sMessageDelegate
            = new WebExtension.MessageDelegate() {
        @Nullable
        @Override
        public void onConnect(@NonNull WebExtension.Port port) {
            sBackgroundPort = port;
            port.setDelegate(sWrapperPortDelegate);
        }
    };

    private static WebExtension.PortDelegate sWrapperPortDelegate = new WebExtension.PortDelegate() {
        @Override
        public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port port) {
            if (sPortDelegate != null) {
                sPortDelegate.onPortMessage(message, port);
            }
        }
    };

    @AnyThread
    public static void createRuntimeIfNotExist() {

    }

    public static WebExtension.Port backgroundPort() {
        return sBackgroundPort;
    }

    public static void registerTestSupport() {
        sTestSupport.set(0);
        sRuntime.registerWebExtension(TEST_SUPPORT_WEB_EXTENSION)
                .accept(value -> {
                    sTestSupport.set(1);
                }, exception -> {
                    Log.e("RuntimeCreator", "Error registering TestSupport", exception);
                    sTestSupport.set(2);
                });
    }

    public static void setPortDelegate(WebExtension.PortDelegate portDelegate) {
        sPortDelegate = portDelegate;
    }

    @UiThread
    public static GeckoRuntime getRuntime() {
        if (sRuntime != null) {
            return sRuntime;
        }

        Log.e("sferrog", "starting runtime");

        final GeckoRuntimeSettings.Builder runtimeSettingsBuilder =
                new GeckoRuntimeSettings.Builder();
        runtimeSettingsBuilder.arguments(new String[]{"-purgecaches"})
                .extras(InstrumentationRegistry.getArguments())
                .remoteDebuggingEnabled(true)
                .consoleOutput(true);

        if (new Environment().isAutomation()) {
            runtimeSettingsBuilder.crashHandler(TestCrashHandler.class);
        }

        TEST_SUPPORT_WEB_EXTENSION.setMessageDelegate(sMessageDelegate, "browser");

        sRuntime = GeckoRuntime.create(
                InstrumentationRegistry.getTargetContext(),
                runtimeSettingsBuilder.build());

        registerTestSupport();

        sRuntime.setDelegate(() -> Process.killProcess(Process.myPid()));

        return sRuntime;
    }
}
