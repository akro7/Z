package top.niunaijun.blackbox.closecode;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.WeakHashMap;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * TIGER injection entry point.
 *
 * Execution sequence (mirrors Samurai Engine BActivityThread + InGameModMenuController):
 *
 *  1. beforeCreateApplication  → EARLIEST hook point in the virtual 8BP process:
 *       • NativeCore.hideXposed()            — must run before any game code
 *       • copy .tiger_k / .menu_enabled      — auth + toggle files
 *       • copy + System.load(libakro.so)     — load the mod library
 *
 *  2. beforeApplicationOnCreate → backup inject in case (1) context is null
 *
 *  3. onActivityCreated (Samurai InGameModMenuController.onActivityCreated pattern)
 *       → attachMenuOverlay(activity): attach android.service.SurfaceView as a
 *         transparent overlay into the activity's content root, then schedule a
 *         2-second delayed show (mirrors InGameModMenuController.scheduleShow).
 *         This is what actually makes the ImGui menu VISIBLE.
 *
 *  4. onActivityResumed  → re-attach + show overlay if hasShown
 *  5. onActivityPaused   → hide overlay (remove show runnable)
 *  6. onActivityDestroyed → remove overlay view
 */
public class Entry {

    private static final String TAG        = "TIGER_Entry";
    private static final String TARGET_PKG = "com.miniclip.eightballpool";
    private static final String LOADER_PKG = "com.fs4ip.delta";
    private static final String LIB_NAME   = "libakro.so";
    private static final String KEY_FILE   = ".tiger_k";
    private static final String MENU_FLAG  = ".menu_enabled";

    /** SHOW_DELAY matches Samurai InGameModMenuController.SHOW_DELAY_MS */
    private static final long   SHOW_DELAY_MS = 2000L;

    private static volatile boolean sInjected = false;

    // Per-activity overlay state (mirrors Samurai MenuEntry + WeakHashMap<Activity,MenuEntry>)
    private static final WeakHashMap<Activity, OverlayEntry> sEntries = new WeakHashMap<>();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    // ── Public entry ─────────────────────────────────────────────────────────

    public static void attach() {
        BlackBoxCore.get().addAppLifecycleCallback(new AppLifecycleCallback() {

            // ── 1. Earliest: before Application object ─────────────────────
            @Override
            public void beforeCreateApplication(String pkg, String proc,
                                                Context ctx, int uid) {
                if (!TARGET_PKG.equals(pkg)) return;
                injectNow(ctx);
            }

            // ── 2. Backup: before Application.onCreate ─────────────────────
            @Override
            public void beforeApplicationOnCreate(String pkg, String proc,
                                                  Application app, int uid) {
                if (!TARGET_PKG.equals(pkg)) return;
                injectNow(app);
            }

            // ── 3. Activity created — attach overlay (Samurai pattern) ──────
            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
                if (!TARGET_PKG.equals(activity.getPackageName())) return;
                onCreated(activity, state);
            }

            // ── 4. Activity resumed ─────────────────────────────────────────
            @Override
            public void onActivityResumed(Activity activity) {
                if (!TARGET_PKG.equals(activity.getPackageName())) return;
                onResumed(activity);
            }

            // ── 5. Activity paused ──────────────────────────────────────────
            @Override
            public void onActivityPaused(Activity activity) {
                if (!TARGET_PKG.equals(activity.getPackageName())) return;
                onPaused(activity);
            }

            // ── 6. Activity destroyed ───────────────────────────────────────
            @Override
            public void onActivityDestroyed(Activity activity) {
                if (!TARGET_PKG.equals(activity.getPackageName())) return;
                onDestroyed(activity);
            }
        });

        Slog.d(TAG, "Entry.attach() registered — target=" + TARGET_PKG);
    }

    // ── Injection ─────────────────────────────────────────────────────────────

    private static synchronized void injectNow(Context ctx) {
        if (sInjected) return;

        // Resolve the game's files dir (try both paths — BlackBox may use either)
        String gameFilesDir = null;
        if (ctx != null) {
            try { gameFilesDir = ctx.getFilesDir().getAbsolutePath(); }
            catch (Throwable t) { Slog.w(TAG, "getFilesDir: " + t.getMessage()); }
        }
        if (gameFilesDir == null)
            gameFilesDir = "/data/data/" + TARGET_PKG + "/files";

        String loaderDir = resolveLoaderDir();

        // Copy auth + toggle files
        copyFile(loaderDir + "/" + KEY_FILE,  gameFilesDir + "/" + KEY_FILE);
        copyFile(loaderDir + "/" + MENU_FLAG, gameFilesDir + "/" + MENU_FLAG);

        // Copy mod library
        String libDst = gameFilesDir + "/" + LIB_NAME;
        copyFile(loaderDir + "/" + LIB_NAME, libDst);

        File libFile = new File(libDst);
        if (!libFile.exists()) {
            Slog.e(TAG, LIB_NAME + " not found at " + libDst + " — abort");
            return;
        }

        // CRITICAL: hideXposed() BEFORE System.load() so Helium sees a clean process
        try {
            NativeCore.hideXposed();
            Slog.d(TAG, "hideXposed() OK");
        } catch (Throwable t) {
            Slog.e(TAG, "hideXposed failed: " + t.getMessage());
        }

        try {
            System.load(libDst);
            sInjected = true;
            Slog.d(TAG, LIB_NAME + " loaded into " + TARGET_PKG);
        } catch (Throwable t) {
            Slog.e(TAG, "System.load failed: " + t.getMessage());
        }
    }

    // ── Overlay lifecycle (Samurai InGameModMenuController mirror) ────────────

    private static void onCreated(Activity activity, Bundle state) {
        ViewGroup root = getRootView(activity);
        if (root == null) return;

        OverlayEntry entry = sEntries.get(activity);
        if (entry != null) {
            // Re-attach existing view (e.g. activity was recreated)
            attachView(root, entry.overlay);
            return;
        }

        // Create the transparent SurfaceView overlay (ImGui draws into it via eglSwapBuffers hook)
        View overlay = createOverlay(activity);
        if (overlay == null) return;

        overlay.setVisibility(View.INVISIBLE); // hidden until scheduleShow fires

        if (!attachView(root, overlay)) return;

        OverlayEntry e = new OverlayEntry(overlay);
        sEntries.put(activity, e);
        scheduleShow(activity, e);
    }

    private static void onResumed(Activity activity) {
        ViewGroup root = getRootView(activity);
        if (root == null) return;

        OverlayEntry entry = sEntries.get(activity);
        if (entry == null) {
            onCreated(activity, null);
            entry = sEntries.get(activity);
            if (entry == null) return;
        }

        if (attachView(root, entry.overlay)) {
            if (entry.hasShown) {
                entry.overlay.setVisibility(View.VISIBLE);
            } else {
                scheduleShow(activity, entry);
            }
        }
    }

    private static void onPaused(Activity activity) {
        OverlayEntry entry = sEntries.get(activity);
        if (entry == null) return;
        if (entry.showRunnable != null) {
            sMainHandler.removeCallbacks(entry.showRunnable);
            entry.showRunnable = null;
        }
        entry.overlay.setVisibility(View.INVISIBLE);
    }

    private static void onDestroyed(Activity activity) {
        OverlayEntry entry = sEntries.remove(activity);
        if (entry == null) return;
        if (entry.showRunnable != null) {
            sMainHandler.removeCallbacks(entry.showRunnable);
        }
        ViewGroup parent = (entry.overlay.getParent() instanceof ViewGroup)
                           ? (ViewGroup) entry.overlay.getParent() : null;
        if (parent != null) parent.removeView(entry.overlay);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static View createOverlay(Activity activity) {
        try {
            // Use android.view.SurfaceView — the standard surface the native mod lib
            // draws into via eglSwapBuffers hook. The JNI exports in libakro.so
            // reference Java_android_view_SurfaceView_* symbols.
            android.view.SurfaceView sv = new android.view.SurfaceView(activity);
            sv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            sv.setTag("AKRO_OVERLAY_TAG");
            sv.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            sv.setZOrderOnTop(true);
            Slog.d(TAG, "Overlay SurfaceView created");
            return sv;
        } catch (Throwable t) {
            Slog.e(TAG, "createOverlay failed: " + t.getMessage());
            return null;
        }
    }

    private static boolean attachView(ViewGroup root, View overlay) {
        if (overlay.getParent() == root) return true;
        if (overlay.getParent() instanceof ViewGroup)
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        try {
            root.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return true;
        } catch (Throwable t) {
            Slog.e(TAG, "attachView failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Mirrors Samurai InGameModMenuController.scheduleShow():
     * delay 2 s then set VISIBLE. The delay gives the game time to finish
     * its own onCreate before the overlay renders.
     */
    private static void scheduleShow(final Activity activity, final OverlayEntry entry) {
        if (entry.showRunnable != null)
            sMainHandler.removeCallbacks(entry.showRunnable);

        entry.overlay.setVisibility(View.INVISIBLE);
        Runnable r = () -> {
            if (sEntries.get(activity) != entry) return;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            entry.hasShown = true;
            entry.showRunnable = null;
            entry.overlay.setVisibility(View.VISIBLE);
            Slog.d(TAG, "Overlay shown for " + activity.getClass().getSimpleName());
        };
        entry.showRunnable = r;
        sMainHandler.postDelayed(r, SHOW_DELAY_MS);
    }

    private static ViewGroup getRootView(Activity activity) {
        try {
            return (ViewGroup) activity.findViewById(android.R.id.content);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Resolve the loader's files directory (handles both /data/data/ and /data/user/0/) */
    private static String resolveLoaderDir() {
        // Try BlackBoxCore context first
        try {
            Context host = BlackBoxCore.getContext();
            if (host != null) {
                File f = host.getFilesDir();
                if (f != null && f.exists()) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {}
        // Fallback to raw paths
        File f1 = new File("/data/data/" + LOADER_PKG + "/files");
        if (f1.exists()) return f1.getAbsolutePath();
        File f2 = new File("/data/user/0/" + LOADER_PKG + "/files");
        if (f2.exists()) return f2.getAbsolutePath();
        return f1.getAbsolutePath();
    }

    private static void copyFile(String src, String dst) {
        try {
            File s = new File(src);
            if (!s.exists()) { Slog.w(TAG, "src not found: " + src); return; }
            File d = new File(dst);
            if (d.getParentFile() != null) d.getParentFile().mkdirs();
            try (FileInputStream  in  = new FileInputStream(s);
                 FileOutputStream out = new FileOutputStream(d)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Slog.e(TAG, "copyFile " + src + ": " + e.getMessage());
        }
    }

    // ── Per-activity overlay state ─────────────────────────────────────────────

    private static class OverlayEntry {
        final View overlay;
        Runnable   showRunnable;
        boolean    hasShown;

        OverlayEntry(View overlay) {
            this.overlay = overlay;
        }
    }
}
