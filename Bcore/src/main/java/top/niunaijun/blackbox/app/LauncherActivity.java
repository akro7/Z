package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.content.Intent;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * LauncherActivity — transparent, immediate, no auto-finish.
 *
 * Mirrors the Samurai Engine source exactly:
 *   public class LauncherActivity extends Activity {
 *       public static void launch(Intent intent, int i) {
 *           SamuraiEngineCore.getBActivityManager().startActivity(intent, i);
 *       }
 *   }
 *
 * The static launch() method is called by BlackBoxCore.startActivity() when
 * isEnableLauncherActivity() is true.  Since App.java now overrides
 * isEnableLauncherActivity() = false, BlackBoxCore.startActivity() calls
 * getBActivityManager().startActivity() directly and this class is never
 * instantiated.  It is kept here as a stub so the manifest declaration
 * compiles without error.
 *
 * REMOVED (was the bug):
 *   • onCreate() splash screen + animation
 *   • onPause()  → isRunning = true
 *   • onResume() → if (isRunning) finish()   ← THIS WAS KILLING ITSELF
 *     After the game started, LauncherActivity would resume and call finish(),
 *     bringing the LOADER's MainActivity back to foreground ("crash to launcher").
 */
public class LauncherActivity extends Activity {

    public static void launch(Intent intent, int userId) {
        try {
            BlackBoxCore.getBActivityManager().startActivity(intent, userId);
            Slog.d("LauncherActivity", "startActivity dispatched for " + intent.getPackage());
        } catch (Throwable t) {
            Slog.e("LauncherActivity", "startActivity failed: " + t.getMessage());
        }
    }
}
