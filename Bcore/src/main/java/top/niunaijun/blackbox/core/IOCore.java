package top.niunaijun.blackbox.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.TrieTree;

@SuppressLint("SdCardPath")
public class IOCore {
    public static final String TAG = "IOCore";

    private static final IOCore sIOCore = new IOCore();
    private static final TrieTree mTrieTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();

    public static IOCore get() {
        return sIOCore;
    }

    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath)
                || mRedirectMap.containsKey(origPath))
            return;
        mTrieTree.add(origPath);
        mRedirectMap.put(origPath, redirectPath);
        if (!new File(redirectPath).exists()) {
            FileUtils.mkdirs(redirectPath);
        }
        NativeCore.addIORule(origPath, redirectPath);
        Log.d(TAG, "Added redirect: " + origPath + " -> " + redirectPath);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path) || path.contains("/engine/"))
            return path;
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key)) {
            String dst = mRedirectMap.get(key);
            if (dst != null) {
                String result = path.replace(key, dst);
                Log.v(TAG, "Redirected: " + path + " -> " + result);
                return result;
            }
        }
        return path;
    }

    public File redirectPath(File path) {
        if (path == null) return null;
        String abs = path.getAbsolutePath();
        String redirected = redirectPath(abs);
        return abs.equals(redirected) ? path : new File(redirected);
    }

    public String redirectPath(String path, Map<String, String> rule) {
        if (TextUtils.isEmpty(path)) return path;
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key)) {
            String dst = rule.get(key);
            if (dst != null) return path.replace(key, dst);
        }
        return path;
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null) return null;
        return new File(redirectPath(path.getAbsolutePath(), rule));
    }

    public void enableRedirect(Context context) {
        Map<String, String> rule = new LinkedHashMap<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo appInfo = BlackBoxCore.getBPackageManager()
                    .getApplicationInfo(packageName, PackageManager.GET_META_DATA,
                            BlackBoxCore.getUserId());
            int hostUserId = BlackBoxCore.getHostUserId();

            // data dir redirects
            rule.put(String.format("/data/data/%s/lib", packageName), appInfo.nativeLibraryDir);
            rule.put(String.format("/data/user/%d/%s/lib", hostUserId, packageName), appInfo.nativeLibraryDir);
            rule.put(String.format("/data/data/%s", packageName), appInfo.dataDir);
            rule.put(String.format("/data/user/%d/%s", hostUserId, packageName), appInfo.dataDir);

            // external storage redirects (mirrors working source exactly)
            if (BlackBoxCore.getContext().getExternalCacheDir() != null
                    && context.getExternalCacheDir() != null) {
                File extUserDir = BEnvironment.getExternalUserDir(BlackBoxCore.getUserId());
                File androidDir = new File(Environment.getExternalStorageDirectory(), "Android");
                String sdRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
                String emulated = String.format("/storage/emulated/%d/Android", hostUserId);
                if (!androidDir.exists()) androidDir = new File(emulated);
                if (androidDir.exists()) {
                    File[] subdirs = androidDir.listFiles(File::isDirectory);
                    if (subdirs != null) {
                        for (File sub : subdirs) {
                            String name = sub.getName();
                            String dst = extUserDir.getAbsolutePath() + "/Android/" + name;
                            rule.put(sdRoot + "/Android/" + name, dst);
                            rule.put(emulated + "/" + name, dst);
                        }
                    } else {
                        String dst = extUserDir.getAbsolutePath() + "/Android";
                        rule.put(sdRoot + "/Android", dst);
                        rule.put(emulated, dst);
                    }
                } else {
                    String dst = extUserDir.getAbsolutePath();
                    rule.put(sdRoot + "/Android", dst);
                    rule.put(emulated, dst);
                }
                String obbDst = extUserDir.getAbsolutePath() + "/Android/obb";
                rule.put(sdRoot + "/Android/obb", obbDst);
                rule.put(emulated + "/obb", obbDst);
                String dataDst = extUserDir.getAbsolutePath() + "/Android/data";
                rule.put(sdRoot + "/Android/data", dataDst);
                rule.put(emulated + "/data", dataDst);
            }

            // root hiding (same as working source)
            android.MetaCore.RuntimeFlags.sHideRoot = BlackBoxCore.get().isHideRoot();
            if (android.MetaCore.RuntimeFlags.sHideRoot) {
                hideRoot(rule);
            }

            // proc redirect — ONLY cmdline, exactly like the working Samurai source.
            // /proc/self/maps is handled entirely at the native layer by FileSystemHook
            // (read() filter). Redirecting maps to a pre-written static file is WRONG:
            // that file is written before the game launches and is missing the game's
            // own lib entries — causing get8BPbase() to return 0 → black screen.
            addProcRedirect(rule);

        } catch (Exception e) {
            Log.e(TAG, "enableRedirect failed", e);
        }

        for (Map.Entry<String, String> entry : rule.entrySet()) {
            get().addRedirect(entry.getKey(), entry.getValue());
        }
        NativeCore.enableIO();
    }

    private void hideRoot(Map<String, String> rule) {
        String[] su = {
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        };
        for (String path : su) {
            rule.put(path, path + "-fake");
        }
    }

    /**
     * Only redirect cmdline — exactly matching the working Samurai Engine source.
     * Maps filtering is handled in native FileSystemHook via read() interception.
     */
    private void addProcRedirect(Map<String, String> rule) {
        int appPid = BlackBoxCore.getAppPid();
        String numericProc = "/proc/" + Process.myPid() + "/";
        String cmdline = new File(BEnvironment.getProcDir(appPid), "cmdline").getAbsolutePath();
        rule.put(numericProc + "cmdline", cmdline);
        rule.put("/proc/self/cmdline",    cmdline);
    }
}
