package com.fs4ip.delta;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;
import top.niunaijun.blackbox.closecode.Entry;

public class App extends Application {

    private static final String TAG        = "AKRO_App";
    private static final String TARGET_PKG = "com.miniclip.eightballpool";

    private boolean mBlackBoxAttached = false;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            BlackBoxCore.get().doAttachBaseContext(this, new ClientConfiguration() {

                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }

                @Override
                public boolean isHideRoot() {
                    return true;
                }

                @Override
                public boolean isEnableDaemonService() {
                    return false;
                }

                /**
                 * CRITICAL LAUNCH FIX — must return false.
                 *
                 * When true (the default), BlackBoxCore.startActivity() routes through
                 * LauncherActivity. That activity sets isRunning=true in onPause() and
                 * calls finish() in onResume() — so the moment the game starts or
                 * crashes, LauncherActivity resumes and destroys itself, which pops
                 * the LOADER's MainActivity to foreground. This is the exact "crash
                 * back to launcher" on phones and "black screen then back" on tablets.
                 *
                 * When false, BlackBoxCore.startActivity() calls
                 * getBActivityManager().startActivity() directly — exactly matching
                 * the Samurai Engine source LauncherActivity.launch() pattern:
                 *   SamuraiEngineCore.getBActivityManager().startActivity(intent, i)
                 * No intermediate activity, no auto-finish, no race condition.
                 */
                @Override
                public boolean isEnableLauncherActivity() {
                    return false;
                }
            });
            mBlackBoxAttached = true;
            Log.d(TAG, "doAttachBaseContext OK  hideRoot=true  launcherActivity=false");
        } catch (Throwable t) {
            Log.e(TAG, "doAttachBaseContext failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (!mBlackBoxAttached) {
            Log.e(TAG, "BlackBox not attached — skipping doCreate");
            return;
        }

        try {
            BlackBoxCore.get().doCreate();
            Log.d(TAG, "doCreate OK");

            Entry.attach();
            Log.d(TAG, "Entry.attach() OK");

            BlackBoxCore.get().addAppLifecycleCallback(new AppLifecycleCallback() {

                @Override
                public void beforeCreateApplication(String packageName, String processName,
                                                    Context context, int userId) {
                    if (!TARGET_PKG.equals(packageName)) return;
                    Log.d(TAG, "beforeCreateApplication: " + packageName
                               + " / " + processName + " uid=" + userId);
                }

                @Override
                public void beforeApplicationOnCreate(String packageName, String processName,
                                                      Application application, int userId) {
                    if (!TARGET_PKG.equals(packageName)) return;
                    Log.d(TAG, "beforeApplicationOnCreate: " + packageName
                               + " uid=" + userId);
                }

                @Override
                public void afterApplicationOnCreate(String packageName, String processName,
                                                     Application application, int userId) {
                    if (!TARGET_PKG.equals(packageName)) return;
                    Log.d(TAG, "afterApplicationOnCreate: " + packageName
                               + " uid=" + userId);
                }

                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    if (!TARGET_PKG.equals(activity.getPackageName())) return;
                    Log.d(TAG, "onActivityCreated: " + activity.getClass().getSimpleName());
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    if (!TARGET_PKG.equals(activity.getPackageName())) return;
                    Log.d(TAG, "onActivityResumed: " + activity.getClass().getSimpleName());
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    if (!TARGET_PKG.equals(activity.getPackageName())) return;
                    Log.d(TAG, "onActivityPaused: " + activity.getClass().getSimpleName());
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    if (!TARGET_PKG.equals(activity.getPackageName())) return;
                    Log.d(TAG, "onActivityDestroyed: " + activity.getClass().getSimpleName());
                }
            });

        } catch (Throwable t) {
            Log.e(TAG, "onCreate setup failed: " + t.getMessage(), t);
        }
    }
}
